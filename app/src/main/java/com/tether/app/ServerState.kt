package com.tether.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single source of truth for what the status screen shows.
 * Written by the service thread, read by the UI thread.
 */
object ServerState {

    const val PORT = 8080

    @Volatile var running: Boolean = false
    @Volatile var modelName: String = "gemma-3-1b-it-int4"
    @Volatile var modelLoaded: Boolean = false
    @Volatile var backend: String = "-"
    @Volatile var lastTokensPerSec: Double = 0.0
    @Volatile var lastLatencyMs: Long = 0L
    @Volatile var ipAddress: String = "-"

    /** Text from the last camera OCR capture. Consumed by the next chat request. */
    @Volatile var ocrContext: String? = null

    fun consumeOcrContext(): String? {
        val c = ocrContext
        ocrContext = null
        return c
    }

    val requestsServed = AtomicInteger(0)

    private val logLines = ArrayDeque<String>()
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun log(line: String) {
        logLines.addLast("${stamp.format(Date())}  $line")
        while (logLines.size > 200) logLines.removeFirst()
    }

    @Synchronized
    fun logSnapshot(): List<String> = logLines.toList()

    @Synchronized
    fun clearLog() = logLines.clear()
}
