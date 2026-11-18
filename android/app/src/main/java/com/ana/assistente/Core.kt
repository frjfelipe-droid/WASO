package com.ana.assistente

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object Prefs {
    fun of(ctx: Context): SharedPreferences = ctx.getSharedPreferences("ana", Context.MODE_PRIVATE)
    fun wakeName(ctx: Context): String = of(ctx).getString("name", "ana")!!.ifBlank { "ana" }
}

/* ---------------- voz (fala) ---------------- */
object Speaker : TextToSpeech.OnInitListener {
    private const val GOOGLE_TTS = "com.google.android.tts"
    private var tts: TextToSpeech? = null
    private var ready = false
    private var triedDefault = false
    private var appCtx: Context? = null
    private val pending = mutableListOf<String>()
    private val main = Handler(Looper.getMainLooper())
    private var lastText = ""
    private var lastWasNetwork = false
    private var forceLocal = false
    @Volatile var speaking = false
    var onStart: (() -> Unit)? = null
    var onDone: (() -> Unit)? = null
    /** avisos para a interface (ex.: voz em português não instalada) */
    var onProblem: ((String) -> Unit)? = null

    private fun hasGoogleTts(ctx: Context) = try { ctx.packageManager.getPackageInfo(GOOGLE_TTS, 0); true } catch (_: Exception) { false }

    fun init(ctx: Context) {
        if (tts != null) return
        appCtx = ctx.applicationContext
        // a voz do Google é a mais natural em português; muitos celulares (ex.: Samsung) usam outro motor por padrão
        tts = if (hasGoogleTts(appCtx!!)) TextToSpeech(appCtx, this, GOOGLE_TTS) else { triedDefault = true; TextToSpeech(appCtx, this) }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            android.util.Log.w("Ana", "motor de voz falhou ($status)")
            if (!triedDefault) { triedDefault = true; try { tts?.shutdown() } catch (_: Exception) {}; tts = TextToSpeech(appCtx, this); return }
            main.post { onProblem?.invoke("noengine") }
            return
        }
        ready = true
        val br = Locale("pt", "BR")
        val avail = tts?.isLanguageAvailable(br) ?: TextToSpeech.LANG_NOT_SUPPORTED
        if (avail < TextToSpeech.LANG_AVAILABLE) {
            android.util.Log.w("Ana", "português não disponível no motor ${tts?.defaultEngine} ($avail)")
            if (!triedDefault) { triedDefault = true; ready = false; try { tts?.shutdown() } catch (_: Exception) {}; tts = TextToSpeech(appCtx, this); return }
            main.post { onProblem?.invoke("nolang") }
        }
        tts?.language = br
        tts?.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        )
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { speaking = true; main.post { onStart?.invoke() } }
            override fun onDone(id: String?) { speaking = false; forceLocal = false; main.post { onDone?.invoke() } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) = failed(-1)
            override fun onError(id: String?, errorCode: Int) = failed(errorCode)
        })
        android.util.Log.i("Ana", "voz pronta: motor ${tts?.defaultEngine}, ${ptVoices().size} vozes em português")
        pending.toList().forEach { speak(it) }
        pending.clear()
    }

    /** Se a voz online falhar (internet lenta), fala de novo com uma voz offline. */
    private fun failed(code: Int) {
        android.util.Log.w("Ana", "erro ao falar ($code), voz online=$lastWasNetwork")
        if (lastWasNetwork && !forceLocal && lastText.isNotBlank()) {
            forceLocal = true
            main.post { speak(lastText) }
            return
        }
        speaking = false; forceLocal = false
        main.post { onDone?.invoke() }
    }

    private fun online(): Boolean = try {
        val cm = appCtx?.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (_: Throwable) { false }

    private fun ptVoices(): List<Voice> = try {
        (tts?.voices ?: emptySet()).filter {
            it.locale.language == "pt" && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        }
    } catch (e: Exception) { emptyList() }

    /** Escolhe uma voz feminina, natural e tranquila (as vozes "online" do Google soam bem menos robóticas). */
    private fun bestVoice(allowNetwork: Boolean): Voice? {
        val net = allowNetwork && online()
        return ptVoices().filter { net || !it.isNetworkConnectionRequired }.maxByOrNull { v ->
            val n = v.name.lowercase()
            var s = 0
            if (v.locale.country.equals("BR", true)) s += 40
            if (Regex("afs|female|feminina|-f-|luciana|francisca|thalita|maria").containsMatchIn(n)) s += 25
            if (Regex("ptd|male|masculin").containsMatchIn(n) && !n.contains("female")) s -= 30
            if (v.isNetworkConnectionRequired) s += 12
            s += v.quality / 50
            s - v.latency / 200
        }
    }

    fun voicesJson(): String {
        val arr = JSONArray()
        ptVoices().sortedBy { it.name }.forEach {
            arr.put(JSONObject().put("name", it.name).put("country", it.locale.country)
                .put("online", it.isNetworkConnectionRequired).put("quality", it.quality))
        }
        return arr.toString()
    }

    fun mediaVolume(): Int = try {
        val am = appCtx!!.getSystemService(android.media.AudioManager::class.java)
        am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
    } catch (_: Throwable) { -1 }

    fun speak(text: String) {
        try { doSpeak(text) } catch (e: Throwable) {
            android.util.Log.e("Ana", "falar", e); speaking = false; main.post { onDone?.invoke() }
        }
    }

    private fun doSpeak(text: String) {
        val t = tts
        if (t == null || !ready) { pending.clear(); pending.add(text); return }
        val p = appCtx?.let { Prefs.of(it) }
        val rate = p?.getFloat("rate", 0.95f) ?: 0.95f
        val pitch = p?.getFloat("pitch", 1.0f) ?: 1.0f
        val chosen = p?.getString("voice", "") ?: ""
        val allowNet = !forceLocal
        val v = ptVoices().firstOrNull { it.name == chosen && (!it.isNetworkConnectionRequired || (allowNet && online())) } ?: bestVoice(allowNet)
        if (v != null) t.voice = v else t.language = Locale("pt", "BR")
        lastText = text
        lastWasNetwork = v?.isNetworkConnectionRequired == true
        t.setSpeechRate(rate)
        t.setPitch(pitch)
        speaking = true
        android.util.Log.i("Ana", "falando: ${text.take(60)} (voz ${v?.name})")
        val r = t.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "ana-" + System.currentTimeMillis())
        if (r != TextToSpeech.SUCCESS) failed(r)
    }

    fun stop() { tts?.stop(); speaking = false }
}

