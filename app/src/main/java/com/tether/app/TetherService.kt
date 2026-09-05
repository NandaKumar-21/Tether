package com.tether.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Keeps the HTTP server alive when the app is backgrounded or the screen is off.
 */
class TetherService : Service() {

    companion object {
        const val ACTION_STOP = "com.tether.app.STOP"
        private const val CHANNEL_ID = "tether_server"
        private const val NOTIFICATION_ID = 1001
    }

    private var server: TetherServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(statusText())
        acquireWakeLock()
        startServer()
        loadModelInBackground()
    }

    /**
     * Model init takes tens of seconds and must not block onCreate (ANR).
     * The server answers with a clear 503 until this finishes.
     */
    private fun loadModelInBackground() {
        Thread({
            LlmEngine.initialize(applicationContext)
            updateNotification()
        }, "tether-model-init").start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        LlmEngine.shutdown()
        ServerState.running = false
        ServerState.log("server stopped")
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun startServer() {
        ServerState.ipAddress = localIpAddress()
        try {
            // M2 replaces this lambda with the MediaPipe call.
            val srv = TetherServer(ServerState.PORT) { prompt -> respond(prompt) }
            // Generous socket timeout: real generation in M2 is far slower than the 5s default.
            srv.start(60_000, false)
            server = srv
            ServerState.running = true
            ServerState.log("server listening on 0.0.0.0:${ServerState.PORT}")
            ServerState.log("lan address http://${ServerState.ipAddress}:${ServerState.PORT}")
            updateNotification()
        } catch (t: Throwable) {
            ServerState.running = false
            ServerState.log("FAILED to bind ${ServerState.PORT}: ${t.message}")
            updateNotification()
        }
    }

    /** Runs on a NanoHTTPD worker thread. LlmEngine serialises concurrent callers. */
    private fun respond(prompt: String): String = LlmEngine.generate(prompt)

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Tether server",
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, TetherService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Tether")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(text), type)
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(statusText()))
    }

    private fun statusText(): String =
        if (ServerState.running) "Listening on :${ServerState.PORT}" else "Starting..."

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Tether::server").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun localIpAddress(): String {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val ip = wifi?.connectionInfo?.ipAddress ?: 0
            if (ip != 0) {
                return "%d.%d.%d.%d".format(
                    ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff
                )
            }
        } catch (_: Throwable) {
        }
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                for (addr in ni.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "unknown"
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return "no network (use adb forward)"
    }
}
