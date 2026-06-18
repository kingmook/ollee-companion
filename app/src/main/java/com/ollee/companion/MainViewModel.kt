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
    val temperatureLog: List<OlleeProtocol.Record> = emptyList(),
    val hrLog: List<OlleeProtocol.Record> = emptyList(),
    val tempDaily: List<DailyTemp> = emptyList(),
    val hrDaily: List<DailyHr> = emptyList(),
    val stepDaily: List<DailyStep> = emptyList(),
    val todaySteps: Int = 0,
    val message: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as OlleeApp).repository
    private val store = RecordStore(app)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val dayFmt = SimpleDateFormat("EEE MMM d", Locale.getDefault())

    private val prefs = app.getSharedPreferences("ollee_prefs", Context.MODE_PRIVATE)
    private val lastAddressKey = "last_address"

    /** Address to connect to: last successfully connected watch, else default. */
    val defaultAddress: String
        get() = prefs.getString(lastAddressKey, null) ?: "00:80:E1:26:08:5D"

    private var pendingAddress: String? = null
    private var autoSyncJob: Job? = null
    private val autoSyncInterval = 60.minutes

    /** True only when the user taps Disconnect, to suppress auto-reconnect. */
    private var userDisconnect = false
    private val reconnectDelay = 3.seconds
    private var reconnectingClearJob: Job? = null
    // How long to keep the watch screen up (with a "Reconnecting…" banner)
    // during an auto-reconnect before falling back to the connect panel.
    private val reconnectingGrace = 15.seconds

    /** Serializes syncs so a resume-verify can await an in-flight auto-sync. */
    private val syncMutex = Mutex()
    private var verifyJob: Job? = null
    private val verifyBudget = 60.seconds
    private val probeAttempts = 4
    private val probeRetryDelay = 1.5.seconds

    /** Sanity ranges to reject corrupt/sentinel sensor records. */
    private val plausibleTempC = -40.0..85.0
    private val plausibleBpm = 20..255

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
                        startAutoSync()
                    }
                    ConnectionState.CONNECTING -> stopAutoSync()
                    ConnectionState.DISCONNECTED -> {
                        stopAutoSync()
                        maybeAutoReconnect()
                    }
                }
            }
        }
        // Show stored history immediately, before any connection.
        viewModelScope.launch {
            applyRecords(withContext(Dispatchers.IO) { store.loadAll() })
        }
        // On launch, auto-connect to the last successfully connected watch.
        prefs.getString(lastAddressKey, null)?.let { connect(it) }
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
                else s.copy(devices = s.devices + dev)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        _ui.update { it.copy(scanning = true, devices = emptyList()) }
        runCatching { scanner.startScan(scanCallback) }
            .onFailure { setMessage("Scan failed: ${it.message}") }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        runCatching { scanner.stopScan(scanCallback) }
        _ui.update { it.copy(scanning = false) }
    }

    fun connect(address: String) {
        userDisconnect = false
        pendingAddress = address
        stopScan()
        // Start the foreground service so the link survives backgrounding.
        OlleeConnectionService.start(getApplication())
        viewModelScope.launch {
            runCatching { repo.connect(address) }
                .onFailure { setMessage("Connect failed: ${it.message}") }
        }
    }

    fun disconnect() {
        userDisconnect = true
        reconnectingClearJob?.cancel()
        _ui.update { it.copy(reconnecting = false) }
        repo.disconnect()
        OlleeConnectionService.stop(getApplication())
    }

    /**
     * Re-establish the link after an *unexpected* drop (stale link tear-down,
     * watch out of range) — but not after the user taps Disconnect. The
     * foreground service stays up, so we keep trying until the watch returns.
     */
    private fun maybeAutoReconnect() {
        if (userDisconnect) return
        val addr = prefs.getString(lastAddressKey, null) ?: return
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
        viewModelScope.launch {
            delay(reconnectDelay)
            if (!userDisconnect &&
                repo.connectionState.value == ConnectionState.DISCONNECTED
            ) {
                connect(addr)
            }
        }
    }

    /**
     * On returning to the foreground, block the UI with a "reconnecting…"
     * overlay until the link is confirmed live AND a fresh sync completes — the
     * silent few-second update was confusing. A stale ("zombie") link still
     * reports READY but no longer answers, so we probe it; if it's dead we drop
     * it and wait for auto-reconnect. If nothing comes back within
     * [verifyBudget] (1 min) we kill the connection and show the connect screen.
     */
    fun verifyConnection() {
        if (repo.connectionState.value != ConnectionState.READY) return
        if (verifyJob?.isActive == true) return
        verifyJob = viewModelScope.launch {
            _ui.update { it.copy(verifying = true) }
            try {
                // Best-effort: never tear the link down here. If it can't be
                // confirmed in time, drop the overlay and let auto-reconnect keep
                // working — don't dump the user to the connect screen and give up.
                withTimeoutOrNull(verifyBudget) { verifyAndSync() }
            } finally {
                _ui.update { it.copy(verifying = false) }
            }
        }
    }

    /**
     * On resume: confirm the link, refresh device info, and push the time only.
     * The (heavier) health-records drain is left to a manual "Sync health
     * records" tap — we don't want it running automatically on every return.
     */
    private suspend fun verifyAndSync() {
        // Patient probe first: the watch is often briefly slow to answer right
        // after waking, so don't drop a perfectly good link on the first miss.
        if (!probe()) {
            // Genuinely unresponsive: force a clean reconnect, then re-probe.
            repo.disconnect()
            repo.connectionState.first { it == ConnectionState.READY }
            if (!probe()) return  // still nothing — leave it; auto-reconnect runs
        }
        tryRefresh()
        runCatching { repo.syncTime() }
    }

    /** A few quick liveness checks, allowing for a slow post-wake response. */
    private suspend fun probe(): Boolean {
        repeat(probeAttempts) {
            if (repo.isResponsive()) return true
            delay(probeRetryDelay)
        }
        return false
    }

    /**
     * While connected, automatically sync time + device info + health records
     * on connect and then every 60 minutes. Cancelled on disconnect.
     */
    private fun startAutoSync() {
        if (autoSyncJob?.isActive == true) return
        autoSyncJob = viewModelScope.launch {
            var first = true
            while (isActive) {
                fullSync(announce = false)
                if (first) {
                    setMessage("Synced with watch.")
                    first = false
                }
                delay(autoSyncInterval)
            }
        }
    }

    /** One full pass: push time, refresh device info, drain records. */
    private suspend fun fullSync(announce: Boolean) {
        runCatching { repo.syncTime() }
        refreshInternal()
        // recover=false: this runs on autoSyncJob, which a reconnect would
        // cancel from under us. The periodic loop just retries next cycle.
        doRecordsSync(announce, recover = false)
    }

    private fun stopAutoSync() {
        autoSyncJob?.cancel()
        autoSyncJob = null
    }

    fun refresh() = viewModelScope.launch { refreshInternal() }

    private suspend fun refreshInternal() {
        if (!tryRefresh()) setMessage("Watch not responding — retrying…")
    }

    /**
     * Read device info, keeping the last-known value on a per-field miss so one
     * slow read can't blank the whole card. Returns true only if the watch
     * actually answered a core read (firmware or step goal) — the signal that
     * the link is genuinely alive, not just superficially connected.
     */
    private suspend fun tryRefresh(): Boolean {
        val fw = runCatching { repo.firmware() }.getOrNull()
        val goal = runCatching { repo.stepGoal() }.getOrNull()
        val live = runCatching { repo.liveValue() }.getOrNull()
        val nm = runCatching { repo.name() }.getOrNull()
        _ui.update {
            it.copy(
                firmware = fw ?: it.firmware,
                stepGoal = goal ?: it.stepGoal,
                liveValue = live ?: it.liveValue,
                name = nm ?: it.name,
            )
        }
        return fw != null || goal != null
    }

    fun syncTimeNow() = action { repo.syncTime(); "Time synced." }

    /** Drain the watch's log, merge into 30-day storage, and refresh history. */
    fun syncRecords() = viewModelScope.launch { doRecordsSync(announce = true) }

    private suspend fun doRecordsSync(announce: Boolean, recover: Boolean = true) =
        syncMutex.withLock {
            // Serialized (not skipped): a resume-verify must be able to await an
            // already-running auto-sync rather than racing past it.
            _ui.update { it.copy(syncing = true) }
            try {
                val recs = try {
                    repo.syncRecords()
                } catch (e: LinkDeadException) {
                    // Only a genuinely dead link (not a mid-drain hiccup) forces
                    // a clean reconnect and one retry before giving up.
                    if (recover && reconnectFresh()) repo.syncRecords() else throw e
                }
                val all = withContext(Dispatchers.IO) { store.merge(recs) }
                applyRecords(all)
                if (announce) {
                    setMessage("Synced ${recs.size} records (${all.size} kept, 30 days).")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (announce) setMessage("Records sync failed: ${e.message}")
            } finally {
                _ui.update { it.copy(syncing = false) }
            }
        }

    /** Drop the link and wait for auto-reconnect to bring up a fresh one. */
    private suspend fun reconnectFresh(): Boolean {
        repo.disconnect()
        return withTimeoutOrNull(verifyBudget) {
            repo.connectionState.first { it == ConnectionState.READY }
            true
        } == true
    }

    /** Split stored records into per-type history and daily summaries. */
    private fun applyRecords(all: List<OlleeProtocol.Record>) {
        // Drop physically-impossible readings (corrupt/sentinel records) so a
        // single garbage value can't skew a day's min/max (e.g. 14854 °C).
        val temp = all.filter {
            it.type == OlleeProtocol.REC_TEMPERATURE && it.celsius in plausibleTempC
        }.sortedByDescending { it.tStart }
        val hr = all.filter {
            it.type == OlleeProtocol.REC_HEART_RATE && it.bpm in plausibleBpm
        }.sortedByDescending { it.tStart }
        val steps = all.filter { it.type == OlleeProtocol.REC_STEPS }
        val stepDays = dailySteps(steps)
        val todayKey = startOfLocalDay(System.currentTimeMillis() / 1000)
        val today = stepDays.firstOrNull { it.dayEpoch == todayKey }?.steps ?: 0
        _ui.update {
            it.copy(
                temperatureLog = temp, hrLog = hr,
                tempDaily = dailyTemp(temp), hrDaily = dailyHr(hr),
                stepDaily = stepDays, todaySteps = today,
            )
        }
    }

    private fun dailyTemp(list: List<OlleeProtocol.Record>): List<DailyTemp> =
        list.groupBy { startOfLocalDay(it.tStart) }
            .map { (day, recs) ->
                val c = recs.map { it.celsius }
                DailyTemp(dayFmt.format(Date(day * 1000)), day, c.min(), c.max(), c.average())
            }
            .sortedByDescending { it.dayEpoch }

    private fun dailyHr(list: List<OlleeProtocol.Record>): List<DailyHr> =
        list.groupBy { startOfLocalDay(it.tStart) }
            .map { (day, recs) ->
                val b = recs.map { it.bpm }
                DailyHr(dayFmt.format(Date(day * 1000)), day,
                    b.min(), b.max(), b.average().toInt(), b.size)
            }
            .sortedByDescending { it.dayEpoch }

    private fun dailySteps(list: List<OlleeProtocol.Record>): List<DailyStep> =
        list.groupBy { startOfLocalDay(it.tStart) }
            .map { (day, recs) ->
                DailyStep(dayFmt.format(Date(day * 1000)), day, recs.sumOf { it.value })
            }
            .sortedByDescending { it.dayEpoch }

    private fun startOfLocalDay(epochSec: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = epochSec * 1000
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
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
            } catch (e: Exception) {
                setMessage("Error: ${e.message}")
            }
        }
    }

    fun clearMessage() = _ui.update { it.copy(message = null) }
    private fun setMessage(m: String) = _ui.update { it.copy(message = m) }
    // No onCleared() release: the connection is app-scoped and kept alive by the
    // foreground service; it ends on explicit disconnect, not Activity teardown.
}
