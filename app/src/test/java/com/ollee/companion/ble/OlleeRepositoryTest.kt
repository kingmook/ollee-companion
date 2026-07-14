package com.ollee.companion.ble

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class OlleeRepositoryTest {

    private val gatt: OlleeGattManager = mockk()
    private val repo = OlleeRepository(gatt)

    @Before
    fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testFirmware() = runTest {
        val payload = "FW1.0.0".toByteArray() + byteArrayOf(0)
        coEvery { gatt.request(OlleeProtocol.CMD_INFO, any(), any()) } returns 
            OlleeProtocol.Frame(OlleeProtocol.CMD_INFO + 0x20, 0x02, payload, true, byteArrayOf())
        
        val fw = repo.firmware()
        assertEquals("FW1.0.0", fw)
    }

    @Test
    fun testStepGoal() = runTest {
        val payload = byteArrayOf(0, 0, 0x27, 0x10.toByte()) // 10000
        coEvery { gatt.request(OlleeProtocol.CMD_STEP_GOAL, any(), any()) } returns 
            OlleeProtocol.Frame(OlleeProtocol.CMD_STEP_GOAL + 0x20, 0x02, payload, true, byteArrayOf())
        
        val goal = repo.stepGoal()
        assertEquals(10000, goal)
    }

    @Test
    fun testSyncTime() = runTest {
        coEvery {
            gatt.request(OlleeProtocol.CMD_SET_TIME, any(), 3_000, 0)
        } returns frame(OlleeProtocol.CMD_SET_TIME)

        repo.syncTime()

        coVerify(exactly = 1) {
            gatt.request(
                OlleeProtocol.CMD_SET_TIME,
                match { it.size == 20 },
                3_000,
                0,
            )
        }
        coVerify(exactly = 0) { gatt.send(any()) }
    }

    @Test
    fun completeRecordDrainIsAcknowledged() = runTest {
        runBurstBlocks()
        var countReads = 0
        coEvery {
            gatt.request(OlleeProtocol.CMD_REC_COUNT, any(), 5_000, 2)
        } coAnswers {
            frame(
                OlleeProtocol.CMD_REC_COUNT,
                intPayload(if (countReads++ == 0) 1 else 0),
            )
        }
        coEvery {
            gatt.request(OlleeProtocol.CMD_REC_FETCH, any(), 4_000, 1)
        } returns frame(OlleeProtocol.CMD_REC_FETCH, recordPayload(value = 123))
        coEvery { gatt.send(any()) } returns Unit

        val result = repo.syncRecords()

        assertEquals(true, result.drained)
        assertEquals(true, result.acknowledged)
        assertEquals(123, result.records.single().value)
        coVerifyOrder {
            gatt.request(OlleeProtocol.CMD_REC_COUNT, any(), 5_000, 2)
            gatt.request(OlleeProtocol.CMD_REC_FETCH, any(), 4_000, 1)
            gatt.send(any())
            gatt.request(OlleeProtocol.CMD_REC_COUNT, any(), 5_000, 2)
        }
    }

    @Test
    fun emptyRecordLogSucceedsWithoutCleanupCommand() = runTest {
        runBurstBlocks()
        coEvery {
            gatt.request(OlleeProtocol.CMD_REC_COUNT, any(), 5_000, 2)
        } returns frame(OlleeProtocol.CMD_REC_COUNT, intPayload(0))

        val result = repo.syncRecords()

        assertEquals(true, result.drained)
        assertEquals(true, result.acknowledged)
        assertEquals(0, result.records.size)
        coVerify(exactly = 0) { gatt.send(any()) }
        coVerify(exactly = 0) {
            gatt.request(OlleeProtocol.CMD_REC_FETCH, any(), any(), any())
        }
    }

    @Test
    fun cleanupThatLeavesRecordsIsNotAcknowledged() = runTest {
        runBurstBlocks()
        var countReads = 0
        coEvery {
            gatt.request(OlleeProtocol.CMD_REC_COUNT, any(), 5_000, 2)
        } coAnswers {
            frame(
                OlleeProtocol.CMD_REC_COUNT,
                intPayload(if (countReads++ == 0) 1 else 1),
            )
        }
        coEvery {
            gatt.request(OlleeProtocol.CMD_REC_FETCH, any(), 4_000, 1)
        } returns frame(OlleeProtocol.CMD_REC_FETCH, recordPayload(value = 456))
        coEvery { gatt.send(any()) } returns Unit

        val result = repo.syncRecords()

        assertEquals(true, result.drained)
        assertEquals(false, result.acknowledged)
        assertEquals(true, result.failure?.contains("still reports 1") == true)
        coVerify(exactly = 1) { gatt.send(any()) }
    }

    @Test
    fun incompleteRecordDrainPreservesPartialDataWithoutAcknowledging() = runTest {
        runBurstBlocks()
        coEvery {
            gatt.request(OlleeProtocol.CMD_REC_COUNT, any(), 5_000, 2)
        } returns frame(OlleeProtocol.CMD_REC_COUNT, intPayload(2))
        var fetches = 0
        coEvery {
            gatt.request(OlleeProtocol.CMD_REC_FETCH, any(), 4_000, 1)
        } coAnswers {
            if (fetches++ == 0) frame(OlleeProtocol.CMD_REC_FETCH, recordPayload(value = 321))
            else throw IOException("dropped response")
        }

        val result = repo.syncRecords()

        assertEquals(false, result.drained)
        assertEquals(false, result.acknowledged)
        assertEquals(2, result.expectedCount)
        assertEquals(321, result.records.single().value)
        coVerify(exactly = 0) {
            gatt.send(any())
        }
    }

    @Test
    fun malformedRecordCountCannotClearTheWatchLog() = runTest {
        runBurstBlocks()
        coEvery {
            gatt.request(OlleeProtocol.CMD_REC_COUNT, any(), 5_000, 2)
        } returns frame(OlleeProtocol.CMD_REC_COUNT, byteArrayOf(0))

        val result = repo.syncRecords()

        assertEquals(false, result.drained)
        assertEquals(false, result.acknowledged)
        assertEquals(true, result.failure?.contains("record count") == true)
        coVerify(exactly = 0) {
            gatt.send(any())
        }
    }

    @Test
    fun recordCountTimeoutDoesNotDisconnectTheWatch() = runTest {
        runBurstBlocks()
        coEvery {
            gatt.request(OlleeProtocol.CMD_REC_COUNT, any(), 5_000, 2)
        } throws IOException("timeout waiting for record count")

        val result = repo.syncRecords()

        assertEquals(false, result.drained)
        assertEquals(true, result.failure?.contains("timeout") == true)
        coVerify(exactly = 0) { gatt.disconnect() }
        coVerify(exactly = 0) {
            gatt.send(any())
        }
    }

    private fun runBurstBlocks() {
        coEvery { gatt.burst<RecordSyncResult>(any()) } coAnswers {
            firstArg<suspend () -> RecordSyncResult>().invoke()
        }
    }

    private fun frame(cmd: Int, payload: ByteArray = ByteArray(0)) =
        OlleeProtocol.Frame(cmd + OlleeProtocol.RESP_OFFSET, 0x02, payload, true, byteArrayOf())

    private fun intPayload(value: Int) = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(),
        (value ushr 8).toByte(), value.toByte(),
    )

    private fun recordPayload(value: Int): ByteArray =
        intPayload(OlleeProtocol.REC_STEPS) + intPayload(1_700_000_000) +
            intPayload(1_700_000_060) + intPayload(value)
}
