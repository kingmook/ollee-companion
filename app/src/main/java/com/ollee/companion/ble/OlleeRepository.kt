package com.ollee.companion.ble

import com.ollee.companion.feature.AlarmConfig
import java.nio.charset.StandardCharsets

/**
 * High-level Ollee API built on [OlleeGattManager].
 *
 * Methods backed by a decoded command are implemented. Methods whose command
 * bytes are not yet known throw [CaptureNeeded] with the exact action to record
 * in the official app (capture it, run ../ollee-ble/parse_capture.py, and the
 * bytes drop straight into these stubs).
 */
class OlleeRepository(val gatt: OlleeGattManager) {

    val connectionState get() = gatt.state

    suspend fun connect(address: String) = gatt.connect(address)
    fun disconnect() = gatt.disconnect()

    // --- Implemented (protocol known) --------------------------------------

    /** Firmware / serial string, e.g. "DEADBEEF01.05.0000.01.07DEADBEEF". */
    suspend fun firmware(): String =
        gatt.request(OlleeProtocol.CMD_INFO).payload
            .takeWhile { it.toInt() != 0 }.toByteArray()
            .toString(StandardCharsets.US_ASCII)

    suspend fun name(): String =
        gatt.request(OlleeProtocol.CMD_NAME).payload
            .toString(StandardCharsets.US_ASCII).trim()

    /** Daily step goal (uint32, big-endian). */
    suspend fun stepGoal(): Int =
        gatt.request(OlleeProtocol.CMD_STEP_GOAL).payload.beInt(0, 4)

    /** Live value polled by the app (battery/steps — exact meaning TBD). */
    suspend fun liveValue(): Int =
        gatt.request(OlleeProtocol.CMD_LIVE).payload.beInt(0, 2)

    suspend fun statusRaw(): ByteArray = gatt.request(OlleeProtocol.CMD_STATUS).payload

    /** Automatic sync: push the phone's current time + timezone to the watch. */
    suspend fun syncTime() = gatt.send(OlleeProtocol.setTimeFrame())

    /** Set the watch clock to a specific instant / timezone (WorldTime). */
    suspend fun setTime(epochSeconds: Long, tzOffsetSeconds: Int) =
        gatt.send(OlleeProtocol.setTimeFrame(epochSeconds, tzOffsetSeconds))

    /**
     * Drain the watch's health/activity log: ask for the count (0x27), fetch
     * each record (0x28), then acknowledge (0x2d). Returns steps, hourly
     * temperature, and heart-rate records.
     */
    suspend fun syncRecords(): List<OlleeProtocol.Record> {
        val count = gatt.request(OlleeProtocol.CMD_REC_COUNT).payload.beInt(0, 4)
        val records = ArrayList<OlleeProtocol.Record>(count)
        repeat(count) {
            val frame = gatt.request(OlleeProtocol.CMD_REC_FETCH, timeoutMs = 3_000)
            OlleeProtocol.parseRecord(frame.payload)?.let { records.add(it) }
        }
        runCatching { gatt.request(OlleeProtocol.CMD_SYNC_DONE, timeoutMs = 1_500) }
        return records
    }

    // --- Capture-pending (command bytes unknown) ---------------------------

    suspend fun setStepGoal(goal: Int): Nothing =
        throw CaptureNeeded("step goal write",
            "In the Ollee app, change the daily step goal and save.")

    suspend fun setAlarm(alarm: AlarmConfig): Nothing =
        throw CaptureNeeded("alarm set",
            "Create/enable an alarm with days + chime + snooze, and save.")

    suspend fun setHourlyChime(enabled: Boolean): Nothing =
        throw CaptureNeeded("hourly chime",
            "Toggle the hourly beep/chime setting.")

    suspend fun setWorldTimeZones(zones: List<Int>): Nothing =
        throw CaptureNeeded("world-time zones",
            "Add/select world-time cities in the app.")
}

/** Thrown by stubs whose protocol bytes haven't been captured yet. */
class CaptureNeeded(val feature: String, val howToCapture: String) :
    Exception("'$feature' not yet decoded. To enable: $howToCapture")

private fun ByteArray.beInt(start: Int, len: Int): Int {
    var v = 0
    for (i in start until minOf(start + len, size)) v = (v shl 8) or (this[i].toInt() and 0xFF)
    return v
}
