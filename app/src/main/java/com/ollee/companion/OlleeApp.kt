package com.ollee.companion

import android.app.Application
import com.ollee.companion.ble.OlleeGattManager
import com.ollee.companion.ble.OlleeRepository

/**
 * Holds the BLE connection at application scope so it survives Activity/ViewModel
 * recreation and app backgrounding. The foreground service keeps the process
 * alive while connected.
 */
class OlleeApp : Application() {

    lateinit var repository: OlleeRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = OlleeRepository(OlleeGattManager(this))
    }
}
