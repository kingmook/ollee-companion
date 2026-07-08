package com.ollee.companion.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun testParseRecord() {
        // Record: type=0, start=100, end=200, value=300
        val payload = byteArrayOf(
            0, 0, 0, 0, // type
            0, 0, 0, 100, // start
            0, 0, 0, 200.toByte(), // end
            0, 0, 1, 44.toByte() // value (300)
        )
        val record = OlleeProtocol.parseRecord(payload)
        assertNotNull(record)
        assertEquals(0, record!!.type)
        assertEquals(100L, record.tStart)
        assertEquals(200L, record.tEnd)
        assertEquals(300, record.value)
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
}
