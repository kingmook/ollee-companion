package com.ollee.companion

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.ollee.companion.ble.OlleeProtocol
import com.ollee.companion.ble.OlleeRepository
import com.ollee.companion.ble.RecordSyncResult
import com.ollee.companion.data.RecordStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SyncStatus(
    val syncing: Boolean = false,
    val lastTimeSyncEpoch: Long? = null,
    val lastHealthSyncEpoch: Long? = null,
    val lastError: String? = null,
)

data class SyncOperationResult(val success: Boolean, val error: String? = null)

data class HealthSyncOutcome(
    val sync: RecordSyncResult?,
    val totalStored: Int,
    val error: String? = null,
)

data class FullSyncOutcome(
    val time: SyncOperationResult,
    val health: HealthSyncOutcome,
) {
    val successful: Boolean get() = time.success && health.error == null
}

/** Application-scoped owner of all automatic and manual watch synchronization. */
class SyncCoordinator(
    context: Context,
    private val repository: OlleeRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext = context.applicationContext
    private val store = RecordStore(appContext)
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _records = MutableStateFlow<List<OlleeProtocol.Record>>(emptyList())
    val records: StateFlow<List<OlleeProtocol.Record>> = _records.asStateFlow()

    private val _status = MutableStateFlow(
        SyncStatus(
            lastTimeSyncEpoch = prefs.longOrNull(KEY_LAST_TIME),
            lastHealthSyncEpoch = prefs.longOrNull(KEY_LAST_HEALTH),
            lastError = prefs.getString(KEY_LAST_ERROR, null),
        ),
    )
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    init {
        scope.launch {
            try {
                _records.value = withContext(ioDispatcher) { store.loadAll() }
            } catch (e: Exception) {
                recordError("Stored health history could not be read: ${e.message}")
            }
        }
    }

    suspend fun syncTime(): SyncOperationResult = mutex.withSyncState { syncTimeLocked() }

    suspend fun syncHealth(): HealthSyncOutcome = mutex.withSyncState { syncHealthLocked() }

    /** Serialize non-sync GATT reads with record drains, which must not be interleaved. */
    suspend fun <T> withWatchAccess(block: suspend () -> T): T = mutex.withLock { block() }

    suspend fun syncAll(): FullSyncOutcome = mutex.withSyncState {
        // Time failure should not prevent preservation of pending health records.
        val time = syncTimeLocked()
        val health = syncHealthLocked()
        FullSyncOutcome(time, health)
    }

    private suspend fun syncTimeLocked(): SyncOperationResult = try {
        repository.syncTime()
        val now = System.currentTimeMillis() / 1000
        _status.update { it.copy(lastTimeSyncEpoch = now, lastError = null) }
        prefs.edit { putLong(KEY_LAST_TIME, now); remove(KEY_LAST_ERROR) }
        SyncOperationResult(success = true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val message = "Time sync failed: ${e.message ?: "unknown error"}"
        recordError(message, e)
        SyncOperationResult(success = false, error = message)
    }

    private suspend fun syncHealthLocked(): HealthSyncOutcome = try {
        var all = _records.value
        val sync = repository.syncRecords { records ->
            all = withContext(ioDispatcher) { store.merge(records) }
        }
        _records.value = all
        val error = when {
            !sync.drained -> sync.failure ?: "Health record sync did not complete"
            !sync.acknowledged -> sync.failure ?: "Watch did not confirm record cleanup"
            else -> null
        }
        if (error == null) {
            val now = System.currentTimeMillis() / 1000
            _status.update { it.copy(lastHealthSyncEpoch = now, lastError = null) }
            prefs.edit { putLong(KEY_LAST_HEALTH, now); remove(KEY_LAST_ERROR) }
        } else {
            recordError(error)
        }
        HealthSyncOutcome(sync, all.size, error)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val message = "Health sync failed: ${e.message ?: "unknown error"}"
        recordError(message, e)
        HealthSyncOutcome(null, _records.value.size, message)
    }

    private fun recordError(message: String, error: Throwable? = null) {
        if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error)
        _status.update { it.copy(lastError = message) }
        prefs.edit { putString(KEY_LAST_ERROR, message) }
    }

    private suspend fun <T> Mutex.withSyncState(block: suspend () -> T): T = withLock {
        _status.update { it.copy(syncing = true) }
        try {
            block()
        } finally {
            _status.update { it.copy(syncing = false) }
        }
    }

    private fun android.content.SharedPreferences.longOrNull(key: String): Long? =
        if (contains(key)) getLong(key, 0) else null

    companion object {
        private const val TAG = "OlleeSync"
        private const val PREFS = "ollee_sync_status"
        private const val KEY_LAST_TIME = "last_time_sync"
        private const val KEY_LAST_HEALTH = "last_health_sync"
        private const val KEY_LAST_ERROR = "last_sync_error"
    }
}
