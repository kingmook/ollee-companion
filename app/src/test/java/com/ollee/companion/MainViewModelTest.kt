package com.ollee.companion

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.ollee.companion.ble.ConnectionState
import com.ollee.companion.ble.OlleeRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
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
    private lateinit var syncCoordinator: SyncCoordinator
    private lateinit var viewModel: MainViewModel
    private lateinit var viewModelStore: ViewModelStore
    private lateinit var testDispatcher: TestDispatcher
    
    private val connectionStateFlow = MutableStateFlow(ConnectionState.DISCONNECTED)

    @Before
    fun setup() {
        testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        
        app = mockk(relaxed = true)
        repo = mockk(relaxed = true)
        syncCoordinator = mockk(relaxed = true)
        val prefs: SharedPreferences = mockk(relaxed = true)
        val records = MutableStateFlow(emptyList<com.ollee.companion.ble.OlleeProtocol.Record>())
        val syncStatus = MutableStateFlow(SyncStatus())
        
        every { app.repository } returns repo
        every { app.syncCoordinator } returns syncCoordinator
        every { syncCoordinator.records } returns records
        every { syncCoordinator.status } returns syncStatus
        coEvery { syncCoordinator.withWatchAccess<Boolean>(any()) } returns false
        every { app.getSharedPreferences(any(), any()) } returns prefs
        every { app.applicationContext } returns app
        every { app.filesDir } returns File("build/tmp/test")
        
        every { repo.connectionState } returns connectionStateFlow
        
        viewModelStore = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MainViewModel(app) as T
        }
        viewModel = ViewModelProvider(viewModelStore, factory)[MainViewModel::class.java]
    }

    @After
    fun tearDown() {
        // Match the real owner lifecycle: cancelling viewModelScope must happen
        // while the test Main dispatcher is still installed.
        try {
            viewModelStore.clear()
            testDispatcher.scheduler.runCurrent()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun testInitialState() = runTest(testDispatcher) {
        testDispatcher.scheduler.runCurrent()
        assertEquals(ConnectionState.DISCONNECTED, viewModel.ui.value.connection)
    }

    @Test
    fun testConnectionStateUpdate() = runTest(testDispatcher) {
        testDispatcher.scheduler.runCurrent()
        connectionStateFlow.value = ConnectionState.READY
        testDispatcher.scheduler.runCurrent()

        assertEquals(ConnectionState.READY, viewModel.ui.value.connection)
    }
}
