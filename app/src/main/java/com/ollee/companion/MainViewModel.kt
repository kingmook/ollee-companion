package com.ollee.companion

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.location.LocationManager
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ollee.companion.ble.ConnectionState
import com.ollee.companion.ble.LinkDeadException
import com.ollee.companion.ble.OlleeProtocol
import com.ollee.companion.data.RecordStore
import com.ollee.companion.feature.SunCalculator
import com.ollee.companion.feature.SunTimes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class ScannedDevice(val name: String, val address: String)

/** One day's temperature summary (low/high/avg in degrees C). */
data class DailyTemp(
    val label: String, val dayEpoch: Long,
    val minC: Double, val maxC: Double, val avgC: Double,
)

/** One day's heart-rate summary (min/max/avg bpm + sample count). */
data class DailyHr(
    val label: String, val dayEpoch: Long,
    val min: Int, val max: Int, val avg: Int, val count: Int,
)

/** One day's total step count. */
data class DailyStep(val label: String, val dayEpoch: Long, val steps: Int)

data class UiState(
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val scanning: Boolean = false,
    val devices: List<ScannedDevice> = emptyList(),
    val firmware: String? = null,
    val name: String? = null,
    val stepGoal: Int? = null,
    val liveValue: Int? = null,
    val sun: SunTimes? = null,
    val syncing: Boolean = false,
    val verifying: Boolean = false,
    val reconnecting: Boolean = false,
    val tempDaily: List<DailyTemp> = emptyList(),
    val hrDaily: List<DailyHr> = emptyList(),
    val stepDaily: List<DailyStep> = emptyList(),
    val todaySteps: Int = 0,
    val message: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as OlleeApp).repository
    private val store = (app as OlleeApp).recordStore

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val dayFmt = SimpleDateFormat("EEE MMM d", Locale.getDefault())

    private val prefs = app.getSharedPreferences("ollee_prefs", Context.MODE_PRIVATE)
    private val lastAddressKey = "last_address"

    /** The last successfully connected watch, or null if none yet (scan first). */
    val lastAddress: String?
        get() = prefs.getString(lastAddressKey, null)

    private var pendingAddress: String? = null

    private var scanJob: Job? = null
    private val scanTimeout = 20.seconds

    /** True only when the user taps Disconnect, to suppress auto-reconnect. */
    private var userDisconnect = false
    private var reconnectingClearJob: Job? = null
    // How long to keep the watch screen up (with a "Reconnecting…" banner)
    // during an auto-reconnect before falling back to the connect panel.
    private val reconnectingGrace = 15.seconds

    private var verifyJob: Job? = null
    private val verifyBudget = 60.seconds
    // Cap the post-reconnect info+time sync so the overlay can't hang on a slow
    // read (the reconnect itself is still bounded by verifyBudget).
    private val syncPhaseBudget = 12.seconds

    /** Sanity ranges to reject corrupt/sentinel sensor records. */
    private val plausibleTempC = -40.0..85.0
    private val plausibleBpm = 20..255
    private val plausibleSteps = 0..50_000  // a single record's step count

    init {
        viewModelScope.launch {
            repo.connectionState.collect { state ->
                _ui.update { it.copy(connection = state) }
                when (state) {
                    ConnectionState.READY -> {
                        // Remember this watch so we can auto-connect next launch.
                        pendingAddress?.let { addr ->
                            prefs.edit { putString(lastAddressKey, addr) }
                        }
                        reconnectingClearJob?.cancel()
                        _ui.update { it.copy(reconnecting = false) }
                    }
                    ConnectionState.CONNECTING -> {}
                    ConnectionState.DISCONNECTED -> {
                        maybeAutoReconnect()
                    }
                }
            }
        }
        // Observe global sync status.
        viewModelScope.launch {
            repo.isSyncing.collect { syncing ->
                _ui.update { it.copy(syncing = syncing) }
            }
        }
        // Reload history whenever new records are merged (manual or background).
        viewModelScope.launch {
            repo.newRecords.collect {
                applyRecords(withContext(Dispatchers.IO) { store.loadAll() })
            }
        }
        // Show stored history immediately, before any connection.
        viewModelScope.launch {
            applyRecords(withContext(Dispatchers.IO) { store.loadAll() })
        }
        // On launch, auto-connect to the last successfully connected watch.
        lastAddress?.let { connect(it) }
    }

    private val scanner get() =
        (getApplication<Application>().getSystemService(Context.BLUETOOTH_SERVICE)
                as BluetoothManager).adapter.bluetoothLeScanner

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName ?: "(unknown)"
            val dev = ScannedDevice(name, result.device.address)
            _ui.update { s ->
                if (s.devices.any { it.address == dev.address }) s
                else {
                    val newList = (s.devices + dev).sortedWith(
                        compareByDescending<ScannedDevice> {
                            it.name.contains("ollee", ignoreCase = true)
                        }.thenBy {
                            it.name == "(unknown)"
                        }.thenBy { it.name },
                    )
                    s.copy(devices = newList)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        _ui.update { it.copy(scanning = true, devices = emptyList()) }
        runCatching { scanner.startScan(scanCallback) }
            .onFailure { setMessage("Scan failed: ${it.message}") }
        // Bound the scan: Android throttles long scans and they drain battery.
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            delay(scanTimeout)
            stopScan()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        runCatching { scanner.stopScan(scanCallback) }
        _ui.update { it.copy(scanning = false) }
    }

    fun connect(address: String, autoConnect: Boolean = false) {
        userDisconnect = false
        pendingAddress = address
        stopScan()
        // Start the foreground service so the link survives backgrounding.
        OlleeConnectionService.start(getApplication())
        viewModelScope.launch {
            catching { repo.connect(address, autoConnect) }
                .onFailure { setMessage("Connect failed: ${it.message}") }
        }
    }

    fun disconnect() {
        userDisconnect = true
        reconnectingClearJob?.cancel()
        _ui.update { it.copy(reconnecting = false) }
        viewModelScope.launch { repo.disconnect() }
        OlleeConnectionService.stop(getApplication())
    }

    /**
     * Re-establish the link after an *unexpected* drop (stale link tear-down,
     * watch out of range) — but not after the user taps Disconnect. The
     * foreground service stays up, so we keep trying until the watch returns.
     */
    private fun maybeAutoReconnect() {
        if (userDisconnect) return
        if (lastAddress == null) return
        // Keep the watch screen up (with a banner) instead of flashing the
        // connect panel, but fall back to it if reconnection drags on.
        _ui.update { it.copy(reconnecting = true) }
        reconnectingClearJob?.cancel()
        reconnectingClearJob = viewModelScope.launch {
            delay(reconnectingGrace)
            if (repo.connectionState.value != ConnectionState.READY) {
                _ui.update { it.copy(reconnecting = false) }
            }
        }
        // The actual reconnection loop is handled by OlleeConnectionService
        // while it is running, to ensure persistence even if the app is
        // backgrounded/swiped away.
    }

    /**
     * On returning to the foreground (notification tap or task switcher),
     * ensure we have a live connection. If we are currently disconnected or
     * in the middle of a slow background auto-reconnect, force a fresh fast
     * connection. If we are already connected, verify the link is still
     * responsive and push the time, blocking the UI with an overlay until done.
     */
    fun verifyConnection() {
        if (verifyJob?.isActive == true) return
        verifyJob = viewModelScope.launch {
            val state = repo.connectionState.value
            // If we have no watch yet, there's nothing to verify.
            if (state == ConnectionState.DISCONNECTED && lastAddress == null) return@launch

            _ui.update { it.copy(verifying = true) }
            try {
                withTimeoutOrNull(verifyBudget) {
                    if (state == ConnectionState.READY) {
                        reconnectThenSync()
                    } else {
                        // Not connected or in a slow background reconnect. Force
                        // a fresh fast connection now that we're foregrounded.
                        val addr = lastAddress ?: return@withTimeoutOrNull
                        repo.disconnect()
                        userDisconnect = false
                        pendingAddress = addr
                        stopScan()
                        OlleeConnectionService.start(getApplication())
                        
                        catching { repo.connect(addr, autoConnect = false) }
                            .onFailure { setMessage("Connect failed: ${it.message}") }
                            .getOrNull() ?: return@withTimeoutOrNull

                        tryRefresh()
                        catching { repo.syncTime() }
                    }
                }
            } finally {
                _ui.update { it.copy(verifying = false) }
            }
        }
    }

    private suspend fun reconnectThenSync() {
        // Try a quick refresh first. If the link is still alive (responsive),
        // we're done instantly.
        if (tryRefresh()) {
            catching { repo.syncTime() }
            return
        }
        // If the link is stale, drop it and reconnect immediately using a fast
        // direct connection (autoConnect=false) to avoid the 3s auto-delay.
        repo.disconnect()
        delay(500) // Give the OS a moment to release the radio
        
        val addr = lastAddress ?: return
        userDisconnect = false
        pendingAddress = addr
        stopScan()
        OlleeConnectionService.start(getApplication())
        
        catching { repo.connect(addr, autoConnect = false) }
            .onFailure { setMessage("Reconnect failed: ${it.message}") }

        repo.connectionState.first { it == ConnectionState.READY }
        withTimeoutOrNull(syncPhaseBudget) {
            tryRefresh()
            catching { repo.syncTime() }
        }
    }

    fun refresh() = viewModelScope.launch { refreshInternal() }

    private suspend fun refreshInternal() {
        if (!tryRefresh()) setMessage("Watch not responding — retrying…")
    }

    /**
     * Read device info, keeping the last-known value on a per-field miss.
     * Returns true only if the watch actually answered a core read (firmware)
     * within a short 1-second window — the signal that the link is genuinely
     * alive and responsive.
     */
    private suspend fun tryRefresh(): Boolean {
        // Fast-path: if firmware read fails within 1s, the link is likely stale.
        val fw = withTimeoutOrNull(1000) { catching { repo.firmware() }.getOrNull() }
        if (fw == null) return false

        // Link is alive, fetch the rest at normal speed.
        val goal = catching { repo.stepGoal() }.getOrNull()
        val live = catching { repo.liveValue() }.getOrNull()
        val nm = catching { repo.name() }.getOrNull()
        _ui.update {
            it.copy(
                firmware = fw,
                stepGoal = goal ?: it.stepGoal,
                liveValue = live ?: it.liveValue,
                name = nm ?: it.name,
            )
        }
        return true
    }

    fun syncTimeNow() = action { repo.syncTime(); "Time synced." }

    /** Drain the watch's log, merge into 30-day storage, and refresh history. */
    fun syncRecords() = viewModelScope.launch { doRecordsSync(announce = true) }

    private suspend fun doRecordsSync(announce: Boolean) {
        try {
            val recs = repo.syncHealthRecords()
            if (announce) {
                // History reload is handled by the newRecords observer in init.
                setMessage("Synced ${recs.size} records.")
            }
        } catch (e: Exception) {
            if (announce) setMessage("Records sync failed: ${e.message}")
        }
    }

    /** Split stored records into per-type history and daily summaries. */
    private fun applyRecords(all: List<OlleeProtocol.Record>) {
        // Drop physically-impossible readings (corrupt/sentinel records) so a
        // single garbage value can't skew a day's min/max (e.g. 14854 °C).
        val temp = all.asSequence().filter {
            (it.type == OlleeProtocol.REC_TEMPERATURE) && (it.celsius in plausibleTempC)
        }.sortedByDescending { it.tStart }.toList()
        val hr = all.asSequence().filter {
            (it.type == OlleeProtocol.REC_HEART_RATE) && (it.bpm in plausibleBpm)
        }.sortedByDescending { it.tStart }.toList()
        val steps = all.asSequence().filter {
            (it.type == OlleeProtocol.REC_STEPS) && (it.value in plausibleSteps)
        }.toList()
        val stepDays = dailySteps(steps)
        val todayKey = startOfLocalDay(System.currentTimeMillis() / 1000)
        val today = stepDays.firstOrNull { it.dayEpoch == todayKey }?.steps ?: 0
        _ui.update {
            it.copy(
                tempDaily = dailyTemp(temp), hrDaily = dailyHr(hr),
                stepDaily = stepDays, todaySteps = today,
            )
        }
    }

    private fun dailyTemp(list: List<OlleeProtocol.Record>): List<DailyTemp> =
        list.groupBy { startOfLocalDay(it.tStart) }
            .asSequence()
            .map { (day, recs) ->
                val c = recs.map { it.celsius }
                DailyTemp(dayFmt.format(Date(day * 1000)), day, c.min(), c.max(), c.average())
            }
            .sortedByDescending { it.dayEpoch }
            .toList()

    private fun dailyHr(list: List<OlleeProtocol.Record>): List<DailyHr> =
        list.groupBy { startOfLocalDay(it.tStart) }
            .asSequence()
            .map { (day, recs) ->
                val b = recs.map { it.bpm }
                DailyHr(
                    dayFmt.format(Date(day * 1000)),
                    day,
                    b.min(),
                    b.max(),
                    b.average().toInt(),
                    b.size,
                )
            }
            .sortedByDescending { it.dayEpoch }
            .toList()

    private fun dailySteps(list: List<OlleeProtocol.Record>): List<DailyStep> =
        list.groupBy { startOfLocalDay(it.tStart) }
            .asSequence()
            .map { (day, recs) ->
                DailyStep(dayFmt.format(Date(day * 1000)), day, recs.sumOf { it.value })
            }
            .sortedByDescending { it.dayEpoch }
            .toList()

    private fun startOfLocalDay(epochSec: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = epochSec * 1000
        cal[Calendar.HOUR_OF_DAY] = 0
        cal[Calendar.MINUTE] = 0
        cal[Calendar.SECOND] = 0
        cal[Calendar.MILLISECOND] = 0
        return cal.timeInMillis / 1000
    }

    fun setAlarm(hour: Int, minute: Int, daysMask: Int) = action {
        repo.setAlarm(hour, minute, daysMask)
        "Alarm set for %02d:%02d.".format(hour, minute)
    }

    fun clearAlarm() = action { repo.clearAlarm(); "Alarm cleared." }

    @SuppressLint("MissingPermission")
    fun computeSun() = viewModelScope.launch {
        val lm = getApplication<Application>()
            .getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = runCatching {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }.getOrNull()
        if (loc == null) {
            setMessage("No location yet — enable GPS and grant location.")
            return@launch
        }
        val sun = SunCalculator.compute(loc.latitude, loc.longitude, System.currentTimeMillis())
        _ui.update { it.copy(sun = sun) }
    }

    private inline fun action(crossinline block: suspend () -> String) {
        viewModelScope.launch {
            try {
                val msg = block()
                if (msg.isNotEmpty()) setMessage(msg)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setMessage("Error: ${e.message}")
            }
        }
    }

    /** Like [runCatching], but never swallows coroutine cancellation. */
    private inline fun <T> catching(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }

    fun clearMessage() = _ui.update { it.copy(message = null) }
    private fun setMessage(m: String) = _ui.update { it.copy(message = m) }
    // No onCleared() release: the connection is app-scoped and kept alive by the
    // foreground service; it ends on explicit disconnect, not Activity teardown.
}
