package com.ana.assistente

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    companion object {
        @Volatile var visible = false
        private const val HOME = "https://appassets.androidplatform.net/assets/www/index.html"
        private const val REQ_BASIC = 10
        private const val REQ_WAKE = 11
        private const val REQ_CAL = 12
    }

    private lateinit var web: WebView
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var pageReady = false
    private var pendingListen = false
    private var listening = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.channels(this)
        Speaker.init(this)
        Speaker.onStart = { WakeWordService.send(this, WakeWordService.ACTION_PAUSE) }
        Speaker.onDone = { resumeWakeSoon() }

        web = WebView(this)
        setContentView(web)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == "appassets.androidplatform.net") return false
                openExternal(u.toString())
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                if (pendingListen) { pendingListen = false; startListening() }
                if (intent?.getBooleanExtra("chat", false) == true) js("go('chat')")
            }
        }
        web.addJavascriptInterface(Bridge(), "AnaNative")
        web.loadUrl(HOME)

        val p = Prefs.of(this)
        if (!p.getBoolean("askedBasic", false)) {
            p.edit().putBoolean("askedBasic", true).apply()
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), REQ_BASIC)
        }
        // se a escuta estava ligada (ex.: após reiniciar o celular), religa agora que o app está aberto
        if (p.getBoolean("wake", false) && hasMic()) WakeWordService.send(this, WakeWordService.ACTION_START)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.getBooleanExtra("listen", false)) {
            i.removeExtra("listen")
            showOverLockScreen()
            if (pageReady) startListening() else pendingListen = true
        }
        if (i.getBooleanExtra("chat", false) && pageReady) js("go('chat')")
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
    }

    override fun onResume() { super.onResume(); visible = true; if (pageReady) js("window.anaResume && anaResume()") }
    override fun onPause() {
        super.onPause(); visible = false
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(false); setTurnScreenOn(false) }
    }

    override fun onDestroy() { recognizer?.destroy(); super.onDestroy() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // volta para a tela inicial da Ana; se já estiver nela, só minimiza (ela continua por perto)
        web.evaluateJavascript("window.anaBack ? anaBack() : false") { handled ->
            if (handled != "true") moveTaskToBack(true)
        }
    }

    private fun js(code: String) = main.post { if (::web.isInitialized) web.evaluateJavascript(code, null) }
    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun hasCal() = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun resumeWakeSoon() = main.postDelayed({
        if (!listening && !Speaker.speaking) WakeWordService.send(this, WakeWordService.ACTION_RESUME)
    }, 700)

    private fun openExternal(url: String) {
        try {
            val i = Intent(if (url.startsWith("tel:")) Intent.ACTION_DIAL else Intent.ACTION_VIEW, Uri.parse(url))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (_: ActivityNotFoundException) { js("toast('Não encontrei um app para abrir isso.')") }
    }

    /* ---------- ouvir o pedido (depois do "Ana") ---------- */
    private fun startListening() = main.post {
        if (!hasMic()) { ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_BASIC); return@post }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { js("toast('Instale/atualize o app Google para eu entender sua voz.')"); return@post }
        WakeWordService.send(this, WakeWordService.ACTION_PAUSE)
        Speaker.stop()
        listening = true
        js("anaListening(true)")
        main.postDelayed({
            recognizer?.destroy()
            val r = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { js("anaListening(true)") }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onPartialResults(partial: Bundle?) {
                    val t = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                    js("anaPartial(${JSONObject.quote(t)})")
                }
                override fun onResults(results: Bundle?) {
                    listening = false
                    val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    js("anaListening(false)")
                    if (!t.isNullOrBlank()) js("anaHeard(${JSONObject.quote(t)})")
                    main.postDelayed({ resumeWakeSoon() }, 1500)
                }
                override fun onError(error: Int) {
                    listening = false
                    js("anaListening(false)")
                    resumeWakeSoon()
                }
            })
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            try { r.startListening(i) } catch (e: Exception) { listening = false; js("anaListening(false)") }
        }, 350)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_WAKE && hasMic()) enableWake()
        js("window.anaPermsChanged && anaPermsChanged()")
    }

    private fun enableWake() {
        Prefs.of(this).edit().putBoolean("wake", true).apply()
        WakeWordService.send(this, WakeWordService.ACTION_START)
        // para abrir sozinha quando você chamar (mesmo com outro app aberto ou a tela desligada)
        if (!Settings.canDrawOverlays(this)) {
            js("toast('Permita “Aparecer sobre outros apps” para eu abrir sozinha quando você chamar.')")
            main.postDelayed({
                try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
            }, 1800)
        } else askBattery()
    }

    @SuppressLint("BatteryLife")
    private fun askBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
        }
    }

    /* ---------- ponte JavaScript <-> Android ---------- */
    inner class Bridge {
        @JavascriptInterface fun isNative() = true

        @JavascriptInterface fun speak(text: String) { main.post { Speaker.speak(text) } }
        @JavascriptInterface fun stopSpeaking() { main.post { Speaker.stop() } }
        @JavascriptInterface fun voices(): String = Speaker.voicesJson()
        @JavascriptInterface fun setVoice(name: String, rate: Float, pitch: Float) {
            Prefs.of(this@MainActivity).edit().putString("voice", name).putFloat("rate", rate).putFloat("pitch", pitch).apply()
        }

        @JavascriptInterface fun listen() = startListening()

        @JavascriptInterface fun setName(name: String) {
            Prefs.of(this@MainActivity).edit()
                .putString("name", WakeWordService.normalize(name).split(" ").firstOrNull()?.ifBlank { "ana" } ?: "ana")
                .putString("displayName", name.ifBlank { "Ana" }).apply()
            if (WakeWordService.running) WakeWordService.send(this@MainActivity, WakeWordService.ACTION_RESUME)
        }

        @JavascriptInterface fun setWake(on: Boolean) = main.post {
            if (on) {
                if (hasMic()) enableWake()
                else ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_WAKE)
            } else {
                Prefs.of(this@MainActivity).edit().putBoolean("wake", false).apply()
                WakeWordService.send(this@MainActivity, WakeWordService.ACTION_STOP)
            }
        }
        @JavascriptInterface fun wakeOn() = Prefs.of(this@MainActivity).getBoolean("wake", false)
        @JavascriptInterface fun setSpeakAlarms(on: Boolean) { Prefs.of(this@MainActivity).edit().putBoolean("speakAlarms", on).apply() }

        @JavascriptInterface fun perms(): String {
            val pm = getSystemService(PowerManager::class.java)
            val notif = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            return JSONObject()
                .put("mic", hasMic()).put("notif", notif)
                .put("overlay", Settings.canDrawOverlays(this@MainActivity))
                .put("battery", pm.isIgnoringBatteryOptimizations(packageName))
                .put("calendar", hasCal()).toString()
        }
        @JavascriptInterface fun askPerm(which: String) = main.post {
            when (which) {
                "mic" -> ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_BASIC)
                "notif" -> if (Build.VERSION.SDK_INT >= 33) ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_BASIC)
                "overlay" -> try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
                "battery" -> askBattery()
                "calendar" -> ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), REQ_CAL)
            }
        }

        @JavascriptInterface fun notifyUser(title: String, body: String) = Notifier.show(this@MainActivity, title, body, body.hashCode())
        @JavascriptInterface fun syncAlarms(json: String) = AlarmScheduler.sync(this@MainActivity, json)
        @JavascriptInterface fun openUrl(url: String) = main.post { openExternal(url) }

        /* calendário do celular */
        @JavascriptInterface fun calendarRead(days: Int): String {
            if (!hasCal()) return "[]"
            val arr = JSONArray()
            val now = System.currentTimeMillis()
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                android.content.ContentUris.appendId(it, now - 86400000L)
                android.content.ContentUris.appendId(it, now + days * 86400000L)
            }.build()
            val proj = arrayOf(
                CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.DISPLAY_COLOR
            )
            try {
                contentResolver.query(uri, proj, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
                    while (c.moveToNext() && arr.length() < 300) {
                        arr.put(JSONObject().put("id", c.getLong(0)).put("title", c.getString(1) ?: "(sem título)")
                            .put("begin", c.getLong(2)).put("end", c.getLong(3)).put("allDay", c.getInt(4) == 1)
                            .put("place", c.getString(5) ?: "").put("color", String.format("#%06X", 0xFFFFFF and c.getInt(6))))
                    }
                }
            } catch (_: Exception) {}
            return arr.toString()
        }

        @JavascriptInterface fun calendarInsert(json: String): String {
            if (!hasCal()) return ""
            return try {
                val o = JSONObject(json)
                var calId = -1L
                contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
                    arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
                    CalendarContract.Calendars.VISIBLE + "=1 AND " + CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + ">=" + CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
                    null, CalendarContract.Calendars.IS_PRIMARY + " DESC")?.use { c -> if (c.moveToFirst()) calId = c.getLong(0) }
                if (calId < 0) return ""
                val allDay = o.optBoolean("allDay")
                val v = ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, calId)
                    put(CalendarContract.Events.TITLE, o.optString("title"))
                    put(CalendarContract.Events.EVENT_LOCATION, o.optString("place"))
                    put(CalendarContract.Events.DTSTART, o.optLong("begin"))
                    put(CalendarContract.Events.DTEND, o.optLong("end"))
                    put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
                    put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else TimeZone.getDefault().id)
                    put(CalendarContract.Events.DESCRIPTION, "Criado pela Ana")
                }
                val id = contentResolver.insert(CalendarContract.Events.CONTENT_URI, v)?.lastPathSegment ?: return ""
                val remind = o.optInt("remind", -1)
                if (remind >= 0) contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, id.toLong())
                    put(CalendarContract.Reminders.MINUTES, remind)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                })
                id
            } catch (_: Exception) { "" }
        }

        @JavascriptInterface fun calendarDelete(id: String) {
            if (!hasCal() || id.isBlank()) return
            try { contentResolver.delete(android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id.toLong()), null, null) } catch (_: Exception) {}
        }

        /* salvar arquivo (backup) na pasta Downloads */
        @JavascriptInterface fun saveFile(name: String, content: String, mime: String): Boolean = try {
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
                contentResolver.openOutputStream(uri)!!.use { it.write(content.toByteArray()) }
                true
            } else {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, content), name))
                true
            }
        } catch (_: Exception) { false }
    }
}
