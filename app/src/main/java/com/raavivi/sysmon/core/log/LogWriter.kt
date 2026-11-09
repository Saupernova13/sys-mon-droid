package com.raavivi.sysmon.core.log

import java.io.OutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes log lines to hourly files in a [LogStore]. Normal lines go through [executor]
 * (a single thread in the app); [logSync] writes on the caller's thread for the crash path.
 * [enabled] is checked on every call: when false nothing is written. Never throws:
 * logging must not take the app down.
 */
class LogWriter(
    private val store: LogStore,
    private val executor: Executor,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val maxFileBytes: Long = LogRules.MAX_FILE_BYTES,
    private val enabled: () -> Boolean = { true },
) {
    private val lock = Any()
    private var currentName: String? = null
    private var currentSize = 0L
    private var markerWritten = false

    fun log(level: Char, tag: String, msg: String, t: Throwable? = null) {
        if (!enabled()) return
        val now = clock()
        val line = LogRules.formatLine(now, zone, level, tag, msg, t?.let(::stackTrace))
        try {
            executor.execute { write(now, line) }
        } catch (_: Exception) {
            // executor rejected (shutdown): drop the line
        }
    }

    /** Crash path: write now, on this thread. */
    fun logSync(level: Char, tag: String, msg: String, t: Throwable? = null) {
        if (!enabled()) return
        val now = clock()
        write(now, LogRules.formatLine(now, zone, level, tag, msg, t?.let(::stackTrace)))
    }

    /** Prunes old files (run at app start, even when logging is off; also on each new hourly file). */
    fun prune() {
        executor.execute { synchronized(lock) { pruneLocked(clock()) } }
    }

    private fun write(now: Long, line: String) = synchronized(lock) {
        try {
            val name = LogRules.fileName(now, zone)
            if (name != currentName) {
                currentName = name
                markerWritten = false
                pruneLocked(now)
                currentSize = store.size(name)
            }
            val bytes = line.toByteArray(Charsets.UTF_8)
            when (LogRules.capDecision(currentSize, bytes.size, markerWritten, maxFileBytes)) {
                LogRules.Cap.WRITE -> {
                    store.append(name, bytes)
                    currentSize += bytes.size
                }
                LogRules.Cap.MARKER -> {
                    val marker = (LogRules.TRUNCATED_MARKER + "\n").toByteArray(Charsets.UTF_8)
                    store.append(name, marker)
                    currentSize += marker.size
                    markerWritten = true
                }
                LogRules.Cap.DROP -> Unit
            }
        } catch (_: Throwable) {
            // best effort
        }
    }

    private fun pruneLocked(now: Long) {
        try {
            for (n in LogRules.toPrune(store.list(), now, zone)) store.delete(n)
        } catch (_: Throwable) {
            // best effort
        }
    }

    /** Waits (bounded) for queued lines to land. */
    fun flush() {
        val done = CountDownLatch(1)
        try {
            executor.execute { done.countDown() }
            done.await(3, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // best effort
        }
    }

    /** Zips every log file into [out]; returns how many files were added. */
    fun zipTo(out: OutputStream): Int {
        flush()
        var count = 0
        ZipOutputStream(out).use { zip ->
            synchronized(lock) {
                for (n in store.list().sorted()) {
                    val input = store.open(n) ?: continue
                    input.use {
                        zip.putNextEntry(ZipEntry(n))
                        it.copyTo(zip)
                        zip.closeEntry()
                        count++
                    }
                }
            }
        }
        return count
    }

    /** Deletes every log file. */
    fun clear() {
        flush()
        synchronized(lock) {
            try {
                for (n in store.list()) store.delete(n)
            } catch (_: Throwable) {
                // best effort
            }
            currentName = null
        }
    }

    companion object {
        fun stackTrace(t: Throwable): String {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            return sw.toString()
        }
    }
}
