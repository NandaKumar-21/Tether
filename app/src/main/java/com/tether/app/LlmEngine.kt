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

    /**
     * Tried in order until one initialises.
     *
     * gemma-4-E4B-it.litertlm is deliberately NOT here. MediaPipe 0.10.24 rejects the
     * LiteRT-LM container outright (RET_CHECK model_data.cc:424, "Error building tflite
     * model"), and on 0.10.35 the 3.4 GB load killed the process in a restart loop.
     * Re-adding it needs the LiteRT-LM runtime, not a MediaPipe version bump.
     *
     * ctx 4096 is also absent: the 1B .task is built with a 2048 KV cache and 4096
     * fails with INVALID_ARGUMENT.
     */
    /**
     * gemma-4-E4B-it.litertlm is deliberately absent.
     *
     * On tasks-genai 0.10.24 the container is rejected at parse time
     * (RET_CHECK model_data.cc:424). On 0.10.35 LitertLmLoader::Initialize does run,
     * so the format IS supported there, but loading 3.4 GB kills the process in a
     * restart loop even with largeHeap and ctx 1024 - largeHeap raises the Java heap,
     * not the native allocation that actually blows. Getting E4B running needs a
     * smaller quantisation or the standalone LiteRT-LM runtime, not a version bump.
     *
     * ctx 4096 is absent too: the 1B .task has a 2048 KV cache and 4096 fails with
     * INVALID_ARGUMENT.
     */
    private val INIT_LADDER = listOf(
        Config(LlmInference.Backend.GPU, 2048, null, "gemma-3-1b-it-int4"),
        Config(LlmInference.Backend.GPU, 1024, null, "gemma-3-1b-it-int4"),
        Config(LlmInference.Backend.CPU, 1024, null, "gemma-3-1b-it-int4")
    )

    private data class Config(
        val backend: LlmInference.Backend,
        val maxTokens: Int,
        /** null means "use the default .task path". */
        val path: String?,
        val name: String
    )

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

        val defaultPath = modelPath()

        for (cfg in INIT_LADDER) {
            val path = cfg.path ?: defaultPath ?: continue
            val f = File(path)
            if (!f.exists() || !f.canRead()) continue

            val sizeMb = f.length() / (1024 * 1024)
            val started = System.currentTimeMillis()
            ServerState.log("trying ${cfg.name} ($sizeMb MB) ${cfg.backend}/ctx ${cfg.maxTokens}")
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
                ServerState.modelName = cfg.name
                ServerState.backend = "${cfg.backend} / ctx ${cfg.maxTokens}"
                ServerState.log("model READY ${cfg.name} on ${cfg.backend} (ctx ${cfg.maxTokens}) in ${took}ms")
                Log.i(TAG, "init ok: ${cfg.name} ${cfg.backend} ${cfg.maxTokens}")
                return
            } catch (t: Throwable) {
                lastError = "${t.javaClass.simpleName}: ${t.message}"
                ServerState.log("init failed ${cfg.name} ${cfg.backend}/${cfg.maxTokens}: ${t.message}")
                Log.w(TAG, "init failed ${cfg.name} ${cfg.backend}/${cfg.maxTokens}", t)
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
