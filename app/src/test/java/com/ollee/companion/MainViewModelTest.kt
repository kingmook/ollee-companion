package com.ollee.companion

import android.content.Context
import android.content.SharedPreferences
import app.cash.turbine.test
import com.ollee.companion.ble.ConnectionState
import com.ollee.companion.ble.OlleeRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private lateinit var app: OlleeApp
    private lateinit var repo: OlleeRepository
    private lateinit var viewModel: MainViewModel
    
    private val connectionStateFlow = MutableStateFlow(ConnectionState.DISCONNECTED)

    @Before
    fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        
        app = mockk(relaxed = true)
        repo = mockk(relaxed = true)
        val prefs: SharedPreferences = mockk(relaxed = true)
        
        every { app.repository } returns repo
        every { app.getSharedPreferences(any(), any()) } returns prefs
        every { app.applicationContext } returns app
        every { app.filesDir } returns File("build/tmp/test")
        
        every { repo.connectionState } returns connectionStateFlow
        
        viewModel = MainViewModel(app)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialState() = runTest {
        viewModel.ui.test {
            val state = awaitItem()
            assertEquals(ConnectionState.DISCONNECTED, state.connection)
        }
    }

    @Test
    fun testConnectionStateUpdate() = runTest {
        viewModel.ui.test {
            assertEquals(ConnectionState.DISCONNECTED, awaitItem().connection)
            
            connectionStateFlow.value = ConnectionState.READY
            
            // We expect multiple updates:
            // 1. ConnectionState.READY update
            // 2. tryRefresh updates (firmware, goal, etc.)
            // 3. setMessage("Synced time with watch.")
            
            var lastState = awaitItem()
            while (lastState.message == null) {
                lastState = awaitItem()
            }
            
            assertEquals(ConnectionState.READY, lastState.connection)
            assertEquals("Synced time with watch.", lastState.message)
        }
    }
}
