package com.ollee.companion.ble

import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.nio.charset.StandardCharsets

/** Thrown when the link looks dead (a command can't be reached at all). */
class LinkDeadException(cause: Throwable) : IOException(cause.message, cause)

private const val MAX_RECORDS = 5_000        // sanity cap on a (possibly garbage) count
private const val MAX_FETCH_MISSES = 4       // consecutive dropped fetches => link dead

/** High-level Ollee API built on [OlleeGattManager]. */
class OlleeRepository(val gatt: OlleeGattManager) {

    val connectionState get() = gatt.state

    suspend fun connect(address: String) = gatt.connect(address)
    fun disconnect() = gatt.disconnect()

    // --- Implemented (protocol known) --------------------------------------

    /** Firmware / serial string, e.g. "DEADBEEF01.05.0000.01.07DEADBEEF". */
    suspend fun firmware(): String =
        gatt.request(OlleeProtocol.CMD_INFO, timeoutMs = 3_000, retries = 2).payload
            .takeWhile { it.toInt() != 0 }.toByteArray()
            .toString(StandardCharsets.US_ASCII)

    suspend fun name(): String =
        gatt.request(OlleeProtocol.CMD_NAME, timeoutMs = 3_000, retries = 2).payload
            .toString(StandardCharsets.US_ASCII).trim()

    /** Daily step goal (uint32, big-endian). */
    suspend fun stepGoal(): Int =
        gatt.request(OlleeProtocol.CMD_STEP_GOAL, timeoutMs = 3_000, retries = 2).payload.beInt(4)

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
        val count = countRecords().coerceIn(0, MAX_RECORDS)
        val records = ArrayList<OlleeProtocol.Record>(count)
        var consecutiveMisses = 0
        repeat(count) {
            try {
                val frame = gatt.request(
                    OlleeProtocol.CMD_REC_FETCH, timeoutMs = 4_000, retries = 1,
                )
                OlleeProtocol.parseRecord(frame.payload)?.let { records.add(it) }
                consecutiveMisses = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A live link occasionally drops a single record reply; skip it
                // rather than losing the whole batch. Bail (as a dead link) only
                // if many in a row fail.
                if (++consecutiveMisses >= MAX_FETCH_MISSES) throw LinkDeadException(e)
            }
        }
        // Best-effort ack; don't fail the sync if it's missed (but do honour
        // cancellation rather than swallowing it via runCatching).
        try {
            gatt.request(OlleeProtocol.CMD_SYNC_DONE, timeoutMs = 1_500)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // ignore
        }
        return records
    }

    /**
     * Read the pending-record count (0x27). This is the drain's liveness check:
     * if it can't be reached, the link is dead. The first command after an idle
     * wake can be slow, so it gets a generous window and extra retries.
     */
    private suspend fun countRecords(): Int = try {
        gatt.request(OlleeProtocol.CMD_REC_COUNT, timeoutMs = 5_000, retries = 2)
            .payload.beInt(4)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw LinkDeadException(e)
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
