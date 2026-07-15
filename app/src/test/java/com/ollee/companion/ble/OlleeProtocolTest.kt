package com.ollee.companion.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class OlleeProtocolTest {

    @Test
    fun testCrc16() {
        val data = byteArrayOf(0x02, 0x2A.toByte())
        val crc = OlleeProtocol.crc16(data)
        // Verified: 02 2A -> 0xFE45
        assertEquals(0xFE45, crc)
    }

    @Test
    fun testBuildFrame() {
        val frame = OlleeProtocol.buildFrame(OlleeProtocol.CMD_INFO)
        // Length 2, Magic 2, CRC 2, Type 1, Cmd 1 = 8 bytes
        assertEquals(8, frame.size)
        // Length (excluding itself) = 6 bytes
        assertEquals(0, frame[0].toInt())
        assertEquals(6, frame[1].toInt())
        // Magic
        assertEquals(0xAA.toByte(), frame[2])
        assertEquals(0x55.toByte(), frame[3])
    }

    @Test
    fun setTimePayloadMatchesOfficialTwentyByteLayout() {
        val payload = OlleeProtocol.setTimePayload(
            epochSeconds = 0x6A567FCEL,
            tzOffsetSeconds = -14_400,
        )

        assertEquals(20, payload.size)
        assertArrayEquals(
            byteArrayOf(
                0xCE.toByte(), 0x7F, 0x56, 0x6A,
                0xC0.toByte(), 0xC7.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            ) + ByteArray(12),
            payload,
        )
    }

    @Test
    fun testParseRecord() {
        // Record: type=0, start=100, end=200, value=300
        val payload = byteArrayOf(
            0, 0, 0, 0, // type
            0, 0, 0, 100, // start
            0, 0, 0, 200.toByte(), // end
            0, 0, 1, 44.toByte() // value (300)
        )
        val record = OlleeProtocol.parseRecord(payload, ZoneOffset.UTC)
        assertNotNull(record)
        assertEquals(0, record!!.type)
        assertEquals(100L, record.tStart)
        assertEquals(200L, record.tEnd)
        assertEquals(300, record.value)
    }

    @Test
    fun recordWallClockTimestampIsNormalizedUsingHistoricalZoneOffset() {
        // The watch emitted 16:30:28 as epoch-shaped local wall time while
        // Toronto was on EDT. The actual instant was therefore 20:30:28 UTC.
        val payload = byteArrayOf(
            0, 0, 0, OlleeProtocol.REC_HEART_RATE.toByte(),
            0x6A, 0x56, 0x64, 0x24,
            0, 0, 0, 0,
            0, 0, 0, 38,
        )

        val record = OlleeProtocol.parseRecord(payload, ZoneId.of("America/Toronto"))

        assertNotNull(record)
        assertEquals(Instant.parse("2026-07-14T20:30:28Z").epochSecond, record!!.tStart)
        assertEquals(0L, record.tEnd)
        assertEquals(38, record.bpm)
    }

    @Test
    fun testDecode() {
        val frame = OlleeProtocol.buildFrame(OlleeProtocol.CMD_INFO, byteArrayOf(0x01, 0x02))
        val decoded = OlleeProtocol.decode(frame)
        assertNotNull(decoded)
        assertEquals(OlleeProtocol.CMD_INFO, decoded!!.cmd)
        assertTrue(decoded.crcOk)
        assertArrayEquals(byteArrayOf(0x01, 0x02), decoded.payload)
    }

    @Test
    fun reassemblerHandlesEveryFragmentBoundaryInOrder() {
        val first = OlleeProtocol.buildFrame(OlleeProtocol.CMD_INFO, byteArrayOf(1, 2, 3))
        val second = OlleeProtocol.buildFrame(OlleeProtocol.CMD_NAME, byteArrayOf(4, 5))
        val stream = first + second

        for (split in 1 until stream.size) {
            val reassembler = FrameReassembler()
            val frames = reassembler.feed(stream.copyOfRange(0, split)) +
                reassembler.feed(stream.copyOfRange(split, stream.size))
            assertEquals(listOf(OlleeProtocol.CMD_INFO, OlleeProtocol.CMD_NAME), frames.map { it.cmd })
            assertTrue(frames.all { it.crcOk })
        }
    }

    @Test
    fun reassemblerHandlesOneByteNotifications() {
        val expected = OlleeProtocol.buildFrame(OlleeProtocol.CMD_LIVE, byteArrayOf(0x12, 0x34))
        val reassembler = FrameReassembler()
        val frames = expected.flatMap { byte -> reassembler.feed(byteArrayOf(byte)) }

        assertEquals(1, frames.size)
        assertEquals(OlleeProtocol.CMD_LIVE, frames.single().cmd)
        assertArrayEquals(byteArrayOf(0x12, 0x34), frames.single().payload)
    }
}
