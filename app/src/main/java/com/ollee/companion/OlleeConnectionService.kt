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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service (type connectedDevice) that keeps the process alive while
 * the watch is connected, so Android's background limits don't drop the BLE
 * link. It observes the app-scoped connection state and stops itself once the
 * watch disconnects.
 */
class OlleeConnectionService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat()

        // Mirror the connection state in the notification. The service is NOT
        // stopped on a dropped link — the ViewModel auto-reconnects, so we stay
        // foreground through transient drops. It is stopped explicitly via
        // stop() only when the user taps Disconnect.
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
                // Reuse the existing Activity (with singleTop) instead of
                // creating a duplicate when the notification is tapped.
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Ollee Companion")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_watch)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "Watch connection", NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Keeps the watch connected in the background" },
        )
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
