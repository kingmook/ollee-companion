package com.ollee.companion

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ollee.companion.ble.ConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/** Durable, inexact fallback for sync when the connected-device service is absent. */
class OlleeSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    @SuppressLint("MissingPermission")
    override suspend fun doWork(): Result {
        val app = applicationContext as OlleeApp
        val prefs = app.getSharedPreferences("ollee_prefs", Context.MODE_PRIVATE)
        val address = prefs.getString("last_address", null) ?: return Result.success()
        if (!hasBluetoothPermission() || !isBluetoothEnabled()) return Result.success()
        val timeOnly = inputData.getBoolean(KEY_TIME_ONLY, false)
        val serviceOwnsConnection = app.connectionServiceActive

        var connectedByWorker = false
        try {
            when (app.repository.connectionState.value) {
                ConnectionState.READY -> Unit
                ConnectionState.CONNECTING -> {
                    val state = withTimeoutOrNull(
                        if (serviceOwnsConnection) SERVICE_RECONNECT_TIMEOUT_MS else CONNECT_TIMEOUT_MS,
                    ) {
                        app.repository.connectionState.first { it != ConnectionState.CONNECTING }
                    }
                    if (state != ConnectionState.READY) return Result.retry()
                }
                ConnectionState.DISCONNECTED -> {
                    if (serviceOwnsConnection) {
                        // Do not steal ownership from the foreground service. Wake it and
                        // allow its reconnect loop to restore the long-lived connection.
                        OlleeConnectionService.start(applicationContext)
                        val state = withTimeoutOrNull(SERVICE_RECONNECT_TIMEOUT_MS) {
                            app.repository.connectionState.first { it == ConnectionState.READY }
                        }
                        if (state != ConnectionState.READY) return Result.retry()
                    } else {
                        connectedByWorker = app.repository.connect(
                            address,
                            autoConnect = false,
                            timeoutMs = CONNECT_TIMEOUT_MS,
                        )
                    }
                }
            }

            val success = if (timeOnly) {
                app.syncCoordinator.syncTime().success
            } else {
                app.syncCoordinator.syncAll().successful
            }
            return if (success) Result.success() else Result.retry()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Result.retry()
        } finally {
            if (connectedByWorker) runCatching { app.repository.disconnect() }
        }
    }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun isBluetoothEnabled(): Boolean =
        (applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
            .adapter?.isEnabled == true

    companion object {
        private const val PERIODIC_NAME = "ollee-periodic-watch-sync"
        private const val TIME_NAME = "ollee-system-time-sync"
        private const val KEY_TIME_ONLY = "time_only"
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val SERVICE_RECONNECT_TIMEOUT_MS = 60_000L

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<OlleeSyncWorker>(
                30, TimeUnit.MINUTES,
                15, TimeUnit.MINUTES,
            ).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
            )
        }

        fun enqueueTimeSync(context: Context) {
            val request = OneTimeWorkRequestBuilder<OlleeSyncWorker>()
                .setInputData(workDataOf(KEY_TIME_ONLY to true))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                TIME_NAME, ExistingWorkPolicy.REPLACE, request,
            )
        }
    }
}

/** Reschedules the watch clock after manual time or timezone changes. */
class SystemTimeChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action == Intent.ACTION_TIME_CHANGED ||
            intent.action == Intent.ACTION_TIMEZONE_CHANGED
        ) {
            OlleeSyncWorker.enqueueTimeSync(context)
        }
    }
}
