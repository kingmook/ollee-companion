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
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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
    // All mutable transport state, including frame reassembly, lives on this
    // dispatcher. Bluetooth callbacks may arrive on arbitrary binder threads.
    private val scope = CoroutineScope(gattDispatcher + SupervisorJob())
    
    private var keepAliveJob: Job? = null
    private val keepAliveInterval = 12.seconds
    private val keepAliveTimeout = 5.seconds

    @Volatile private var keepAliveSuppressed = false

    private val callback = object : android.bluetooth.BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            scope.launch {
                Log.i(TAG, "GATT state changed; status=$status newState=$newState")
                if (!isActiveGatt(g)) {
                    g.close()
                    return@launch
                }
                
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    // Larger MTU can help with stability and faster data transfer.
                    g.requestMtu(256)
                    delay(600.milliseconds)
                    if (!g.discoverServices()) {
                        failConnection(g, IOException("service discovery failed to start"))
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val msg = if (status != 0) "disconnected (status=$status)" else "disconnected"
                    readyDeferred?.takeIf { !it.isCompleted }
                        ?.completeExceptionally(IOException(msg))
                    cleanup()
                } else if (status != BluetoothGatt.GATT_SUCCESS) {
                    failConnection(g, IOException("connection failed (status=$status)"))
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            // MTU change is informational for us as our protocol chunks at 20 bytes.
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            scope.launch {
                if (!isActiveGatt(g)) return@launch
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    failConnection(g, IOException("service discovery failed (status=$status)"))
                    return@launch
                }
                val tx = g.getService(OlleeProtocol.NUS_SERVICE)
                    ?.getCharacteristic(OlleeProtocol.NUS_TX)
                if (tx == null) {
                    failConnection(g, IOException("Nordic UART TX characteristic not found"))
                    return@launch
                }
                if (!g.setCharacteristicNotification(tx, true)) {
                    failConnection(g, IOException("enabling local notifications failed"))
                    return@launch
                }
                val cccd = tx.getDescriptor(OlleeProtocol.CCCD)
                if (cccd == null) {
                    failConnection(g, IOException("Nordic UART notification descriptor not found"))
                    return@launch
                }
                writeCccdEnable(g, cccd)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            scope.launch {
                if (!isActiveGatt(g)) return@launch
                if (d.uuid != OlleeProtocol.CCCD) return@launch
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    failConnection(g, IOException("notification descriptor write failed (status=$status)"))
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
                if (!isActiveGatt(g)) return@launch
                val d = pendingWrite
                pendingWrite = null
                if (status == BluetoothGatt.GATT_SUCCESS) d?.complete(Unit)
                else d?.completeExceptionally(IOException("write failed $status"))
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray,
        ) = handleNotify(g, value)

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            handleNotify(g, c.value ?: ByteArray(0))
        }
    }

    private fun handleNotify(source: BluetoothGatt, value: ByteArray) {
        // A single dispatcher preserves BLE notification order and protects the
        // mutable reassembler. Copy because pre-33 characteristic values may be
        // backed by a buffer that Android reuses after the callback returns.
        val bytes = value.copyOf()
        scope.launch {
            if (!isActiveGatt(source)) return@launch
            for (frame in reasm.feed(bytes)) {
                if (!frame.crcOk) continue
                frames.tryEmit(frame)
                waiters.remove(frame.cmd)?.complete(frame)
            }
        }
    }

    private fun isActiveGatt(candidate: BluetoothGatt): Boolean = candidate === gatt

    private fun failConnection(source: BluetoothGatt, error: IOException) {
        if (!isActiveGatt(source)) return
        readyDeferred?.takeIf { !it.isCompleted }?.completeExceptionally(error)
        cleanup()
    }

    /**
     * Connect to a known device by MAC and wait until READY.
     */
    suspend fun connect(address: String, autoConnect: Boolean = false, timeoutMs: Long = 120_000) {
        // Ensure any previous connection is fully torn down before starting a new one.
        if (_state.value != ConnectionState.DISCONNECTED) {
            disconnect()
        }
        
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
        pendingWrite?.takeIf { !it.isCompleted }
            ?.completeExceptionally(IOException("disconnected"))
        pendingWrite = null
        readyDeferred = null
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
                    Log.w(TAG, "BLE keep-alive failed ($failures/5)", result.exceptionOrNull())
                    // If we miss 5 polls in a row (~1 minute), force a decisive
                    // teardown so the background reconnect logic can start fresh.
                    if (failures >= 5) {
                        Log.w(TAG, "BLE keep-alive failure threshold reached; disconnecting")
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
            val result = try {
                writeFrame(frame)
                withTimeoutOrNull(timeoutMs.milliseconds) { deferred.await() }
            } finally {
                waiters.remove(respCmd, deferred)
            }
            if (result != null) return@withLock result
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

    private fun writeCccdEnable(g: BluetoothGatt, d: BluetoothGattDescriptor) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val rc = g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (rc != BluetoothStatusCodes.SUCCESS) {
                failConnection(g, IOException("notification descriptor write failed to start (rc=$rc)"))
            }
        } else {
            @Suppress("DEPRECATION")
            run {
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (!g.writeDescriptor(d)) {
                    failConnection(g, IOException("notification descriptor write failed to start"))
                }
            }
        }
    }

    companion object {
        private const val TAG = "OlleeGattManager"
    }
}
