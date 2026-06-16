package com.ollee.companion.ble

import java.nio.charset.StandardCharsets

/** High-level Ollee API built on [OlleeGattManager]. */
class OlleeRepository(val gatt: OlleeGattManager) {

    val connectionState get() = gatt.state

    suspend fun connect(address: String) = gatt.connect(address)
    fun disconnect() = gatt.disconnect()
    fun release() = gatt.release()

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
        gatt.request(OlleeProtocol.CMD_STEP_GOAL).payload.beInt(4)

    /** Live value polled by the app (battery/steps — exact meaning TBD). */
    suspend fun liveValue(): Int =
        gatt.request(OlleeProtocol.CMD_LIVE).payload.beInt(2)

    /** Automatic sync: push the phone's current time + timezone to the watch. */
    suspend fun syncTime() = gatt.send(OlleeProtocol.setTimeFrame())

    /**
     * Drain the watch's health/activity log: ask for the count (0x27), fetch
     * each record (0x28), then acknowledge (0x2d). Returns steps, hourly
     * temperature, and heart-rate records.
     */
    suspend fun syncRecords(): List<OlleeProtocol.Record> {
        val count = gatt.request(OlleeProtocol.CMD_REC_COUNT).payload.beInt(4)
        val records = ArrayList<OlleeProtocol.Record>(count)
        repeat(count) {
            val frame = gatt.request(OlleeProtocol.CMD_REC_FETCH, timeoutMs = 3_000)
            OlleeProtocol.parseRecord(frame.payload)?.let { records.add(it) }
        }
        runCatching { gatt.request(OlleeProtocol.CMD_SYNC_DONE, timeoutMs = 1_500) }
        return records
    }

    // --- Alarm -------------------------------------------------------------

    /**
     * Set/update the alarm. daysMask: bit0=Sun .. bit6=Sat (0x3E = Mon-Fri).
     * The watch acks slowly (~5-7s), so we wait long with no retry to avoid
     * sending a duplicate alarm.
     */
    suspend fun setAlarm(hour: Int, minute: Int, daysMask: Int) {
        gatt.request(
            OlleeProtocol.CMD_SET_ALARM,
            OlleeProtocol.alarmPayload(hour, minute, daysMask),
            timeoutMs = 8_000, retries = 0,
        )
    }

    /** Clear/disable the alarm (sends the alarm frame with zero repeat days). */
    suspend fun clearAlarm() = setAlarm(0, 0, 0)
}

private fun ByteArray.beInt(len: Int): Int {
    var v = 0
    for (i in 0 until minOf(len, size)) v = (v shl 8) or (this[i].toInt() and 0xFF)
    return v
}
