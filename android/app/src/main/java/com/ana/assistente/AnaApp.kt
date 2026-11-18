package com.ana.assistente

import android.app.Application
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter

/** Guarda o erro se o app fechar sozinho, para mostrar na próxima abertura. */
class AnaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                Log.e("Ana", "FALHA na thread ${t.name}", e)
                Prefs.of(this).edit().putString("lastCrash", "Thread: ${t.name}\n$sw".take(4000)).commit()
            } catch (_: Throwable) {}
            previous?.uncaughtException(t, e)
        }
    }
}
