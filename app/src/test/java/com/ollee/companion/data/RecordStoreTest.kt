package com.ollee.companion.data

import android.content.Context
import com.ollee.companion.ble.OlleeProtocol
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecordStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var store: RecordStore
    private lateinit var filesDir: File

    @Before
    fun setup() {
        context = mockk()
        filesDir = tempFolder.newFolder("files")
        every { context.filesDir } returns filesDir
        store = RecordStore(context)
    }

    @Test
    fun testMergeAndLoad() {
        val record1 = OlleeProtocol.Record(OlleeProtocol.REC_STEPS, 1000, 2000, 50)
        val record2 = OlleeProtocol.Record(OlleeProtocol.REC_STEPS, 3000, 4000, 60)
        
        // Use recent timestamps so they are not pruned (current time - a few seconds)
        val now = System.currentTimeMillis() / 1000
        val r1 = record1.copy(tStart = now - 100, tEnd = now - 50)
        val r2 = record2.copy(tStart = now - 200, tEnd = now - 150)

        store.merge(listOf(r1))
        val loaded1 = store.loadAll()
        assertEquals(1, loaded1.size)
        assertEquals(r1.tStart, loaded1[0].tStart)

        store.merge(listOf(r2))
        val loaded2 = store.loadAll()
        assertEquals(2, loaded2.size)
        
        // Should be sorted by tStart descending
        assertEquals(r1.tStart, loaded2[0].tStart)
        assertEquals(r2.tStart, loaded2[1].tStart)
    }

    @Test
    fun testPruning() {
        val now = System.currentTimeMillis() / 1000
        val oldRecord = OlleeProtocol.Record(OlleeProtocol.REC_STEPS, now - 40 * 86400, now - 39 * 86400, 10) // 40 days ago
        val newRecord = OlleeProtocol.Record(OlleeProtocol.REC_STEPS, now - 100, now - 50, 20)
        
        store.merge(listOf(oldRecord, newRecord))
        val loaded = store.loadAll()
        
        assertEquals(1, loaded.size)
        assertEquals(newRecord.tStart, loaded[0].tStart)
    }
}
