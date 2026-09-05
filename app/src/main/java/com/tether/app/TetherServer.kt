package com.tether.app

import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI-compatible HTTP surface.
 *
 * [generate] takes the flattened prompt and returns the assistant reply.
 * M1 wires a hardcoded lambda here; M2 swaps in MediaPipe without touching this file.
 */
class TetherServer(
    port: Int,
    private val generate: (String) -> String
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/').ifEmpty { "/" }

        return try {
            when {
                session.method == Method.OPTIONS -> cors(text(Response.Status.OK, ""))

                uri == "/v1/chat/completions" && session.method == Method.POST ->
                    cors(handleChat(session))

                uri == "/v1/models" -> cors(handleModels())

                uri == "/health" || uri == "/" -> cors(
                    json(
                        Response.Status.OK,
                        JSONObject()
                            .put("status", "ok")
                            .put("model", ServerState.modelName)
                            .put("model_loaded", ServerState.modelLoaded)
                            .put("backend", ServerState.backend)
                            .put("requests_served", ServerState.requestsServed.get())
                            .put("tokens_per_sec", ServerState.lastTokensPerSec)
                            .put("model_path", LlmEngine.modelPath() ?: "not found")
                    )
                )

                else -> cors(
                    json(
                        Response.Status.NOT_FOUND,
                        errorObj("no route for ${session.method} $uri", "invalid_request_error")
                    )
                )
            }
        } catch (t: Throwable) {
            ServerState.log("ERROR ${t.javaClass.simpleName}: ${t.message}")
            cors(
                json(
                    Response.Status.INTERNAL_ERROR,
                    errorObj(t.message ?: t.javaClass.simpleName, "server_error")
                )
            )
        }
    }

    private fun handleModels(): Response {
        val model = JSONObject()
            .put("id", ServerState.modelName)
            .put("object", "model")
            .put("created", System.currentTimeMillis() / 1000)
            .put("owned_by", "tether-local")

        return json(
            Response.Status.OK,
            JSONObject()
                .put("object", "list")
                .put("data", JSONArray().put(model))
        )
    }

    private fun handleChat(session: IHTTPSession): Response {
        val body = readBody(session)
        val root = JSONObject(if (body.isBlank()) "{}" else body)
        val messages = root.optJSONArray("messages") ?: JSONArray()

        // A camera capture becomes context for exactly the next request.
        val ocr = ServerState.consumeOcrContext()
        if (ocr != null) ServerState.log("using OCR context (${ocr.length} chars)")

        val prompt = flattenMessages(messages, ocr)
        if (prompt.isBlank()) {
            return json(
                Response.Status.BAD_REQUEST,
                errorObj("messages[] is empty or missing", "invalid_request_error")
            )
        }

        if (!ServerState.modelLoaded) {
            return json(
                Response.Status.SERVICE_UNAVAILABLE,
                errorObj(
                    LlmEngine.lastError ?: "model is still loading, retry in a few seconds",
                    "model_not_ready"
                )
            )
        }

        val started = System.currentTimeMillis()
        val reply = generate(prompt)
        val elapsed = System.currentTimeMillis() - started

        val n = ServerState.requestsServed.incrementAndGet()
        ServerState.lastLatencyMs = elapsed
        ServerState.log(
            "#$n chat  ${elapsed}ms  ${"%.1f".format(ServerState.lastTokensPerSec)} tok/s  ${reply.length}ch"
        )

        val promptTokens = approxTokens(prompt)
        val completionTokens = approxTokens(reply)

        val payload = JSONObject()
            .put("id", "chatcmpl-tether-${System.nanoTime()}")
            .put("object", "chat.completion")
            .put("created", System.currentTimeMillis() / 1000)
            .put("model", ServerState.modelName)
            .put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put("index", 0)
                        .put(
                            "message",
                            JSONObject()
                                .put("role", "assistant")
                                .put("content", reply)
                        )
                        .put("finish_reason", "stop")
                )
            )
            .put(
                "usage",
                JSONObject()
                    .put("prompt_tokens", promptTokens)
                    .put("completion_tokens", completionTokens)
                    .put("total_tokens", promptTokens + completionTokens)
            )

        return json(Response.Status.OK, payload)
    }

    /** NanoHTTPD needs parseBody() called before the raw JSON is reachable. */
    private fun readBody(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return files["postData"] ?: ""
    }

    /**
     * Gemma 3 turn format. Gemma has no system role, so system content is
     * folded into the first user turn.
     */
    private fun flattenMessages(messages: JSONArray, ocrContext: String? = null): String {
        val system = StringBuilder()
        val turns = ArrayList<Pair<String, String>>()

        if (!ocrContext.isNullOrBlank()) {
            system.append("Use the following text, captured from the user's camera, ")
                .append("as context for the question.\n\n")
                .append(ocrContext)
                .append("\n\n")
        }

        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val role = m.optString("role", "user")
            val content = when (val c = m.opt("content")) {
                is String -> c
                is JSONArray -> (0 until c.length())
                    .mapNotNull { c.optJSONObject(it)?.optString("text", "") }
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                else -> ""
            }
            if (content.isBlank()) continue
            if (role == "system") system.append(content).append("\n\n") else turns.add(role to content)
        }

        if (turns.isEmpty() && system.isEmpty()) return ""

        val sb = StringBuilder()
        var systemInjected = false
        for ((role, content) in turns) {
            if (role == "assistant") {
                sb.append("<start_of_turn>model\n").append(content).append("<end_of_turn>\n")
            } else {
                sb.append("<start_of_turn>user\n")
                if (!systemInjected && system.isNotEmpty()) {
                    sb.append(system)
                    systemInjected = true
                }
                sb.append(content).append("<end_of_turn>\n")
            }
        }
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }

    private fun approxTokens(s: String): Int =
        if (s.isBlank()) 0 else LlmEngine.tokenCount(s).coerceAtLeast(1)

    private fun errorObj(message: String, type: String): JSONObject =
        JSONObject().put(
            "error",
            JSONObject().put("message", message).put("type", type).put("code", JSONObject.NULL)
        )

    private fun json(status: Response.Status, obj: JSONObject): Response =
        newFixedLengthResponse(status, "application/json", obj.toString())

    private fun text(status: Response.Status, s: String): Response =
        newFixedLengthResponse(status, "text/plain", s)

    private fun cors(r: Response): Response = r.apply {
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
    }
}
