package com.tether.app

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Owns the MediaPipe LLM. Created once at service start, never per request.
 */
object LlmEngine {

    private const val TAG = "TetherLLM"

    /** First readable path wins. adb push target is the first entry. */
    private val CANDIDATE_PATHS = listOf(
        "/data/local/tmp/llm/gemma3-1b-it-int4.task",
        "/sdcard/Android/data/com.tether.app/files/gemma3-1b-it-int4.task"
    )

    /** Tried in order until one initialises. Covers GPU-unavailable and KV-cache-too-small. */
    private val INIT_LADDER = listOf(
        Config(LlmInference.Backend.GPU, 2048),
        Config(LlmInference.Backend.GPU, 1024),
        Config(LlmInference.Backend.CPU, 1024)
    )

    private data class Config(val backend: LlmInference.Backend, val maxTokens: Int)

    private var llm: LlmInference? = null

    /** Held so a wedged engine can be rebuilt without a request thread supplying context. */
    @Volatile private var appContext: Context? = null

    /**
     * Generation is serialised but deliberately NOT pinned to the init thread.
     * Creating a session on the same thread that built the graph makes
     * generateResponse return an empty string immediately.
     */
    private val lock = ReentrantLock(true)

    @Volatile var maxTokens: Int = 0
        private set
    @Volatile var lastError: String? = null
        private set

    val isReady: Boolean get() = llm != null

    fun modelPath(): String? =
        CANDIDATE_PATHS.firstOrNull { File(it).let { f -> f.exists() && f.canRead() } }

    /** Blocking, and must run on its own background thread (not a request thread). */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (llm != null) return

        val path = modelPath()
        if (path == null) {
            lastError = "model file not found or unreadable. adb push to ${CANDIDATE_PATHS[0]}"
            ServerState.log("MODEL MISSING: ${CANDIDATE_PATHS[0]}")
            return
        }

        val sizeMb = File(path).length() / (1024 * 1024)
        ServerState.log("loading model ($sizeMb MB) from $path")

        for (cfg in INIT_LADDER) {
            val started = System.currentTimeMillis()
            try {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(path)
                    .setMaxTokens(cfg.maxTokens)
                    .setMaxTopK(64)
                    .setPreferredBackend(cfg.backend)
                    .build()

                llm = LlmInference.createFromOptions(context, options)
                maxTokens = cfg.maxTokens
                lastError = null

                val took = System.currentTimeMillis() - started
                ServerState.modelLoaded = true
                ServerState.backend = "${cfg.backend} / ctx ${cfg.maxTokens}"
                ServerState.log("model READY on ${cfg.backend} (ctx ${cfg.maxTokens}) in ${took}ms")
                Log.i(TAG, "init ok: ${cfg.backend} ${cfg.maxTokens}")
                return
            } catch (t: Throwable) {
                lastError = "${t.javaClass.simpleName}: ${t.message}"
                ServerState.log("init failed on ${cfg.backend}/${cfg.maxTokens}: ${t.message}")
                Log.w(TAG, "init failed ${cfg.backend}/${cfg.maxTokens}", t)
            }
        }

        ServerState.modelLoaded = false
        ServerState.backend = "init failed"
        ServerState.log("ALL BACKENDS FAILED: $lastError")
    }

    /**
     * A fresh session per call. The OpenAI contract is stateless - the client sends
     * the whole history every time, so a reused session would double it.
     *
     * The seed must be non-negative. System.nanoTime().toInt() overflows negative about
     * half the time and MediaPipe rejects that with
     * INVALID_ARGUMENT: CalculatorGraph::Run() failed, which its JNI layer escalates
     * into a process abort under CheckJNI instead of a catchable exception.
     */
    fun generate(prompt: String): String = lock.withLock {
        val started = System.nanoTime()

        val engine = llm ?: throw IllegalStateException(
            "model not loaded" + (lastError?.let { " ($it)" } ?: "")
        )

        // No LlmInferenceSession. Closing a session tears down state the parent
        // LlmInference still needs, leaving the engine dead after one generation.
        val text = engine.generateResponse(prompt).orEmpty().trim()
        Log.i(TAG, "generateResponse -> ${text.length}ch")

        val elapsedSec = (System.nanoTime() - started) / 1_000_000_000.0
        val outTokens = tokenCountUnsafe(text)
        ServerState.lastTokensPerSec = if (elapsedSec > 0) outTokens / elapsedSec else 0.0
        Log.i(TAG, "generated ${text.length}ch in ${"%.2f".format(elapsedSec)}s")

        text
    }

    private fun runOnce(prompt: String, tag: String): String {
        val engine = llm ?: throw IllegalStateException(
            "model not loaded" + (lastError?.let { " ($it)" } ?: "")
        )
        val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(40)
            .setTopP(0.9f)
            .setTemperature(0.7f)
            .setRandomSeed((System.nanoTime() and 0x7FFFFFFFL).toInt())
            .build()

        return try {
            val t = LlmInferenceSession.createFromOptions(engine, sessionOptions).use { session ->
                session.addQueryChunk(prompt)
                session.generateResponse()
            }.orEmpty().trim()
            Log.i(TAG, "$tag -> ${t.length}ch")
            t
        } catch (e: Throwable) {
            Log.w(TAG, "$tag failed", e)
            ServerState.log("$tag failed: ${e.message}")
            ""
        }
    }

    private fun reinitialize(context: Context) {
        try {
            llm?.close()
        } catch (_: Throwable) {
        }
        llm = null
        ServerState.modelLoaded = false
        initialize(context)
    }

    private fun tokenCountUnsafe(s: String): Int = try {
        llm?.sizeInTokens(s) ?: (s.length / 4)
    } catch (_: Throwable) {
        s.length / 4
    }

    /**
     * Must hold the same lock as generate(). sizeInTokens touches the same native
     * engine, and calling it from a request thread while a generation is running
     * crashes the process.
     */
    fun tokenCount(s: String): Int = try {
        lock.withLock { llm?.sizeInTokens(s) ?: (s.length / 4) }
    } catch (_: Throwable) {
        s.length / 4
    }

    fun shutdown() = lock.withLock {
        try {
            llm?.close()
        } catch (_: Throwable) {
        }
        llm = null
        ServerState.modelLoaded = false
        ServerState.backend = "-"
    }
}
