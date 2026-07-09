package com.ollee.companion

import android.app.Application
import com.ollee.companion.ble.OlleeGattManager
import com.ollee.companion.ble.OlleeRepository
import com.ollee.companion.data.RecordStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Holds the BLE connection at application scope so it survives Activity/ViewModel
 * recreation and app backgrounding. The foreground service keeps the process
 * alive while connected.
 */
class OlleeApp : Application() {

    lateinit var repository: OlleeRepository
        private set

    lateinit var recordStore: RecordStore
        private set

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        recordStore = RecordStore(this)
        repository = OlleeRepository(OlleeGattManager(this))

        // Persist records synced in the background immediately.
        scope.launch {
            repository.newRecords.collect { recs ->
                recordStore.merge(recs)
            }
        }
    }
}
