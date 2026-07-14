package com.ollee.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
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
import kotlin.time.Duration.Companion.minutes

/**
 * Foreground service (type connectedDevice) that keeps the process alive while
 * the watch is connected, so Android's background limits don't drop the BLE
 * link. It observes the app-scoped connection state, reconnects unexpected
 * drops, and owns connected-session synchronization.
 */
class OlleeConnectionService : Service() {

    // Use Main dispatcher for the service lifecycle; it's high priority and
    // recommended for Service tasks that aren't heavy computation.
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var reconnectJob: Job? = null
    private var connectedSyncJob: Job? = null
    private val initialReconnectDelay = 3.seconds
    private val maximumReconnectDelay = 60.seconds

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Connection service created")
        (application as OlleeApp).connectionServiceActive = true
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
                    connectedSyncJob?.cancel()
                    scheduleReconnect()
                } else if (state == ConnectionState.READY) {
                    startConnectedSync(forceFullSync = true)
                } else {
                    connectedSyncJob?.cancel()
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            try {
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
                    } catch (e: Exception) {
                        Log.w(TAG, "Background reconnect failed", e)
                        // The next loop iteration retries with a longer delay.
                    }
                    if (repo.connectionState.value == ConnectionState.READY) return@launch
                    retryDelay = (retryDelay * 2).coerceAtMost(maximumReconnectDelay)
                }
            } finally {
                if (reconnectJob === currentCoroutineContext()[Job]) reconnectJob = null
            }
        }
    }

    private fun startConnectedSync(forceFullSync: Boolean) {
        if (connectedSyncJob?.isActive == true) return
        connectedSyncJob = scope.launch {
            try {
                val app = application as OlleeApp
                val repo = app.repository
                if (repo.connectionState.value != ConnectionState.READY) {
                    scheduleReconnect()
                    return@launch
                }

                syncWithWakeLock {
                    val status = app.syncCoordinator.status.value
                    val now = System.currentTimeMillis() / 1000
                    val fullSyncDue = forceFullSync ||
                        status.lastTimeSyncEpoch == null ||
                        status.lastHealthSyncEpoch == null ||
                        now - status.lastTimeSyncEpoch >= FULL_SYNC_INTERVAL.inWholeSeconds ||
                        now - status.lastHealthSyncEpoch >= FULL_SYNC_INTERVAL.inWholeSeconds

                    if (fullSyncDue) {
                        val outcome = app.syncCoordinator.syncAll()
                        Log.i(TAG, "Background full sync completed; success=${outcome.successful}")
                        if (!outcome.time.success && repo.connectionState.value == ConnectionState.READY) {
                            Log.w(TAG, "Time write failed; recycling stale GATT connection")
                            repo.disconnect()
                        }
                    } else {
                        try {
                            app.syncCoordinator.withWatchAccess { repo.liveValue() }
                            Log.d(TAG, "Standby BLE heartbeat completed")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Standby BLE heartbeat failed; reconnecting", e)
                            repo.disconnect()
                        }
                    }
                }
            } finally {
                if (connectedSyncJob === currentCoroutineContext()[Job]) connectedSyncJob = null
            }
        }
    }

    private suspend fun syncWithWakeLock(block: suspend () -> Unit) {
        val power = getSystemService(POWER_SERVICE) as PowerManager
        val wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Ollee:WatchSync")
        wakeLock.acquire(SYNC_WAKE_TIMEOUT_MS)
        try {
            block()
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scheduleNextMaintenance()
        if (intent?.action == ACTION_MAINTENANCE) {
            Log.i(TAG, "Standby maintenance alarm delivered")
            startConnectedSync(forceFullSync = false)
        }
        return START_STICKY
    }

    private fun scheduleNextMaintenance() {
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        val triggerAt = SystemClock.elapsedRealtime() + MAINTENANCE_INTERVAL.inWholeMilliseconds
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerAt,
            maintenanceIntent(this),
        )
        Log.d(TAG, "Next standby maintenance scheduled in ${MAINTENANCE_INTERVAL.inWholeMinutes}m")
    }

    override fun onDestroy() {
        Log.i(TAG, "Connection service destroyed")
        (application as OlleeApp).connectionServiceActive = false
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
        private const val TAG = "OlleeConnectionService"
        private const val CHANNEL_ID = "ollee_connection"
        private const val NOTIF_ID = 1
        private const val MAINTENANCE_REQUEST_CODE = 2
        private const val ACTION_MAINTENANCE = "com.ollee.companion.action.MAINTENANCE"
        private const val SYNC_WAKE_TIMEOUT_MS = 2 * 60_000L
        private val MAINTENANCE_INTERVAL = 10.minutes
        private val FULL_SYNC_INTERVAL = 30.minutes

        private fun maintenanceIntent(context: Context): PendingIntent =
            PendingIntent.getForegroundService(
                context,
                MAINTENANCE_REQUEST_CODE,
                Intent(context, OlleeConnectionService::class.java).setAction(ACTION_MAINTENANCE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, OlleeConnectionService::class.java),
            )
        }

        fun stop(context: Context) {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                .cancel(maintenanceIntent(context))
            context.stopService(Intent(context, OlleeConnectionService::class.java))
        }
    }
}
