package com.ahusan.tvrecorder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecorderService : Service() {

    private var projection: MediaProjection? = null
    private var encoder: ScreenEncoder? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stopRecording() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Screen recording", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        // Must be in the foreground (type mediaProjection) before getMediaProjection().
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        )
        if (RecState.recording) return

        val resultCode = intent.getIntExtra(EXTRA_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        if (data == null) { fail("Screen capture permission missing"); return }

        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val proj = mpm.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("Screen capture not allowed")
            projection = proj
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { handler.post { stopRecording() } }
            }, handler)

            val audio = Prefs.audio(this) && audioAllowed()
            var quality = Prefs.quality(this)
            var enc: ScreenEncoder? = null
            var file: File? = null
            // Try chosen quality, fall back to 720p if the TV's encoder refuses it.
            while (enc == null) {
                val f = newFile()
                file = f
                val (w, h, dpi) = captureSize(quality)
                val bitrate = if (quality >= 1080) 10_000_000 else 5_000_000
                val candidate = ScreenEncoder(proj, w, h, dpi, bitrate, audio, f) { msg ->
                    handler.post { RecState.lastError = msg; stopRecording() }
                }
                try {
                    candidate.start()
                    enc = candidate
                } catch (e: Exception) {
                    Log.w(App.TAG, "Encoder ${w}x$h failed: ${e.message}")
                    f.delete()
                    if (quality <= 720) throw e
                    quality = 720
                }
            }
            encoder = enc

            RecState.lastError = null
            RecState.fileName = file!!.name
            RecState.withAudio = enc!!.hasAudio
            RecState.startedAt = SystemClock.elapsedRealtime()
            RecState.saving = false
            RecState.recording = true

            val mins = Prefs.autoStop(this)
            if (mins > 0) {
                RecState.stopAt = RecState.startedAt + mins * 60_000L
                handler.postDelayed(autoStop, mins * 60_000L)
            } else {
                RecState.stopAt = 0
            }
        } catch (e: Exception) {
            Log.e(App.TAG, "Start failed", e)
            fail(e.message ?: e.toString())
        }
    }

    private fun audioAllowed(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun newFile(): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(App.recordingsDir(this), "REC_$stamp.mp4")
    }

    private fun captureSize(targetH: Int): Triple<Int, Int, Int> {
        val dm = DisplayMetrics()
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        display.getRealMetrics(dm)
        var w = maxOf(dm.widthPixels, dm.heightPixels)
        var h = minOf(dm.widthPixels, dm.heightPixels)
        if (h > targetH) {
            w = (w.toLong() * targetH / h).toInt()
            h = targetH
        }
        w = w and 1.inv()
        h = h and 1.inv()
        return Triple(w, h, dm.densityDpi)
    }

    /** Safe to call more than once. */
    fun stopRecording() {
        handler.removeCallbacks(autoStop)
        val enc = encoder; encoder = null
        val proj = projection; projection = null
        if (enc == null && proj == null) {
            RecState.recording = false
            finishService()
            return
        }
        RecState.recording = false
        RecState.saving = true
        Thread {
            try { enc?.stop() } catch (e: Exception) { Log.e(App.TAG, "Stop error", e) }
            try { proj?.stop() } catch (_: Exception) {}
            RecState.saving = false
            handler.post { finishService() }
        }.start()
    }

    private fun finishService() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(msg: String) {
        RecState.lastError = msg
        RecState.recording = false
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        finishService()
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_rec)
            .setContentTitle("Recording screen")
            .setContentText("Open TV Recorder or use your phone to stop")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    override fun onDestroy() {
        if (encoder != null) {
            val enc = encoder; encoder = null
            RecState.recording = false
            Thread { try { enc?.stop() } catch (_: Exception) {} }.start()
        }
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.ahusan.tvrecorder.START"
        const val ACTION_STOP = "com.ahusan.tvrecorder.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "rec"
        private const val NOTIF_ID = 42

        @Volatile
        var instance: RecorderService? = null
            private set

        /** Stop from anywhere (TV screen or phone page). */
        fun requestStop() {
            Handler(Looper.getMainLooper()).post { instance?.stopRecording() }
        }
    }
}
