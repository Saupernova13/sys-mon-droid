package com.raavivi.sysmon.core.tracker.data

import com.raavivi.sysmon.core.tracker.model.Fix
import com.raavivi.sysmon.core.tracker.net.TrackerJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Persisted upload queue of [Fix]es, oldest first, unique by `ts`, capped at [cap]
 * (the oldest are dropped first). Every change is written to [file] atomically
 * (temp file, then rename) so a crash never leaves a half-written queue.
 */
class FixQueue(private val file: File, private val cap: Int = DEFAULT_CAP) {

    private val fixes = ArrayList<Fix>()
    private val _size = MutableStateFlow(0)

    /** Number of queued fixes, for the status screen. */
    val size: StateFlow<Int> = _size

    init {
        load()
    }

    /** Adds [fix] in time order. Returns false when a fix with the same `ts` exists. */
    @Synchronized
    fun add(fix: Fix): Boolean {
        var lo = 0
        var hi = fixes.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val ts = fixes[mid].ts
            if (ts == fix.ts) return false
            if (ts < fix.ts) lo = mid + 1 else hi = mid
        }
        fixes.add(lo, fix)
        while (fixes.size > cap) fixes.removeAt(0)
        persist()
        return true
    }

    /** Copy of all queued fixes, oldest first. */
    @Synchronized
    fun snapshot(): List<Fix> = fixes.toList()

    /** The queue split into upload chunks of at most [chunkSize], oldest first. */
    @Synchronized
    fun chunks(chunkSize: Int = CHUNK_SIZE): List<List<Fix>> = fixes.chunked(chunkSize)

    /** Removes the fixes in [sent] (matched by `ts`). */
    @Synchronized
    fun remove(sent: List<Fix>) {
        if (sent.isEmpty()) return
        val gone = sent.mapTo(HashSet()) { it.ts }
        fixes.removeAll { it.ts in gone }
        persist()
    }

    @Synchronized
    fun clear() {
        fixes.clear()
        persist()
    }

    private fun load() {
        val loaded = try {
            if (file.isFile) {
                TrackerJson.decodeFromString(ListSerializer(Fix.serializer()), file.readText())
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList() // unreadable file: start empty rather than crash
        }
        fixes.addAll(loaded.distinctBy { it.ts }.sortedBy { it.ts }.takeLast(cap))
        _size.value = fixes.size
    }

    private fun persist() {
        _size.value = fixes.size
        try {
            val json = TrackerJson.encodeToString(ListSerializer(Fix.serializer()), fixes)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json)
            try {
                Files.move(
                    tmp.toPath(), file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (_: Exception) {
            // Disk full or similar: keep the in-memory queue, try again next change.
        }
    }

    companion object {
        const val DEFAULT_CAP = 1000

        /** Maximum fixes per report request (server limit). */
        const val CHUNK_SIZE = 200
    }
}
