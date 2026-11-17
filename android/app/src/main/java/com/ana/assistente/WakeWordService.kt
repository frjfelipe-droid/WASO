package com.ana.assistente

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.text.Normalizer

/**
 * Fica ouvindo em segundo plano (mesmo com a tela desligada) só a palavra "Ana".
 * O reconhecimento é 100% no aparelho (Vosk): nada do que você fala sai do celular
 * até você chamar a Ana.
 */
class WakeWordService : Service(), RecognitionListener {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        @Volatile var running = false
        private var model: Model? = null

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, WakeWordService::class.java).setAction(action)
            when (action) {
                ACTION_START -> ContextCompat.startForegroundService(ctx, i)
                else -> if (running) try { ctx.startService(i) } catch (_: Exception) {}
            }
        }

        fun normalize(s: String): String =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
                .replace(Regex("[^a-z ]"), "").trim()
    }

    private val main = Handler(Looper.getMainLooper())
    private var speech: SpeechService? = null
    private var paused = false
    private val autoResume = Runnable { paused = false; listen() }
    private val retry = Runnable { listen() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> {
                if (!goForeground()) return START_NOT_STICKY
                running = true
                paused = false
                if (model == null) loadModel() else listen()
            }
            ACTION_STOP -> {
                Prefs.of(this).edit().putBoolean("wake", false).apply()
                stopSelf()
            }
            ACTION_PAUSE -> {
                paused = true
                stopRecognizer()
                main.removeCallbacks(autoResume)
                main.postDelayed(autoResume, 25000) // segurança: volta a ouvir sozinha
            }
            ACTION_RESUME -> {
                main.removeCallbacks(autoResume)
                paused = false
                listen()
            }
        }
        return START_STICKY
    }

    private fun goForeground(): Boolean {
        Notifier.channels(this)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val name = Prefs.of(this).getString("displayName", "Ana") ?: "Ana"
        val n = NotificationCompat.Builder(this, Notifier.CH_WAKE)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(0xFFB9A3FF.toInt())
            .setContentTitle("$name está por perto")
            .setContentText("Diga “Ei, $name” quando precisar")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(Notifier.openApp(this, null, 2))
            .addAction(0, "Parar de ouvir", stop)
            .build()
        return try {
            val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            ServiceCompat.startForeground(this, 1, n, type)
            true
        } catch (e: Exception) {
            Log.w("Ana", "não foi possível ouvir em segundo plano", e)
            stopSelf()
            false
        }
    }

    private fun loadModel() {
        StorageService.unpack(this, "model-pt", "model",
            { m: Model -> model = m; listen() },
            { e -> Log.e("Ana", "modelo de voz", e); stopSelf() })
    }

    private fun stopRecognizer() {
        try { speech?.stop(); speech?.shutdown() } catch (_: Exception) {}
        speech = null
    }

    private fun listen() {
        main.removeCallbacks(retry)
        val m = model ?: return
        if (paused || !running || Speaker.speaking) return
        stopRecognizer()
        val name = normalize(Prefs.wakeName(this))
        val words = JSONArray().put(name).put("ei $name").put("oi $name").put("e ai $name").put("[unk]")
        try {
            val rec = Recognizer(m, 16000f, words.toString())
            speech = SpeechService(rec, 16000f).also { it.startListening(this) }
        } catch (e: Exception) {
            Log.e("Ana", "microfone ocupado", e)
            main.postDelayed(retry, 3000)
        }
    }

    private fun heard(hypothesis: String?) {
        val text = try { JSONObject(hypothesis ?: "{}").optString("text") } catch (_: Exception) { "" }
        if (text.isBlank()) return
        val name = normalize(Prefs.wakeName(this))
        if (text.split(" ").contains(name)) triggered()
    }

    private fun triggered() {
        paused = true
        stopRecognizer()
        main.removeCallbacks(autoResume)
        main.postDelayed(autoResume, 25000)
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra("listen", true)
        if (MainActivity.visible || Settings.canDrawOverlays(this)) {
            startActivity(open)
        } else {
            // sem a permissão "aparecer sobre outros apps" o Android só deixa abrir por notificação
            val pi = PendingIntent.getActivity(this, 3, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(this, Notifier.CH_CALL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Oi! Pode falar 💜")
                .setContentText("Toque aqui para falar com a Ana")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setTimeoutAfter(15000)
                .build()
            try { NotificationManagerCompat.from(this).notify(3, n) } catch (_: SecurityException) {}
        }
    }

    override fun onPartialResult(hypothesis: String?) {}
    override fun onResult(hypothesis: String?) = heard(hypothesis)
    override fun onFinalResult(hypothesis: String?) = heard(hypothesis)
    override fun onError(exception: Exception?) { stopRecognizer(); main.postDelayed(retry, 2000) }
    override fun onTimeout() { listen() }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        stopRecognizer()
        super.onDestroy()
    }
}
