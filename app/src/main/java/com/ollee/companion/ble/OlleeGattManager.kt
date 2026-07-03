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
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

enum class ConnectionState { DISCONNECTED, CONNECTING, READY }

/**
 * Minimal raw-GATT manager for the Ollee watch over Nordic UART Service.
 *
 * Handles: connect + service discovery + enabling TX notifications, a serialized
 * write queue (Android requires one outstanding GATT op at a time), frame
 * reassembly, and suspend request/response keyed by the watch's response cmd.
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
    private val requestMutex = Mutex()  // one request round-trip in flight at a time

    private var readyDeferred: CompletableDeferred<Unit>? = null
    private var pendingWrite: CompletableDeferred<Unit>? = null
    private val waiters = ConcurrentHashMap<Int, CompletableDeferred<OlleeProtocol.Frame>>()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var keepAliveJob: Job? = null
    private val keepAliveInterval = 30.seconds

    @Volatile private var keepAliveSuppressed = false

    private val callback = object : android.bluetooth.BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            // Events from a stale client (a previous connection whose callback
            // fires after a new connect started) must not touch current state;
            // just make sure the zombie is fully closed so it can't keep
            // auto-reconnecting in the background and steal the link.
            if (gatt != null && g !== gatt) {
                g.close()
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                readyDeferred?.takeIf { !it.isCompleted }
                    ?.completeExceptionally(IOException("disconnected (status=$status)"))
                cleanup()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                readyDeferred?.takeIf { !it.isCompleted }
                    ?.completeExceptionally(IOException("service discovery failed status=$status"))
                return
            }
            val tx = g.getService(OlleeProtocol.NUS_SERVICE)
                ?.getCharacteristic(OlleeProtocol.NUS_TX)
            if (tx == null) {
                readyDeferred?.completeExceptionally(IOException("NUS not found"))
                return
            }
            g.setCharacteristicNotification(tx, true)
            val cccd = tx.getDescriptor(OlleeProtocol.CCCD)
            writeCccdEnable(g, cccd)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid != OlleeProtocol.CCCD) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // Notifications never got enabled — the link would be connected
                // but mute (every request times out). Fail the connect so the
                // caller retries with a fresh link instead.
                readyDeferred?.takeIf { !it.isCompleted }
                    ?.completeExceptionally(IOException("enable notifications failed status=$status"))
                return
            }
            _state.value = ConnectionState.READY
            readyDeferred?.takeIf { !it.isCompleted }?.complete(Unit)
            startKeepAlive()
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int
        ) {
            val d = pendingWrite
            pendingWrite = null
            if (status == BluetoothGatt.GATT_SUCCESS) d?.complete(Unit)
            else d?.completeExceptionally(IOException("write failed status=$status"))
        }

        // Android 13+ (API 33) value-carrying callback.
        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray
        ) = handleNotify(value)

        // Legacy callback for API <= 32.
        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            handleNotify(c.value ?: ByteArray(0))
        }
    }

    private fun handleNotify(value: ByteArray) {
        for (frame in reasm.feed(value)) {
            frames.tryEmit(frame)
            waiters.remove(frame.cmd)?.complete(frame)
        }
    }

    /**
     * Connect to a known device by MAC and wait until READY, searching for up
     * to [timeoutMs] (default 1 minute). autoConnect=true lets the OS keep
     * looking until the watch appears within the window. On timeout or failure
     * the state is reset to DISCONNECTED so the UI shows "Connect watch" again.
     */
    suspend fun connect(address: String, timeoutMs: Long = 60_000) {
        // Ignore if a connection is already in progress or established, so a
        // second connect() can't overwrite (and leak) the active gatt.
        if (_state.value != ConnectionState.DISCONNECTED) return
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val device = mgr.adapter.getRemoteDevice(address)
        _state.value = ConnectionState.CONNECTING
        reasm.reset()
        val ready = CompletableDeferred<Unit>()
        readyDeferred = ready
        gatt = device.connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE)
        try {
            // connectGatt returns null when the adapter is off — fail fast
            // instead of burning the whole search window.
            if (gatt == null) throw IOException("connectGatt failed (is Bluetooth on?)")
            if (withTimeoutOrNull(timeoutMs.milliseconds) { ready.await() } == null) {
                throw IOException("no watch found within ${timeoutMs / 1000}s")
            }
        } catch (e: Exception) {
            disconnect()
            _state.value = ConnectionState.DISCONNECTED
            throw e
        }
    }

    fun disconnect() {
        gatt?.disconnect()
        cleanup()
    }

    private fun cleanup() {
        stopKeepAlive()
        // Always close(), on every teardown path. An unclosed BluetoothGatt
        // holds one of the system's ~32 GATT client slots and — because we
        // connect with autoConnect=true — keeps reconnecting in the background
        // as a zombie client that races (and corrupts) the next connection.
        // Leaking one per background drop eventually exhausts the slots and
        // connectGatt starts failing outright.
        gatt?.close()
        gatt = null
        waiters.values.forEach { it.takeIf { d -> !d.isCompleted }
            ?.completeExceptionally(IOException("disconnected")) }
        waiters.clear()
        // Calling close() right after disconnect() can suppress the
        // onConnectionStateChange callback, so set the state here too — this is
        // the single teardown path for both manual disconnect and dropped links.
        _state.value = ConnectionState.DISCONNECTED
    }

    /**
     * Keep the link from going idle: poll the watch's live value (the same
     * lightweight read the official app uses) every [keepAliveInterval] (30s)
     * while connected. Failures are ignored — a missed poll is not treated as a
     * dropped link. The job is cancelled by cleanup() when the link goes down.
     */
    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (isActive) {
                delay(keepAliveInterval)
                if (_state.value != ConnectionState.READY) break
                // Don't poll mid records-drain — a CMD_LIVE between fetches can
                // wedge the watch so it stops answering 0x28.
                if (keepAliveSuppressed) continue
                // Keep the link warm. A missed poll is NOT treated as a dead
                // link — that caused false disconnects (and a jarring reconnect
                // flash) on a merely-flaky poll. Genuine drops still arrive via
                // onConnectionStateChange; staleness is caught on resume-verify
                // and self-healed by the manual records sync.
                runCatching { request(OlleeProtocol.CMD_LIVE) }
            }
        }
    }

    private fun stopKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = null
    }

    /**
     * Run [block] as an uninterrupted burst:
     *  - the keep-alive poll is paused, so a CMD_LIVE can't slip between the
     *    requests inside (it wedges a records drain), and
     *  - the connection is bumped to high priority (short connection interval)
     *    for the duration. Watches negotiate long intervals to save power, and
     *    each request is a full write-with-response round trip — at a long
     *    interval a records drain crawls and trips fetch timeouts.
     * Both are restored on exit.
     */
    suspend fun <T> burst(block: suspend () -> T): T {
        keepAliveSuppressed = true
        gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        try {
            return block()
        } finally {
            gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
            keepAliveSuppressed = false
        }
    }

    /** Send a request and await the matching response (cmd + 0x20). */
    suspend fun request(
        cmd: Int, payload: ByteArray = ByteArray(0),
        timeoutMs: Long = 2_000, retries: Int = 1
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
            lastError = IOException("no reply to cmd 0x${cmd.toString(16)}")
        }
        throw lastError ?: IOException("request failed")
    }

    /** Fire a command that performs an action but sends no reply (e.g. set time). */
    suspend fun send(frame: ByteArray) = writeFrame(frame)

    /** Write a full frame as serialized, response-confirmed BLE chunks. */
    private suspend fun writeFrame(frame: ByteArray) = writeMutex.withLock {
        val g = gatt ?: throw IOException("not connected")
        val rx = g.getService(OlleeProtocol.NUS_SERVICE)
            ?.getCharacteristic(OlleeProtocol.NUS_RX)
            ?: throw IOException("RX characteristic missing")
        for (piece in OlleeProtocol.chunk(frame)) {
            val done = CompletableDeferred<Unit>()
            pendingWrite = done
            writeCharacteristicCompat(g, rx, piece)
            try {
                withTimeout(3_000.milliseconds) { done.await() }
            } finally {
                // On timeout, don't leave the stale deferred registered — a
                // late ack for THIS write must not complete the NEXT chunk's
                // deferred (that would interleave frame bytes on the wire).
                if (pendingWrite === done) pendingWrite = null
            }
        }
    }

    private fun writeCharacteristicCompat(
        g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val rc = g.writeCharacteristic(
                c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
            if (rc != BluetoothStatusCodes.SUCCESS) {
                pendingWrite?.completeExceptionally(IOException("writeCharacteristic rc=$rc"))
                pendingWrite = null
            }
        } else {
            @Suppress("DEPRECATION")
            run {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                c.value = value
                if (!g.writeCharacteristic(c)) {
                    pendingWrite?.completeExceptionally(IOException("writeCharacteristic failed"))
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
