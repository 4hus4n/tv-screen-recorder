package com.ahusan.tvrecorder

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var recordBtn: Button
    private lateinit var qualityBtn: Button
    private lateinit var audioBtn: Button
    private lateinit var timerBtn: Button
    private lateinit var storage: TextView
    private lateinit var error: TextView
    private lateinit var qr: ImageView
    private lateinit var url: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var shownUrl = ""
    private var tickCount = 0
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    private val audioPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        askProjection()
    }

    private val projLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val data = r.data
            if (r.resultCode == Activity.RESULT_OK && data != null) {
                val i = Intent(this, RecorderService::class.java)
                    .setAction(RecorderService.ACTION_START)
                    .putExtra(RecorderService.EXTRA_CODE, r.resultCode)
                    .putExtra(RecorderService.EXTRA_DATA, data)
                ContextCompat.startForegroundService(this, i)
                Toast.makeText(
                    this,
                    "Recording started. Open TV Recorder again or use your phone to stop.",
                    Toast.LENGTH_LONG
                ).show()
                // Step out of the way so the user can go to what they want to record.
                handler.postDelayed({ if (RecState.recording) moveTaskToBack(true) }, 1500)
            } else {
                Toast.makeText(this, "Screen recording permission was not given", Toast.LENGTH_SHORT).show()
            }
        }

    private val autoStopSteps = intArrayOf(0, 15, 30, 60, 120, 180)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        recordBtn = findViewById(R.id.recordBtn)
        qualityBtn = findViewById(R.id.qualityBtn)
        audioBtn = findViewById(R.id.audioBtn)
        timerBtn = findViewById(R.id.timerBtn)
        storage = findViewById(R.id.storage)
        error = findViewById(R.id.error)
        qr = findViewById(R.id.qr)
        url = findViewById(R.id.url)

        (application as App).startServer()
        ServerService.start(this) // keep phone access on after the app is closed

        recordBtn.setOnClickListener {
            if (RecState.recording) RecorderService.requestStop() else startFlow()
        }
        qualityBtn.setOnClickListener {
            Prefs.setQuality(this, if (Prefs.quality(this) >= 1080) 720 else 1080)
            refresh()
        }
        audioBtn.setOnClickListener {
            Prefs.setAudio(this, !Prefs.audio(this))
            refresh()
        }
        timerBtn.setOnClickListener {
            val cur = autoStopSteps.indexOf(Prefs.autoStop(this)).coerceAtLeast(0)
            Prefs.setAutoStop(this, autoStopSteps[(cur + 1) % autoStopSteps.size])
            refresh()
        }
        recordBtn.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        tickCount = 0
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun startFlow() {
        if (RecState.saving) {
            Toast.makeText(this, "Still saving the last recording…", Toast.LENGTH_SHORT).show()
            return
        }
        val needMic = Prefs.audio(this) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        if (needMic) audioPerm.launch(Manifest.permission.RECORD_AUDIO) else askProjection()
    }

    private fun askProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        try {
            projLauncher.launch(mpm.createScreenCaptureIntent())
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "This TV doesn't allow screen recording apps", Toast.LENGTH_LONG).show()
        }
    }

    private fun refresh() {
        // Status + main button
        when {
            RecState.recording -> {
                val left = RecState.remainingSec()
                val extra = if (left >= 0) "  ·  stops in ${Net.clock(left)}" else ""
                val snd = if (RecState.withAudio) "" else "  ·  no sound"
                status.text = "● REC  ${Net.clock(RecState.elapsedSec())}$extra$snd"
                status.setTextColor(0xFFFF5252.toInt())
                recordBtn.text = "■ Stop recording"
            }
            RecState.saving -> {
                status.text = "Saving recording…"
                status.setTextColor(0xFFFFD54F.toInt())
                recordBtn.text = "● Start recording"
            }
            else -> {
                status.text = "Ready"
                status.setTextColor(0xFF9AA3B2.toInt())
                recordBtn.text = "● Start recording"
            }
        }

        // Options
        qualityBtn.text = "Quality:  ${Prefs.quality(this)}p"
        val soundLabel = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) "not supported on this TV"
                         else if (Prefs.audio(this)) "On" else "Off"
        audioBtn.text = "TV sound:  $soundLabel"
        val m = Prefs.autoStop(this)
        timerBtn.text = "Auto-stop:  " + if (m == 0) "Off" else if (m < 60) "$m min" else "${m / 60} h"

        // Error
        val err = RecState.lastError
        if (err != null && !RecState.recording) {
            error.text = "Last error: $err"
            error.visibility = View.VISIBLE
        } else {
            error.visibility = View.GONE
        }

        // Storage summary (every 5 s is plenty)
        if (tickCount++ % 5 == 0) {
            val files = App.recordingsDir(this).listFiles { f -> f.name.endsWith(".mp4") } ?: emptyArray()
            val total = files.sumOf { it.length() }
            val free = App.recordingsDir(this).usableSpace
            storage.text = "${files.size} recording(s) · ${Net.humanSize(total)} used · ${Net.humanSize(free)} free"
        }

        // Phone link + QR (rebuild only when IP/port changes)
        val ip = Net.localIp()
        val link = if (ip != null) "http://$ip:${App.port}/?k=${Prefs.key(this)}" else ""
        if (link != shownUrl) {
            shownUrl = link
            if (link.isEmpty()) {
                qr.setImageDrawable(null)
                url.text = "Connect the TV to Wi-Fi"
            } else {
                qr.setImageBitmap(Net.qr(link, 480))
                url.text = "http://$ip:${App.port}\nCode: ${Prefs.key(this)}"
            }
        }
    }
}
