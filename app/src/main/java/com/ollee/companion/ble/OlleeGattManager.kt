package com.ollee.companion.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

enum class ConnectionState { DISCONNECTED, CONNECTING, READY }

/**
 * Minimal raw-GATT manager for the Ollee watch over Nordic UART Service.
 */
@SuppressLint("MissingPermission")
class OlleeGattManager(private val context: Context) {

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** Every decoded frame from the watch (responses and any unsolicited push). */
    val frames = MutableSharedFlow<OlleeProtocol.Frame>(extraBufferCapacity = 64)

    private var gatt: BluetoothGatt? = null
    private val reasm = FrameReassembler()
    private val writeMutex = Mutex()
    private val requestMutex = Mutex()

    private var readyDeferred: CompletableDeferred<Unit>? = null
    private var pendingWrite: CompletableDeferred<Unit>? = null
    private val waiters = ConcurrentHashMap<Int, CompletableDeferred<OlleeProtocol.Frame>>()

    // Dedicated single-threaded dispatcher for all GATT operations and callbacks.
    // This prevents main-thread blocking and ensures serialized access to the BLE stack.
    private val gattDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    // Separate scope for GATT operations (background) and response processing (high priority).
    private val scope = CoroutineScope(gattDispatcher + SupervisorJob())
    private val notifyScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private var keepAliveJob: Job? = null
    private val keepAliveInterval = 12.seconds
    private val keepAliveTimeout = 5.seconds

    @Volatile private var keepAliveSuppressed = false

    private val callback = object : android.bluetooth.BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            // Process state changes on the Main dispatcher. The Main thread
            // is less likely to be throttled by the OS while a foreground 
            // service is active.
            CoroutineScope(Dispatchers.Main).launch {
                if ((gatt != null) && (g !== gatt)) {
                    g.close()
                    return@launch
                }
                
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    // Larger MTU can help with stability and faster data transfer.
                    g.requestMtu(256)
                    delay(600.milliseconds)
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val msg = if (status != 0) "disconnected (status=$status)" else "disconnected"
                    readyDeferred?.takeIf { !it.isCompleted }
                        ?.completeExceptionally(IOException(msg))
                    cleanup()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            // MTU change is informational for us as our protocol chunks at 20 bytes.
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            CoroutineScope(Dispatchers.Main).launch {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    readyDeferred?.completeExceptionally(IOException("discovery failed $status"))
                    return@launch
                }
                val tx = g.getService(OlleeProtocol.NUS_SERVICE)
                    ?.getCharacteristic(OlleeProtocol.NUS_TX)
                if (tx == null) {
                    readyDeferred?.completeExceptionally(IOException("NUS not found"))
                    return@launch
                }
                g.setCharacteristicNotification(tx, true)
                val cccd = tx.getDescriptor(OlleeProtocol.CCCD)
                writeCccdEnable(g, cccd)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            CoroutineScope(Dispatchers.Main).launch {
                if (d.uuid != OlleeProtocol.CCCD) return@launch
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    readyDeferred?.completeExceptionally(IOException("CCCD failed $status"))
                    return@launch
                }
                _state.value = ConnectionState.READY
                readyDeferred?.complete(Unit)
                
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)

