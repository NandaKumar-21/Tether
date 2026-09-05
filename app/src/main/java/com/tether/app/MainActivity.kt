package com.tether.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var scanBtn: Button

    private val ui = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        startBtn = findViewById(R.id.startBtn)
        stopBtn = findViewById(R.id.stopBtn)
        scanBtn = findViewById(R.id.scanBtn)

        requestNotificationPermission()

        startBtn.setOnClickListener {
            ContextCompat.startForegroundService(this, Intent(this, TetherService::class.java))
            ui.postDelayed({ render() }, 400)
        }

        stopBtn.setOnClickListener {
            startService(
                Intent(this, TetherService::class.java).setAction(TetherService.ACTION_STOP)
            )
            ui.postDelayed({ render() }, 400)
        }

        scanBtn.setOnClickListener { startActivity(Intent(this, OcrActivity::class.java)) }

        // The runtime should just be on. Removes a step from the demo.
        if (!ServerState.running) {
            ContextCompat.startForegroundService(this, Intent(this, TetherService::class.java))
        }

        render()
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresh)
    }

    override fun onPause() {
        ui.removeCallbacks(refresh)
        super.onPause()
    }

    private fun render() {
        val s = ServerState
        statusText.text = buildString {
            // Labels kept short so no line wraps at the larger demo font size.
            appendLine(if (s.running) "● LISTENING" else "○ STOPPED")
            appendLine()
            appendLine(":${s.PORT}/v1/chat/completions")
            appendLine("lan     ${s.ipAddress}")
            appendLine("model   ${s.modelName}")
            appendLine("loaded  ${s.modelLoaded}")
            appendLine("backend ${s.backend}")
            appendLine("reqs    ${s.requestsServed.get()}")
            appendLine("last    ${s.lastLatencyMs} ms")
            appendLine("speed   ${"%.1f".format(s.lastTokensPerSec)} tok/s")
            val ocr = s.ocrContext
            appendLine("ocr     " + if (ocr == null) "none" else "${ocr.length} chars")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL} / api ${Build.VERSION.SDK_INT}")
        }

        startBtn.isEnabled = !s.running
        stopBtn.isEnabled = s.running

        val lines = s.logSnapshot()
        logText.text = if (lines.isEmpty()) "no requests yet" else lines.takeLast(60).joinToString("\n")
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }
}
