package com.tether.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * M5 spike: does SpeechRecognizer transcribe with the network off?
 */
class SpeechActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "TetherSpeech"
        const val REQ_AUDIO = 300
    }

    private lateinit var micBtn: Button
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var answer: TextView

    private var recognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_speech)

        micBtn = findViewById(R.id.micBtn)
        status = findViewById(R.id.speechStatus)
        transcript = findViewById(R.id.transcript)
        answer = findViewById(R.id.answer)

        val available = SpeechRecognizer.isRecognitionAvailable(this)
        status.text = "recognition available: $available"
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
                status.text = "listening - speak now"
                Log.i(TAG, "onReadyForSpeech")
            }

            override fun onBeginningOfSpeech() {
                status.text = "hearing you..."
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                status.text = "processing..."
            }

            override fun onError(error: Int) {
                val name = errorName(error)
                status.text = "ERROR $error $name"
                Log.w(TAG, "onError $error $name")
            }

            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val best = list?.firstOrNull().orEmpty()
                transcript.text = best.ifEmpty { "(empty)" }
                Log.i(TAG, "RESULT: $best")
                if (best.isNotEmpty()) ask(best) else status.text = "nothing heard"
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

        status.text = "starting..."
        transcript.text = "-"
        r.startListening(intent)
    }

    /**
     * Sends the transcript to our own /v1/chat/completions over loopback, so the
     * spoken path goes through exactly the same endpoint the laptop uses.
     */
    private fun ask(spoken: String) {
        status.text = "thinking on-device..."
        answer.text = "-"
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

            val ms = System.currentTimeMillis() - started
            runOnUiThread {
                answer.text = reply.ifBlank { "(empty reply)" }
                status.text = "answered in ${ms}ms - offline"
            }
        }.start()
    }

    private fun errorName(e: Int): String = when (e) {
        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
        SpeechRecognizer.ERROR_SERVER -> "SERVER"
        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "LANGUAGE_NOT_SUPPORTED"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "LANGUAGE_UNAVAILABLE"
        11 -> "SERVER_DISCONNECTED (idle timeout, harmless)"
        else -> "UNKNOWN"
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }
}