                startKeepAlive()
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int,
        ) {
            scope.launch {
                val d = pendingWrite
                pendingWrite = null
                if (status == BluetoothGatt.GATT_SUCCESS) d?.complete(Unit)
                else d?.completeExceptionally(IOException("write failed $status"))
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray,
        ) = handleNotify(value)

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            handleNotify(c.value ?: ByteArray(0))
        }
    }

    private fun handleNotify(value: ByteArray) {
        // Process incoming notifications on a separate, high-priority scope.
        // If we process these on the same gattDispatcher used for writes,
        // a slow write or background throttling can delay the response 
        // processing enough to trigger a request timeout.
        notifyScope.launch {
            for (frame in reasm.feed(value)) {
                if (!frame.crcOk) continue
                frames.tryEmit(frame)
                waiters.remove(frame.cmd)?.complete(frame)
            }
        }
    }

    /**
     * Connect to a known device by MAC and wait until READY.
     */
    suspend fun connect(address: String, autoConnect: Boolean = false, timeoutMs: Long = 120_000) {
        // If we are already connecting or connected, don't interrupt unless this 
        // is a forced reset. status 147 often comes from multiple overlapping
        // connectGatt calls to the same address.
        if (_state.value != ConnectionState.DISCONNECTED) return
        
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val device = mgr.adapter.getRemoteDevice(address)
        _state.value = ConnectionState.CONNECTING
        reasm.reset()
        
        val ready = CompletableDeferred<Unit>()
        readyDeferred = ready
        
        // Always initiate connectGatt on our background dispatcher.
        withContext(gattDispatcher) {
            gatt = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
        }
        
        try {
            if (gatt == null) throw IOException("connectGatt returned null")
            if (withTimeoutOrNull(timeoutMs.milliseconds) { ready.await() } == null) {
                throw IOException("connection timed out after ${timeoutMs / 1000}s")
            }
        } catch (e: Exception) {
            disconnect()
            throw e
        }
    }

    suspend fun disconnect() {
        // Execute cleanup on our serialized gattDispatcher and wait for it.
        withContext(gattDispatcher) {
            gatt?.disconnect()
            cleanup()
        }
    }

    private fun cleanup() {
        stopKeepAlive()
        gatt?.close()
        gatt = null
        waiters.values.forEach { 
            if (!it.isCompleted) it.completeExceptionally(IOException("disconnected")) 
        }
        waiters.clear()
        _state.value = ConnectionState.DISCONNECTED
    }

    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            var failures = 0
            while (isActive) {
                delay(keepAliveInterval)
                if (_state.value != ConnectionState.READY) break
                if (keepAliveSuppressed) {
                    failures = 0
                    continue
                }
                
                val result = runCatching { 
                    request(OlleeProtocol.CMD_LIVE, timeoutMs = keepAliveTimeout.inWholeMilliseconds) 
                }
                
                if (result.isSuccess) {
                    failures = 0
                    // Periodically "poke" the radio to ensure it hasn't 
                    // drifted into an aggressive power-saving mode.
                    gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                } else {
                    failures++
                    // If we miss 5 polls in a row (~1 minute), force a decise
                    // teardown so the background reconnect logic can start fresh.
                    if (failures >= 5) {
                        disconnect()
                        break
                    }
                }
            }
        }
    }

    private fun stopKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = null
    }

    suspend fun <T> burst(block: suspend () -> T): T {
        keepAliveSuppressed = true
        withContext(gattDispatcher) {
            gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        }
        return try {
            block()
        } finally {
            withContext(gattDispatcher) {
                gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
            }
            keepAliveSuppressed = false
        }
    }

    /** Send a request and await the matching response (cmd + 0x20). */
    suspend fun request(
        cmd: Int, payload: ByteArray = ByteArray(0),
        timeoutMs: Long = 5_000, retries: Int = 1,
    ): OlleeProtocol.Frame = requestMutex.withLock {
        val respCmd = cmd + OlleeProtocol.RESP_OFFSET
        val frame = OlleeProtocol.buildFrame(cmd, payload)
        var lastError: Exception? = null
        
        repeat(retries + 1) {
            val deferred = CompletableDeferred<OlleeProtocol.Frame>()
            waiters[respCmd] = deferred
            writeFrame(frame)
            val result = withTimeoutOrNull(timeoutMs.milliseconds) { deferred.await() }
            if (result != null) return@withLock result
            
            waiters.remove(respCmd)
            lastError = IOException("timeout waiting for 0x${respCmd.toString(16)}")
        }
        throw lastError ?: IOException("request failed")
    }

    suspend fun send(frame: ByteArray) = writeFrame(frame)

    private suspend fun writeFrame(frame: ByteArray) = writeMutex.withLock {
        val g = gatt ?: throw IOException("not connected")
        val rx = g.getService(OlleeProtocol.NUS_SERVICE)
            ?.getCharacteristic(OlleeProtocol.NUS_RX)
            ?: throw IOException("RX missing")
            
        for (piece in OlleeProtocol.chunk(frame)) {
            val done = CompletableDeferred<Unit>()
            pendingWrite = done
            withContext(gattDispatcher) {
                writeCharacteristicCompat(g, rx, piece)
            }
            try {
                withTimeout(3_000.milliseconds) { done.await() }
            } finally {
                if (pendingWrite === done) pendingWrite = null
            }
        }
    }

    private fun writeCharacteristicCompat(
        g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val rc = g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            if (rc != BluetoothStatusCodes.SUCCESS) {
                pendingWrite?.completeExceptionally(IOException("write failed rc=$rc"))
                pendingWrite = null
            }
        } else {
            @Suppress("DEPRECATION")
            run {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                c.value = value
                if (!g.writeCharacteristic(c)) {
                    pendingWrite?.completeExceptionally(IOException("write failed"))
                    pendingWrite = null
                }
            }
        }
    }

    private fun writeCccdEnable(g: BluetoothGatt, d: BluetoothGattDescriptor?) {
        d ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            run {
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(d)
            }
        }
    }
}
