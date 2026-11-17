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
    private var tts: TextToSpeech? = null
    private var ready = false
    private var appCtx: Context? = null
    private val pending = mutableListOf<String>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile var speaking = false
    var onStart: (() -> Unit)? = null
    var onDone: (() -> Unit)? = null

    fun init(ctx: Context) {
        if (tts != null) return
        appCtx = ctx.applicationContext
        tts = TextToSpeech(appCtx, this)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        ready = true
        tts?.language = Locale("pt", "BR")
        tts?.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        )
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { speaking = true; main.post { onStart?.invoke() } }
            override fun onDone(id: String?) { speaking = false; main.post { onDone?.invoke() } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { speaking = false; main.post { onDone?.invoke() } }
        })
        pending.toList().forEach { speak(it) }
        pending.clear()
    }

    private fun online(): Boolean {
        val cm = appCtx?.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun ptVoices(): List<Voice> = try {
        (tts?.voices ?: emptySet()).filter {
            it.locale.language == "pt" && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        }
    } catch (e: Exception) { emptyList() }

    /** Escolhe uma voz feminina, natural e tranquila (as vozes "online" do Google soam bem menos robóticas). */
    private fun bestVoice(): Voice? {
        val net = online()
        return ptVoices().maxByOrNull { v ->
            val n = v.name.lowercase()
            var s = 0
            if (v.locale.country.equals("BR", true)) s += 40
            if (Regex("afs|female|feminina|-f-|luciana|francisca|thalita|maria").containsMatchIn(n)) s += 25
            if (Regex("ptd|male|masculin").containsMatchIn(n) && !n.contains("female")) s -= 30
            if (v.isNetworkConnectionRequired) s += if (net) 12 else -50
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

    fun speak(text: String) {
        val t = tts
        if (t == null || !ready) { pending.add(text); return }
        val p = appCtx?.let { Prefs.of(it) }
        val rate = p?.getFloat("rate", 0.95f) ?: 0.95f
        val pitch = p?.getFloat("pitch", 1.0f) ?: 1.0f
        val chosen = p?.getString("voice", "") ?: ""
        val v = ptVoices().firstOrNull { it.name == chosen && (!it.isNetworkConnectionRequired || online()) } ?: bestVoice()
        if (v != null) t.voice = v else t.language = Locale("pt", "BR")
        t.setSpeechRate(rate)
        t.setPitch(pitch)
        speaking = true
        t.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "ana-" + System.currentTimeMillis())
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
