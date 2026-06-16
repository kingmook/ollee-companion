package com.ollee.companion

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.location.LocationManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ollee.companion.ble.CaptureNeeded
import com.ollee.companion.ble.ConnectionState
import com.ollee.companion.ble.OlleeGattManager
import com.ollee.companion.ble.OlleeProtocol
import com.ollee.companion.ble.OlleeRepository
import com.ollee.companion.feature.SunCalculator
import com.ollee.companion.feature.SunTimes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ScannedDevice(val name: String, val address: String)

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
    val message: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = OlleeRepository(OlleeGattManager(app))

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** Pre-filled with the watch discovered during reverse-engineering. */
    val defaultAddress = "00:80:E1:26:08:5D"

    init {
        viewModelScope.launch {
            repo.connectionState.collect { state ->
                _ui.update { it.copy(connection = state) }
                if (state == ConnectionState.READY) onReady()
            }
        }
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
        stopScan()
        viewModelScope.launch {
            runCatching { repo.connect(address) }
                .onFailure { setMessage("Connect failed: ${it.message}") }
        }
    }

    fun disconnect() = repo.disconnect()

    /** On connect: automatic time sync, then read device info. */
    private suspend fun onReady() {
        runCatching { repo.syncTime() }
            .onSuccess { setMessage("Time synced automatically.") }
            .onFailure { setMessage("Auto-sync failed: ${it.message}") }
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        runCatching {
            val fw = repo.firmware()
            val goal = repo.stepGoal()
            val live = repo.liveValue()
            val nm = runCatching { repo.name() }.getOrNull()
            _ui.update { it.copy(firmware = fw, stepGoal = goal, liveValue = live, name = nm) }
        }.onFailure { setMessage("Read failed: ${it.message}") }
    }

    fun syncTimeNow() = action { repo.syncTime(); "Time synced." }

    /** Drain the watch's health log and split into temperature + HR history. */
    fun syncRecords() = viewModelScope.launch {
        _ui.update { it.copy(syncing = true) }
        runCatching { repo.syncRecords() }
            .onSuccess { recs ->
                val temp = recs.filter { it.type == OlleeProtocol.REC_TEMPERATURE }
                    .sortedByDescending { it.tStart }
                val hr = recs.filter { it.type == OlleeProtocol.REC_HEART_RATE }
                    .sortedByDescending { it.tStart }
                _ui.update { it.copy(temperatureLog = temp, hrLog = hr, syncing = false) }
                setMessage("Synced ${recs.size} records.")
            }
            .onFailure {
                _ui.update { it.copy(syncing = false) }
                setMessage("Records sync failed: ${it.message}")
            }
    }

    // Capture-pending feature actions surface a friendly "how to capture" note.
    fun setStepGoal(goal: Int) = action { repo.setStepGoal(goal); "" }

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
            } catch (e: CaptureNeeded) {
                setMessage("${e.feature}: not decoded yet. ${e.howToCapture}")
            } catch (e: Exception) {
                setMessage("Error: ${e.message}")
            }
        }
    }

    fun clearMessage() = _ui.update { it.copy(message = null) }
    private fun setMessage(m: String) = _ui.update { it.copy(message = m) }
}