/* ---------------- notificações ---------------- */
object Notifier {
    const val CH_REMIND = "ana_lembretes"
    const val CH_WAKE = "ana_escuta"
    const val CH_CALL = "ana_chamado"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_REMIND, "Lembretes e mensagens da Ana", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Compromissos, bom dia, boa noite e “como você está?”"
            enableVibration(true)
        })
        nm.createNotificationChannel(NotificationChannel(CH_WAKE, "Ana ouvindo", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Aviso fixo enquanto a Ana espera você chamar"
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_CALL, "Ana chamada", NotificationManager.IMPORTANCE_HIGH))
    }

    fun openApp(ctx: Context, extra: String? = null, code: Int = 0): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (extra != null) i.putExtra(extra, true)
        return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun show(ctx: Context, title: String, body: String, id: Int) {
        channels(ctx)
        val n = NotificationCompat.Builder(ctx, CH_REMIND)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(0xFFB9A3FF.toInt())
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, "chat", id))
            .build()
        try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (_: SecurityException) {}
    }
}

/* ---------------- alarmes (funcionam com o app fechado) ---------------- */
object AlarmScheduler {
    fun sync(ctx: Context, json: String) {
        val p = Prefs.of(ctx)
        cancelAll(ctx, p.getString("alarms", "[]")!!)
        p.edit().putString("alarms", json).apply()
        scheduleAll(ctx, json)
    }

    fun rescheduleAll(ctx: Context) = scheduleAll(ctx, Prefs.of(ctx).getString("alarms", "[]")!!)

    private fun pi(ctx: Context, o: JSONObject): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java)
            .putExtra("title", o.optString("title"))
            .putExtra("body", o.optString("body"))
            .putExtra("id", o.optString("id"))
        return PendingIntent.getBroadcast(ctx, o.optString("id").hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun cancelAll(ctx: Context, json: String) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        try {
            val arr = JSONArray(json)
            for (k in 0 until arr.length()) am.cancel(pi(ctx, arr.getJSONObject(k)))
        } catch (_: Exception) {}
    }

    private fun scheduleAll(ctx: Context, json: String) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val now = System.currentTimeMillis()
        try {
            val arr = JSONArray(json)
            for (k in 0 until arr.length()) {
                val o = arr.getJSONObject(k)
                val at = o.optLong("at")
                if (at <= now) continue
                val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
                if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx, o))
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx, o))
            }
        } catch (_: Exception) {}
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: "Ana"
        val body = intent.getStringExtra("body") ?: ""
        Notifier.show(ctx, title, body, (intent.getStringExtra("id") ?: body).hashCode())
        // com o app fechado, ela também fala o lembrete em voz alta
        if (!MainActivity.visible && Prefs.of(ctx).getBoolean("speakAlarms", true)) {
            val pr = goAsync()
            WakeWordService.send(ctx, WakeWordService.ACTION_PAUSE)
            Speaker.init(ctx)
            Speaker.onStart = null
            Speaker.onDone = { WakeWordService.send(ctx, WakeWordService.ACTION_RESUME) }
            Speaker.speak(body.replace(Regex("[\\p{So}\\p{Cn}]"), ""))
            Handler(Looper.getMainLooper()).postDelayed({ try { pr.finish() } catch (_: Exception) {} }, 9000)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        AlarmScheduler.rescheduleAll(ctx)
        if (!Prefs.of(ctx).getBoolean("wake", false)) return
        // Android 14+ não deixa ligar o microfone sozinho após reiniciar: pede um toque.
        val started = Build.VERSION.SDK_INT < 34 && try {
            WakeWordService.send(ctx, WakeWordService.ACTION_START); true
        } catch (_: Exception) { false }
        if (!started) {
            Notifier.channels(ctx)
            val n = NotificationCompat.Builder(ctx, Notifier.CH_REMIND)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Toque para a Ana voltar a ouvir")
                .setContentText("O celular reiniciou. Abra a Ana uma vez para reativar o “Ei, Ana”.")
                .setAutoCancel(true)
                .setContentIntent(Notifier.openApp(ctx, "wake", 7))
                .build()
            try { NotificationManagerCompat.from(ctx).notify(7, n) } catch (_: SecurityException) {}
        }
    }
}
