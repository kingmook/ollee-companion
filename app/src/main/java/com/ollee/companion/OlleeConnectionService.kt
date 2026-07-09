package com.ollee.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.ollee.companion.ble.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Foreground service (type connectedDevice) that keeps the process alive while
 * the watch is connected, so Android's background limits don't drop the BLE
 * link. It observes the app-scoped connection state and stops itself once the
 * watch disconnects.
 */
class OlleeConnectionService : Service() {

    // Use Main dispatcher for the service lifecycle; it's high priority and
    // recommended for Service tasks that aren't heavy computation.
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var reconnectJob: Job? = null
    private val reconnectDelay = 3.seconds

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Hold a wake lock for the entire life of the service.
        acquireWakeLock()
        
        createChannel()
        startForegroundCompat()

        // Mirror the connection state in the notification and handle background
        // auto-reconnect.
        val repo = (application as OlleeApp).repository
        scope.launch {
            // Ignore the very first state emission if it's DISCONNECTED to avoid
            // racing with the Activity's initial connect attempt on boot.
            var firstEvent = true
            repo.connectionState.collect { state ->
                val isFirst = firstEvent
                firstEvent = false

                updateNotification(
                    when (state) {
                        ConnectionState.CONNECTING -> "Searching for your Ollee watch…"
                        ConnectionState.READY -> "Connected to your Ollee watch"
                        ConnectionState.DISCONNECTED -> "Reconnecting to your Ollee watch…"
                    },
                )

                if (state == ConnectionState.DISCONNECTED) {
                    stopAutoSync()
                    if (!isFirst) scheduleReconnect()
                } else if (state == ConnectionState.READY) {
                    reconnectJob?.cancel()
                    startAutoSync()
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            delay(reconnectDelay)
            val prefs = getSharedPreferences("ollee_prefs", MODE_PRIVATE)
            val addr = prefs.getString("last_address", null) ?: return@launch
            val repo = (application as OlleeApp).repository
            
            // Background reconnection uses autoConnect=true so the OS keeps
            // looking at low power until the watch is in range.
            if (repo.connectionState.value == ConnectionState.DISCONNECTED) {
                runCatching { 
                    repo.connect(addr, autoConnect = true, timeoutMs = 24 * 3600_000L) 
                }
            }
        }
    }

    private var autoSyncJob: Job? = null
    private val autoSyncInterval = 30.minutes

    /**
     * Periodic background sync: push time and drain health records every 30 mins
     * while connected. This ensures health data is backed up even if the app
     * isn't opened for days.
     */
    private fun startAutoSync() {
        if (autoSyncJob?.isActive == true) return
        val repo = (application as OlleeApp).repository
        autoSyncJob = scope.launch {
            while (true) {
                if (repo.connectionState.value == ConnectionState.READY) {
                    runCatching { repo.syncTime() }
                    runCatching { repo.syncHealthRecords() }
                }
                delay(autoSyncInterval)
            }
        }
    }

    private fun stopAutoSync() {
        autoSyncJob?.cancel()
        autoSyncJob = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        // Ensure the wake lock stays active even if the service is restarted.
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Ollee:ConnectionService")
        }
        wakeLock?.acquire()
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun startForegroundCompat() {
        val notification = buildNotification("Keeping your Ollee watch connected")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) CHANNEL_ID else ""
        return Notification.Builder(this, channel)
            .setContentTitle("Ollee Companion")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_watch)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Watch connection", NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Keeps the watch connected in the background" },
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "ollee_connection"
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, OlleeConnectionService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OlleeConnectionService::class.java))
        }
    }
}
