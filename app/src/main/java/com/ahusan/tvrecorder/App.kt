package com.ahusan.tvrecorder

import android.app.Application
import android.content.Context
import android.os.Environment
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File

class App : Application() {

    var server: WebServer? = null
        private set

    override fun onCreate() {
        super.onCreate()
        startServer()
    }

    /** Starts the phone web server on the first free port in 8686..8695. */
    fun startServer() {
        if (server?.isAlive == true) return
        for (p in PORTS) {
            try {
                val s = WebServer(this, p)
                s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                server = s
                port = p
                Log.i(TAG, "Web server on port $p")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Port $p busy: ${e.message}")
            }
        }
    }

    companion object {
        const val TAG = "TVRecorder"
        private val PORTS = 8686..8695

        @Volatile
        var port: Int = 8686
            private set

        fun recordingsDir(ctx: Context): File {
            val d = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: File(ctx.filesDir, "recordings")
            if (!d.exists()) d.mkdirs()
            return d
        }
    }
}
