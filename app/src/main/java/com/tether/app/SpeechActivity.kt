package com.tether.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Offline speech in, on-device answer out, through the same HTTP endpoint
 * the laptop uses.
 */
class SpeechActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "TetherSpeech"
        const val REQ_AUDIO = 300
        const val ACCENT = 0xFF4ADE80.toInt()
        const val AMBER = 0xFFF5C451.toInt()
        const val MUTED = 0xFF7A8590.toInt()
        const val RED = 0xFFF87171.toInt()
    }

    private lateinit var micBtn: Button
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var answer: TextView
    private lateinit var answerScroll: ScrollView

    private var recognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_speech)

        micBtn = findViewById(R.id.micBtn)
        status = findViewById(R.id.speechStatus)
        transcript = findViewById(R.id.transcript)
        answer = findViewById(R.id.answer)
        answerScroll = findViewById(R.id.answerScroll)

        val available = SpeechRecognizer.isRecognitionAvailable(this)
        setPill(if (available) "IDLE" else "NO RECOGNIZER", if (available) MUTED else RED)
        Log.i(TAG, "isRecognitionAvailable=$available")

        micBtn.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                listen()
            } else {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            }
        }
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

    private fun setPill(label: String, color: Int) {
        status.text = label
        status.backgroundTintList = ColorStateList.valueOf(color)
    }

    private fun listen() {
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
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                setPill("LISTENING", ACCENT)
                Log.i(TAG, "onReadyForSpeech")
            }

            override fun onBeginningOfSpeech() = setPill("LISTENING", ACCENT)
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = setPill("PROCESSING", AMBER)

            override fun onError(error: Int) {
                val name = errorName(error)
                setPill("ERROR $error", RED)
                answer.text = name
                micBtn.isEnabled = true
                micBtn.text = "TAP TO SPEAK"
                Log.w(TAG, "onError $error $name")
            }

            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val best = list?.firstOrNull().orEmpty()
                transcript.text = best.ifEmpty { "(nothing heard)" }
                Log.i(TAG, "RESULT: $best")
                if (best.isNotEmpty()) {
                    ask(best)
                } else {
                    setPill("IDLE", MUTED)
                    micBtn.isEnabled = true
                    micBtn.text = "TAP TO SPEAK"
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val best = list?.firstOrNull().orEmpty()
                if (best.isNotEmpty()) {
                    transcript.text = best
                    Log.i(TAG, "PARTIAL: $best")
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        setPill("STARTING", AMBER)
        transcript.text = "listening..."
        answer.text = "—"
        micBtn.isEnabled = false
        micBtn.text = "LISTENING"
        r.startListening(intent)
    }

    /**
     * Sends the transcript to our own /v1/chat/completions over loopback, so the
     * spoken path goes through exactly the same endpoint the laptop uses.
     */
    private fun ask(spoken: String) {
        setPill("THINKING", AMBER)
        answer.text = "—"
        micBtn.isEnabled = false
        micBtn.text = "THINKING"
        val started = System.currentTimeMillis()

        Thread {
            val reply = try {
                val body = JSONObject()
                    .put("model", ServerState.modelName)
                    .put(
                        "messages",
                        JSONArray().put(
                            JSONObject().put("role", "user").put("content", spoken)
                        )
                    ).toString()

                val conn = (URL("http://127.0.0.1:${ServerState.PORT}/v1/chat/completions")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 5000
                    readTimeout = 240000
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(body.toByteArray()) }
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(text)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            } catch (t: Throwable) {
                Log.w(TAG, "ask failed", t)
                "[error] ${t.message}"
            }

            val secs = (System.currentTimeMillis() - started) / 1000.0
            runOnUiThread {
                // Same display-only markdown pass as the status screen.
                answer.text = if (reply.isBlank()) {
                    "the model returned an empty reply"
                } else {
                    MarkdownLite.render(reply)
                }
                answerScroll.post { answerScroll.fullScroll(ScrollView.FOCUS_UP) }
                setPill("%.1fs".format(secs), ACCENT)
                micBtn.isEnabled = true
                micBtn.text = "TAP TO SPEAK"
            }
        }.start()
    }

    private fun errorName(e: Int): String = when (e) {
        SpeechRecognizer.ERROR_NETWORK -> "network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
        SpeechRecognizer.ERROR_AUDIO -> "audio error"
        SpeechRecognizer.ERROR_SERVER -> "server error"
        SpeechRecognizer.ERROR_CLIENT -> "client error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "heard nothing, try again"
        SpeechRecognizer.ERROR_NO_MATCH -> "could not make that out, try again"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy, try again"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone permission missing"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "language not supported"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "offline language pack not installed"
        11 -> "recogniser idle timeout, harmless - tap again"
        else -> "unknown error"
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }
}
