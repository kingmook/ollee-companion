package com.ollee.companion.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TimeZone
import java.util.UUID

/**
 * Ollee watch BLE protocol, reverse-engineered from the official app's traffic.
 *
 * Frame on the wire (over Nordic UART Service):
 *
 *     LEN_HI LEN_LO  AA 55  CK_HI CK_LO  02  CMD  payload...
 *     [--length--]   [magic][---CRC---]  [ty][cmd][--payload--]
 *
 *  - LEN  = big-endian count of bytes AFTER the length field (= 6 + payload).
 *  - CK   = CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF, no reflection),
 *           big-endian, over `02 CMD payload`.
 *  - 0x02 = constant frame type.
 *  - The watch answers request CMD x with response CMD x + 0x20.
 *
 * This is a verified 1:1 port of the Python reference in ../ollee-ble/ollee.py.
 */
object OlleeProtocol {

    // Nordic UART Service
    val NUS_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    val NUS_RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e") // write
    val NUS_TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e") // notify
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val TYPE = 0x02
    const val RESP_OFFSET = 0x20

    // --- Known request command codes (response = code + 0x20) ---------------
    const val CMD_INFO = 0x2A        // firmware / serial string
    const val CMD_STATUS = 0x2B      // status block
    const val CMD_SET_TIME = 0x23    // set clock: LE unix ts + tz offset
    const val CMD_STEP_GOAL = 0x30   // daily step goal
    const val CMD_SETTINGS = 0x32    // settings incl. backlight RGB
    const val CMD_WEEKDAYS = 0x35    // weekday label string
    const val CMD_FEATURES = 0x37    // capability/feature table
    const val CMD_LIVE = 0x39        // live value (battery/steps) polled
    const val CMD_NAME = 0x2E        // name tag string

    /** CRC-16/CCITT-FALSE. */
    fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF
                else (crc shl 1) and 0xFFFF
            }
        }
        return crc and 0xFFFF
    }

    /** Wrap a command + payload into a full on-the-wire frame. */
    fun buildFrame(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val body = byteArrayOf(TYPE.toByte(), cmd.toByte()) + payload
        val crc = crc16(body)
        val after = byteArrayOf(
            0xAA.toByte(), 0x55.toByte(),
            ((crc shr 8) and 0xFF).toByte(), (crc and 0xFF).toByte()
        ) + body
        val len = after.size
        return byteArrayOf(((len shr 8) and 0xFF).toByte(), (len and 0xFF).toByte()) + after
    }

    /** Split a frame into <=20-byte BLE writes; the watch reassembles by length. */
    fun chunk(frame: ByteArray, size: Int = 20): List<ByteArray> =
        frame.toList().chunked(size).map { it.toByteArray() }

    /**
     * Build a set-time (0x23) frame: LE unix timestamp + LE timezone offset,
     * followed by the constant config tail captured from the official app.
     */
    fun setTimeFrame(
        epochSeconds: Long = System.currentTimeMillis() / 1000,
        tzOffsetSeconds: Int = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000
    ): ByteArray {
        val tail = byteArrayOf(
            0x33, 0xC9.toByte(), 0x00, 0x00,
            0x81.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x03, 0x00
        )
        val p = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        p.putInt((epochSeconds and 0xFFFFFFFFL).toInt())
        p.putInt(tzOffsetSeconds)
        return buildFrame(CMD_SET_TIME, p.array() + tail)
    }

    data class Frame(
        val cmd: Int,
        val type: Int,
        val payload: ByteArray,
        val crcOk: Boolean,
        val raw: ByteArray
    )

    /** Validate magic + CRC and split a complete frame into fields. */
    fun decode(frame: ByteArray): Frame? {
        if (frame.size < 8) return null
        if (frame[2] != 0xAA.toByte() || frame[3] != 0x55.toByte()) return null
        val len = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)
        val after = frame.copyOfRange(2, minOf(frame.size, 2 + len))
        if (after.size < 4) return null
        val crcRx = ((after[2].toInt() and 0xFF) shl 8) or (after[3].toInt() and 0xFF)
        val body = after.copyOfRange(4, after.size)
        val crcOk = body.size >= 2 && crc16(body) == crcRx
        val type = if (body.isNotEmpty()) body[0].toInt() and 0xFF else -1
        val cmd = if (body.size >= 2) body[1].toInt() and 0xFF else -1
        val payload = if (body.size > 2) body.copyOfRange(2, body.size) else ByteArray(0)
        return Frame(cmd, type, payload, crcOk, frame)
    }
}

/**
 * Reassembles NUS notification chunks into complete frames using the 2-byte
 * length header. Feed raw notification bytes; get back decoded frames.
 */
class FrameReassembler {
    private var buf = ByteArray(0)

    fun feed(data: ByteArray): List<OlleeProtocol.Frame> {
        buf += data
        val out = ArrayList<OlleeProtocol.Frame>()
        while (buf.size >= 2) {
            val length = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
            val total = 2 + length
            if (buf.size < total) break
            val frame = buf.copyOfRange(0, total)
            buf = buf.copyOfRange(total, buf.size)
            OlleeProtocol.decode(frame)?.let { out.add(it) }
        }
        return out
    }

    fun reset() {
        buf = ByteArray(0)
    }
}
