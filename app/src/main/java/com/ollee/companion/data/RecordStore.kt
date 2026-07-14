package com.ollee.companion.data

import android.content.Context
import com.ollee.companion.ble.OlleeProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class RecordStoreException(message: String, cause: Throwable) : IOException(message, cause)

/**
 * Persists synced watch records to a JSON file in app storage, deduplicated and
 * pruned to a rolling retention window (default 30 days).
 *
 * The watch's on-device log is short and may be cleared after a sync, so the
 * app accumulates history here across syncs. Records are keyed by
 * type+tStart+tEnd, so re-syncing the same entries never creates duplicates.
 *
 * Uses the platform's built-in org.json — no extra dependencies / codegen.
 */
class RecordStore(context: Context, private val retentionDays: Int = 30) {

    private val file = File(context.filesDir, "records.json")

    @Synchronized
    fun loadAll(): List<OlleeProtocol.Record> {
        if (!file.exists()) return emptyList()
        return try {
            parse(file.readText())
        } catch (e: Exception) {
            throw RecordStoreException("Stored health history could not be read", e)
        }
    }

    /** Merge new records in, prune anything older than the window, persist, return all. */
    @Synchronized
    fun merge(newRecords: List<OlleeProtocol.Record>): List<OlleeProtocol.Record> {
        val cutoff = (System.currentTimeMillis() / 1000) - (retentionDays * 86_400L)
        val byKey = LinkedHashMap<String, OlleeProtocol.Record>()
        for (r in loadAll() + newRecords) {
            if ((r.tStart < cutoff) || (r.tStart <= 0)) continue
            byKey[key(r)] = r
        }
        val merged = byKey.values.sortedByDescending { it.tStart }
        writeAtomically(serialize(merged))
        return merged
    }

    private fun writeAtomically(text: String) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(text.toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(), file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            throw RecordStoreException("Health history could not be saved", e)
        } finally {
            temp.delete()
        }
    }

    private fun key(r: OlleeProtocol.Record) = "${r.type}:${r.tStart}:${r.tEnd}"

    private fun serialize(list: List<OlleeProtocol.Record>): String {
        val arr = JSONArray()
        for (r in list) {
            arr.put(
                JSONObject()
                    .put("t", r.type).put("s", r.tStart)
                    .put("e", r.tEnd).put("v", r.value),
            )
        }
        return arr.toString()
    }

    private fun parse(text: String): List<OlleeProtocol.Record> {
        val arr = JSONArray(text)
        val out = ArrayList<OlleeProtocol.Record>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                OlleeProtocol.Record(
                    o.getInt("t"), o.getLong("s"), o.getLong("e"), o.getInt("v"),
                ),
            )
        }
        return out
    }
}
