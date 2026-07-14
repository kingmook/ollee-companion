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
import androidx.core.content.ContextCompat
import com.ollee.companion.ble.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    private val initialReconnectDelay = 3.seconds
    private val maximumReconnectDelay = 60.seconds

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat()

        // Mirror the connection state in the notification and handle background
        // auto-reconnect.
        val repo = (application as OlleeApp).repository
        scope.launch {
            repo.connectionState.collect { state ->
                updateNotification(
                    when (state) {
                        ConnectionState.CONNECTING -> "Searching for your Ollee watch…"
                        ConnectionState.READY -> "Connected to your Ollee watch"
                        ConnectionState.DISCONNECTED -> "Reconnecting to your Ollee watch…"
                    },
                )

                if (state == ConnectionState.DISCONNECTED) {
                    scheduleReconnect()
                } else if (state == ConnectionState.READY) {
                    reconnectJob?.cancel()
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val prefs = getSharedPreferences("ollee_prefs", MODE_PRIVATE)
            val addr = prefs.getString("last_address", null) ?: return@launch
            val repo = (application as OlleeApp).repository

            var retryDelay = initialReconnectDelay
            while (currentCoroutineContext().isActive) {
                delay(retryDelay)
                when (repo.connectionState.value) {
                    ConnectionState.READY -> return@launch
                    ConnectionState.CONNECTING -> continue
                    ConnectionState.DISCONNECTED -> Unit
                }

                // Each attempt is bounded so a wedged Android GATT operation
                // cannot stall reconnection indefinitely. autoConnect keeps an
                // individual attempt power-efficient while the watch is away.
                try {
                    repo.connect(addr, autoConnect = true, timeoutMs = 45_000L)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The next loop iteration retries with a longer delay.
                }
                if (repo.connectionState.value == ConnectionState.READY) return@launch
                retryDelay = (retryDelay * 2).coerceAtMost(maximumReconnectDelay)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
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
