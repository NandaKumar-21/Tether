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
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Snapchat-style ephemeral chat. SQLite backs the RecyclerView only so scrolling and
 * context-building are simple; the database file is deleted in onDestroy, so nothing
 * survives the activity closing.
 */
class ChatActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "TetherChat"
        const val REQ_AUDIO = 500
        /** Turns of history sent as context, not counting the new message. */
        const val CONTEXT_TURNS = 3
        /** Defensive char cap so the prompt cannot approach ctx 2048 regardless of
         *  how verbose a turn was. ~4 chars/token is a safe average for this model. */
        const val MAX_PROMPT_CHARS = 6000
    }

    private lateinit var db: ChatDb
    private lateinit var recycler: RecyclerView
    private lateinit var input: EditText
    private lateinit var status: TextView
    private var recognizer: SpeechRecognizer? = null
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        db = ChatDb(this)
        recycler = findViewById(R.id.chatList)
        input = findViewById(R.id.chatInput)
        status = findViewById(R.id.chatStatus)

        recycler.layoutManager = LinearLayoutManager(this)
        refreshList()

        findViewById<Button>(R.id.chatSendBtn).setOnClickListener { send() }
        findViewById<Button>(R.id.chatMicBtn).setOnClickListener { listen() }
        findViewById<Button>(R.id.clearBtn).setOnClickListener {
            db.clear()
            refreshList()
            status.text = "cleared"
        }
    }

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        if (!ServerState.modelLoaded) {
            status.text = "model not loaded yet"
            return
        }

        db.insert("user", text)
        input.setText("")
        refreshList()
        status.text = "thinking..."

        val prompt = buildPrompt(text)
        Thread({
            val reply = try {
                LlmEngine.generate(prompt)
            } catch (t: Throwable) {
                "[error] ${t.message}"
            }
            ui.post {
                db.insert("assistant", reply.ifBlank { "(empty reply)" })
                refreshList()
                status.text = ""
            }
        }, "chat-generate").start()
    }

    /**
     * Gemma 3 turn format, same shape TetherServer uses. Last CONTEXT_TURNS
     * exchanges only, then a defensive char cap so the prompt cannot creep
     * toward ctx 2048 even if a turn was unusually long.
     */
    private fun buildPrompt(newMessage: String): String {
        val history = db.lastTurns(CONTEXT_TURNS)
        val sb = StringBuilder()
        for (m in history) {
            val tag = if (m.role == "user") "user" else "model"
            sb.append("<start_of_turn>").append(tag).append('\n')
                .append(m.content).append("<end_of_turn>\n")
        }
        sb.append("<start_of_turn>user\n").append(newMessage).append("<end_of_turn>\n")
        sb.append("<start_of_turn>model\n")

        var prompt = sb.toString()
        if (prompt.length > MAX_PROMPT_CHARS) {
            // Drop oldest history first; the newest turn and the new message are kept whole.
            prompt = "<start_of_turn>user\n" + newMessage + "<end_of_turn>\n<start_of_turn>model\n"
        }
        return prompt
    }

    private fun refreshList() {
        val messages = db.all()
        recycler.adapter = ChatAdapter(messages)
        recycler.scrollToPosition(maxOf(0, messages.size - 1))
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
            override fun onReadyForSpeech(params: Bundle?) { status.text = "listening..." }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { status.text = "mic error $error" }
            override fun onResults(results: Bundle?) {
                val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (best.isNotEmpty()) {
                    val existing = input.text.toString()
                    input.setText(if (existing.isBlank()) best else "$existing $best")
                    input.setSelection(input.text.length)
                }
                status.text = ""
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
        recognizer?.destroy()
        recognizer = null
        db.close()
        // Ephemeral by design: nothing survives the activity closing.
        deleteDatabase(ChatDb.DB_NAME)
        super.onDestroy()
    }
}
