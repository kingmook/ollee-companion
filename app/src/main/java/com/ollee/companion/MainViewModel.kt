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
import com.ollee.companion.ble.OlleeGattManager
import com.ollee.companion.ble.OlleeProtocol
import com.ollee.companion.ble.OlleeRepository
import com.ollee.companion.data.RecordStore
import com.ollee.companion.feature.SunCalculator
import com.ollee.companion.feature.SunTimes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.minutes

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
    val temperatureLog: List<OlleeProtocol.Record> = emptyList(),
    val hrLog: List<OlleeProtocol.Record> = emptyList(),
    val tempDaily: List<DailyTemp> = emptyList(),
    val hrDaily: List<DailyHr> = emptyList(),
    val stepDaily: List<DailyStep> = emptyList(),
    val todaySteps: Int = 0,
    val message: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = OlleeRepository(OlleeGattManager(app))
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
                        startAutoSync()
                    }
                    else -> stopAutoSync()
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
        pendingAddress = address
        stopScan()
        viewModelScope.launch {
            runCatching { repo.connect(address) }
                .onFailure { setMessage("Connect failed: ${it.message}") }
        }
    }

    fun disconnect() = repo.disconnect()

    /**
     * While connected, automatically sync time + device info + health records
     * on connect and then every 60 minutes. Cancelled on disconnect.
     */
    private fun startAutoSync() {
        if (autoSyncJob?.isActive == true) return
        autoSyncJob = viewModelScope.launch {
            var first = true
            while (isActive) {
                runCatching { repo.syncTime() }
                refreshInternal()
                doRecordsSync(announce = false)
                if (first) {
                    setMessage("Synced with watch.")
                    first = false
                }
                delay(autoSyncInterval)
            }
        }
    }

    private fun stopAutoSync() {
        autoSyncJob?.cancel()
        autoSyncJob = null
    }

    fun refresh() = viewModelScope.launch { refreshInternal() }

    private suspend fun refreshInternal() {
        runCatching {
            val fw = repo.firmware()
            val goal = repo.stepGoal()
            val live = repo.liveValue()
            val nm = runCatching { repo.name() }.getOrNull()
            _ui.update { it.copy(firmware = fw, stepGoal = goal, liveValue = live, name = nm) }
        }.onFailure { setMessage("Read failed: ${it.message}") }
    }

    fun syncTimeNow() = action { repo.syncTime(); "Time synced." }

    /** Drain the watch's log, merge into 30-day storage, and refresh history. */
    fun syncRecords() = viewModelScope.launch { doRecordsSync(announce = true) }

    private suspend fun doRecordsSync(announce: Boolean) {
        if (_ui.value.syncing) return  // avoid overlapping (manual vs auto) syncs
        _ui.update { it.copy(syncing = true) }
        runCatching { repo.syncRecords() }
            .onSuccess { recs ->
                val all = withContext(Dispatchers.IO) { store.merge(recs) }
                applyRecords(all)
                _ui.update { it.copy(syncing = false) }
                if (announce) {
                    setMessage("Synced ${recs.size} records (${all.size} kept, 30 days).")
                }
            }
            .onFailure { e ->
                _ui.update { it.copy(syncing = false) }
                if (announce) setMessage("Records sync failed: ${e.message}")
            }
    }

    /** Split stored records into per-type history and daily summaries. */
    private fun applyRecords(all: List<OlleeProtocol.Record>) {
        val temp = all.filter { it.type == OlleeProtocol.REC_TEMPERATURE }
            .sortedByDescending { it.tStart }
        val hr = all.filter { it.type == OlleeProtocol.REC_HEART_RATE }
            .sortedByDescending { it.tStart }
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

    override fun onCleared() {
        super.onCleared()
        repo.release()  // disconnect + close GATT + cancel the manager's scope
    }
}
