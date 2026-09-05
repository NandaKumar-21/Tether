package com.tether.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Tether Studio: prompt -> code -> run, entirely on the device.
 * Generation calls LlmEngine directly; port 8080 is left alone for external clients.
 */
class StudioActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "TetherStudio"
        const val REQ_AUDIO = 400
        const val ACCENT = 0xFF4ADE80.toInt()
        const val AMBER = 0xFFF5C451.toInt()
        const val RED = 0xFFF87171.toInt()
        const val MUTED = 0xFF6B7680.toInt()
    }

    private lateinit var stateView: TextView
    private lateinit var timerView: TextView
    private lateinit var promptInput: EditText
    private lateinit var promptExtras: TextView
    private lateinit var codeEditor: EditText
    private lateinit var codeLines: TextView
    private lateinit var previewWeb: WebView
    private lateinit var logText: TextView
    private lateinit var paneLog: ScrollView

    private lateinit var panes: List<View>
    private lateinit var tabs: List<Button>

    private val ui = Handler(Looper.getMainLooper())
    private val consoleErrors = ArrayList<String>()
    private val logLines = ArrayList<String>()

    private var cameraContext: String? = null
    private var recognizer: SpeechRecognizer? = null

    /** One entry per generated file, including the repairs. */
    class Version(val index: Int, val html: String) {
        var errors: List<String> = emptyList()
        var status: String = "?"
    }

    private val versions = ArrayList<Version>()
    private var repairCount = 0
    private var maxRepairs = 2

    private var phaseStart = 0L
    private var timerRunning = false
    private val timerTick = object : Runnable {
        override fun run() {
            if (!timerRunning) return
            val s = (System.currentTimeMillis() - phaseStart) / 1000.0
            timerView.text = "%.1fs".format(s)
            ui.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_studio)

        stateView = findViewById(R.id.studioState)
        timerView = findViewById(R.id.studioTimer)
        promptInput = findViewById(R.id.promptInput)
        promptExtras = findViewById(R.id.promptExtras)
        codeEditor = findViewById(R.id.codeEditor)
        codeLines = findViewById(R.id.codeLines)
        previewWeb = findViewById(R.id.previewWeb)
        logText = findViewById(R.id.logText)
        paneLog = findViewById(R.id.paneLog)

        panes = listOf(
            findViewById(R.id.panePrompt),
            findViewById(R.id.paneCode),
            previewWeb,
            paneLog
        )
        tabs = listOf(
            findViewById(R.id.tabPrompt),
            findViewById(R.id.tabCode),
            findViewById(R.id.tabPreview),
            findViewById(R.id.tabLog)
        )
        tabs.forEachIndexed { i, b -> b.setOnClickListener { showPane(i) } }
        showPane(0)

        findViewById<Button>(R.id.buildBtn).setOnClickListener { build() }
        findViewById<Button>(R.id.studioMicBtn).setOnClickListener { listen() }
        findViewById<Button>(R.id.studioCamBtn).setOnClickListener {
            startActivity(Intent(this, OcrActivity::class.java))
        }

        setupWebView()

        codeEditor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = updateLineNumbers()
        })

        setState("IDLE", MUTED)
    }

    override fun onResume() {
        super.onResume()
        // OcrActivity parks its text here; take it as prompt context.
        ServerState.ocrContext?.let {
            cameraContext = it
            ServerState.ocrContext = null
            promptExtras.text = "camera: ${it.length} chars attached"
        }
    }

    // ---------------------------------------------------------------- panes

    private fun showPane(index: Int) {
        panes.forEachIndexed { i, v -> v.visibility = if (i == index) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { i, b -> b.setTextColor(if (i == index) ACCENT else MUTED) }
    }

    // ------------------------------------------------------------- webview

    private fun setupWebView() {
        previewWeb.settings.javaScriptEnabled = true
        previewWeb.settings.domStorageEnabled = true
        @Suppress("DEPRECATION")
        previewWeb.settings.allowFileAccess = true
        previewWeb.webChromeClient = object : WebChromeClient() {
            // A generated page that calls alert/prompt/confirm would sit behind a modal
            // and block the preview, so swallow them and record them as a fault instead.
            override fun onJsAlert(
                view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?
            ): Boolean {
                addLog("blocked alert(): $message")
                consoleErrors.add("page called alert() - use a DOM element instead")
                result?.cancel()
                return true
            }

            override fun onJsConfirm(
                view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?
            ): Boolean {
                addLog("blocked confirm(): $message")
                consoleErrors.add("page called confirm() - use a DOM element instead")
                result?.cancel()
                return true
            }

            override fun onJsPrompt(
                view: WebView?, url: String?, message: String?, defaultValue: String?,
                result: android.webkit.JsPromptResult?
            ): Boolean {
                addLog("blocked prompt(): $message")
                consoleErrors.add("page called prompt() - use an input element instead")
                result?.cancel()
                return true
            }

            override fun onConsoleMessage(cm: ConsoleMessage): Boolean {
                val line = "${cm.messageLevel()} ${cm.message()} (line ${cm.lineNumber()})"
                addLog(line)
                if (cm.messageLevel() == ConsoleMessage.MessageLevel.ERROR &&
                    Studio.isRealError(cm.message())
                ) {
                    consoleErrors.add("${cm.message()} (line ${cm.lineNumber()})")
                }
                return true
            }
        }
    }

    private fun renderHtml(html: String, version: Int) {
        consoleErrors.clear()
        val file = File(filesDir, "studio_v$version.html")
        file.writeText(Studio.injectErrorHook(html))
        previewWeb.loadUrl("file://${file.absolutePath}")
    }

    // -------------------------------------------------------------- build

    private fun build() {
        val request = promptInput.text.toString().trim()
        if (request.isEmpty()) {
            setState("TYPE SOMETHING FIRST", AMBER)
            return
        }
        if (!ServerState.modelLoaded) {
            setState("MODEL NOT LOADED", RED)
            return
        }

        logLines.clear()
        versions.clear()
        repairCount = 0
        addLog("prompt: $request")
        setState("GENERATING", AMBER)
        startTimer()
        showPane(1)

        generateAsync(Studio.buildPrompt(request, cameraContext)) { html ->
            if (html.isBlank()) {
                stopTimer()
                setState("MODEL RETURNED NOTHING", RED)
                addLog("empty generation")
            } else {
                addLog("v1 generated, ${html.length} chars")
                runVersion(html)
            }
        }
    }

    private fun generateAsync(prompt: String, onDone: (String) -> Unit) {
        Thread({
            val raw = try {
                LlmEngine.generate(prompt)
            } catch (t: Throwable) {
                Log.w(TAG, "generate failed", t)
                ""
            }
            val html = Studio.cleanHtml(raw)
            ui.post { onDone(html) }
        }, "studio-generate").start()
    }

    /** Write a new version, show it, then judge it after the page has had time to fail. */
    private fun runVersion(html: String) {
        val v = Version(versions.size + 1, html)
        versions.add(v)

        codeEditor.setText(html)
        updateLineNumbers()
        renderVersions()

        setState("RUNNING", AMBER)
        startTimer()
        showPane(2)
        renderHtml(html, v.index)
        ui.postDelayed({ evaluate(v) }, 2000)
    }

    /**
     * The repair loop. Capped at [maxRepairs] so it can never spin: after that it
     * stops and shows whatever is left rather than pretending it succeeded.
     */
    private fun evaluate(v: Version) {
        stopTimer()
        val errors = consoleErrors.toList()
        v.errors = errors
        v.status = if (errors.isEmpty()) "CLEAN" else "BROKEN"
        renderVersions()

        if (errors.isEmpty()) {
            setState("CLEAN", ACCENT)
            addLog("v${v.index} clean")
            return
        }

        val n = errors.size
        setState("FOUND $n ERROR${if (n == 1) "" else "S"}", RED)
        errors.forEach { addLog("  ! $it") }

        if (repairCount >= maxRepairs) {
            ui.postDelayed({
                setState("$n ERROR${if (n == 1) "" else "S"} REMAIN", RED)
                addLog("repair cap reached, stopping")
            }, 1200)
            return
        }

        // Hold the error count on screen long enough to read before repairing.
        ui.postDelayed({
            repairCount++
            setState("REPAIRING ($repairCount/$maxRepairs)", AMBER)
            startTimer()
            addLog("repair $repairCount: sending code + errors back to the model")
            generateAsync(Studio.buildRepairPrompt(v.html, errors)) { fixed ->
                if (fixed.isBlank()) {
                    stopTimer()
                    setState("REPAIR RETURNED NOTHING", RED)
                } else {
                    addLog("v${versions.size + 1} generated, ${fixed.length} chars")
                    runVersion(fixed)
                }
            }
        }, 1200)
    }

    /** Gate 3 fills this in. */
    private fun renderVersions() {}

    // --------------------------------------------------------------- state

    private fun setState(text: String, color: Int) {
        stateView.text = text
        stateView.setTextColor(color)
    }

    private fun startTimer() {
        phaseStart = System.currentTimeMillis()
        timerRunning = true
        ui.post(timerTick)
    }

    private fun stopTimer() {
        timerRunning = false
    }

    private fun addLog(line: String) {
        logLines.add(line)
        logText.text = logLines.joinToString("\n")
        paneLog.post { paneLog.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun updateLineNumbers() {
        val count = codeEditor.text.toString().count { it == '\n' } + 1
        codeLines.text = (1..count).joinToString("\n")
    }

    // ----------------------------------------------------------------- mic

    private fun listen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = setState("LISTENING", ACCENT)
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = setState("IDLE", MUTED)
            override fun onError(error: Int) = setState("MIC ERROR $error", RED)
            override fun onResults(results: Bundle?) {
                val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (best.isNotEmpty()) {
                    val existing = promptInput.text.toString()
                    promptInput.setText(if (existing.isBlank()) best else "$existing $best")
                    promptInput.setSelection(promptInput.text.length)
                }
                setState("IDLE", MUTED)
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            listen()
        }
    }

    override fun onDestroy() {
        timerRunning = false
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }
}
