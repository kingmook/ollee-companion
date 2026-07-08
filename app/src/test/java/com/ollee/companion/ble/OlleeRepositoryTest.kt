package com.ollee.companion.ble

import io.mockk.coEvery
import io.mockk.coVerify
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
        coEvery { gatt.send(any()) } returns Unit
        repo.syncTime()
        coVerify { gatt.send(any()) }
    }
}
