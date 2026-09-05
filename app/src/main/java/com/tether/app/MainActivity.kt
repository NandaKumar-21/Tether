package com.tether.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private companion object {
        const val ACCENT = 0xFF4ADE80.toInt()
        const val MUTED = 0xFF7A8590.toInt()
        const val AMBER = 0xFFF5C451.toInt()
        /** How long after a reply the pill keeps reading SERVING. */
        const val SERVING_WINDOW_MS = 2500L
    }

    private lateinit var statusPill: TextView
    private lateinit var backendValue: TextView
    private lateinit var tpsValue: TextView
    private lateinit var requestsValue: TextView
    private lateinit var answerText: TextView
    private lateinit var answerScroll: ScrollView

    private val ui = Handler(Looper.getMainLooper())
    private var shownAnswerAt = -1L

    private val refresh = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusPill = findViewById(R.id.statusPill)
        backendValue = findViewById(R.id.backendValue)
        tpsValue = findViewById(R.id.tpsValue)
        requestsValue = findViewById(R.id.requestsValue)
        answerText = findViewById(R.id.answerText)
        answerScroll = findViewById(R.id.answerScroll)

        findViewById<Button>(R.id.studioBtn).setOnClickListener {
            startActivity(Intent(this, StudioActivity::class.java))
        }
        findViewById<Button>(R.id.scanBtn).setOnClickListener {
            startActivity(Intent(this, OcrActivity::class.java))
        }
        findViewById<Button>(R.id.micBtn).setOnClickListener {
            startActivity(Intent(this, SpeechActivity::class.java))
        }

        // Dashboard row. STUDIO is the button above; not duplicated here.
        findViewById<Button>(R.id.chatDashBtn).setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java))
        }
        findViewById<Button>(R.id.codeDashBtn).setOnClickListener {
            startActivity(Intent(this, WorkspaceActivity::class.java))
        }
        findViewById<Button>(R.id.imageDashBtn).setOnClickListener {
            startActivity(Intent(this, ImageActivity::class.java))
        }
        findViewById<Button>(R.id.filesDashBtn).setOnClickListener {
            startActivity(Intent(this, FileBrowserActivity::class.java))
        }

        requestNotificationPermission()

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

        val serving = s.lastAnswerAtMs > 0 &&
            System.currentTimeMillis() - s.lastAnswerAtMs < SERVING_WINDOW_MS

        when {
            !s.modelLoaded -> setPill("LOADING", AMBER)
            serving -> setPill("SERVING", ACCENT)
            else -> setPill("READY", ACCENT)
        }

        // "GPU / ctx 2048" -> "GPU". The context size is not what a judge reads from a metre.
        backendValue.text = s.backend.substringBefore('/').trim().ifEmpty { "—" }
        tpsValue.text = "%.1f".format(s.lastTokensPerSec)
        requestsValue.text = s.requestsServed.get().toString()

        // Only re-render the answer when a new one arrives, so spans are not rebuilt
        // 2x a second and the scroll position is left alone while reading.
        if (s.lastAnswerAtMs != shownAnswerAt) {
            shownAnswerAt = s.lastAnswerAtMs
            val body = s.lastAnswer
            if (body.isBlank()) {
                answerText.setTextColor(0xFF6B7680.toInt())
                answerText.text = "empty reply"
            } else {
                answerText.setTextColor(0xFFF2F5F7.toInt())
                answerText.text = MarkdownLite.render(body)
                answerScroll.post { answerScroll.fullScroll(ScrollView.FOCUS_UP) }
            }
        }

        if (s.lastAnswerAtMs == 0L) {
            answerText.setTextColor(0xFF6B7680.toInt())
            answerText.text = if (s.modelLoaded) {
                "ready on ${deviceName()}"
            } else {
                "loading ${s.modelName}"
            }
        }
    }

    /**
     * Android reports this phone as "vivo I2501" because iQOO is a vivo sub-brand
     * and I2501 is the internal code. Show the name people actually recognise.
     */
    private fun deviceName(): String = when (Build.MODEL) {
        "I2501" -> "iQOO 15"
        else -> "${Build.MANUFACTURER} ${Build.MODEL}"
    }

    private fun setPill(label: String, color: Int) {
        if (statusPill.text != label) statusPill.text = label
        statusPill.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
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
