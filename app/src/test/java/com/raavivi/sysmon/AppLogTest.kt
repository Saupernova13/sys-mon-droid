package com.raavivi.sysmon

import com.raavivi.sysmon.core.log.DirLogStore
import com.raavivi.sysmon.core.log.LogRules
import com.raavivi.sysmon.core.log.LogStore
import com.raavivi.sysmon.core.log.LogWriter
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class AppLogTest {
    @get:Rule val tmp = TemporaryFolder()

    private val zone: ZoneId = ZoneOffset.ofHours(2)
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0, s: Int = 0): Long =
        ZonedDateTime.of(y, mo, d, h, mi, s, 0, zone).toInstant().toEpochMilli()

    private class FakeStore(val files: MutableMap<String, StringBuilder> = mutableMapOf()) : LogStore {
        override fun list() = files.keys.toList()
        override fun size(name: String) = files[name]?.length?.toLong() ?: 0L
        override fun append(name: String, data: ByteArray) {
            files.getOrPut(name) { StringBuilder() }.append(String(data))
        }
        override fun delete(name: String) { files.remove(name) }
        override fun open(name: String): InputStream? = files[name]?.let { ByteArrayInputStream(it.toString().toByteArray()) }
    }

    private fun writer(
        store: LogStore, now: () -> Long, max: Long = LogRules.MAX_FILE_BYTES, enabled: () -> Boolean = { true },
    ) = LogWriter(store, { it.run() }, now, zone, max, enabled)

    @Test
    fun `file name is hourly local time`() {
        assertEquals("sysmon-20261109-15.log", LogRules.fileName(ms(2026, 11, 9, 15, 4, 5), zone))
        assertEquals("sysmon-20261231-00.log", LogRules.fileName(ms(2026, 12, 31, 0, 59), zone))
    }

    @Test
    fun `name parses back to hour start`() {
        assertEquals(ms(2026, 11, 9, 15), LogRules.hourStart("sysmon-20261109-15.log", zone))
        assertEquals(null, LogRules.hourStart("sysmon-2026110-15.log", zone))
        assertEquals(null, LogRules.hourStart("sysmon-20261309-15.log", zone))
        assertEquals(null, LogRules.hourStart("sysmon-20261109-25.log", zone))
    }

    @Test
    fun `prune deletes files older than 24h and keeps the boundary`() {
        val now = ms(2026, 11, 9, 15, 30)
        val names = listOf(
            "sysmon-20261108-14.log", // 25h30 old hour-start: older than now-24h
            "sysmon-20261108-15.log", // hour-start 24h30 ago: older than now-24h (15:30 - 24h = 15:30)
            "sysmon-20261108-16.log", // hour-start after cutoff: kept
            "sysmon-20261109-15.log",
        )
        assertEquals(listOf("sysmon-20261108-14.log", "sysmon-20261108-15.log"), LogRules.toPrune(names, now, zone))
    }

    @Test
    fun `hour start exactly 24h old is kept`() {
        val now = ms(2026, 11, 9, 15)
        assertEquals(emptyList<String>(), LogRules.toPrune(listOf("sysmon-20261108-15.log"), now, zone))
        assertEquals(
            listOf("sysmon-20261108-15.log"),
            LogRules.toPrune(listOf("sysmon-20261108-15.log"), now + 1, zone),
        )
    }

    @Test
    fun `unparseable sysmon names are pruned and others are ignored`() {
        val now = ms(2026, 11, 9, 15)
        val names = listOf("sysmon-garbage.log", "sysmon-20261109-15.log.bak", "notes.txt", "other-20200101-00.log")
        assertEquals(listOf("sysmon-garbage.log", "sysmon-20261109-15.log.bak"), LogRules.toPrune(names, now, zone))
    }

    @Test
    fun `new hourly file prunes old ones through the writer`() {
        val store = FakeStore()
        store.append("sysmon-20261101-00.log", "old".toByteArray())
        store.append("keep.txt", "x".toByteArray())
        val now = ms(2026, 11, 9, 15)
        writer(store, { now }).log('I', "T", "hello")
        assertEquals(setOf("keep.txt", "sysmon-20261109-15.log"), store.files.keys)
    }

    @Test
    fun `line format`() {
        val line = LogRules.formatLine(ms(2026, 11, 9, 15, 4, 5) + 123, zone, 'I', "Tag", "message", null)
        assertEquals("2026-11-09T15:04:05.123+02:00 I/Tag: message\n", line)
        val withTrace = LogRules.formatLine(0, zone, 'E', "T", "m", "java.lang.X\n\tat a.b\n")
        assertTrue(withTrace.endsWith("m\njava.lang.X\n\tat a.b\n"))
    }

    @Test
    fun `size cap drops lines after one marker`() {
        val store = FakeStore()
        val w = writer(store, { ms(2026, 11, 9, 15) }, max = 200)
        repeat(50) { w.log('I', "T", "line number $it") }
        val text = store.files.values.single().toString()
        assertEquals(1, Regex(Regex.escape(LogRules.TRUNCATED_MARKER)).findAll(text).count())
        assertTrue(store.size("sysmon-20261109-15.log") < 200 + LogRules.TRUNCATED_MARKER.length + 2)
        assertTrue(text.startsWith("2026-11-09T15:00:00.000+02:00 I/T: line number 0"))
    }

    @Test
    fun `cap decision`() {
        assertEquals(LogRules.Cap.WRITE, LogRules.capDecision(0, 10, false, 10))
        assertEquals(LogRules.Cap.MARKER, LogRules.capDecision(1, 10, false, 10))
        assertEquals(LogRules.Cap.DROP, LogRules.capDecision(1, 10, true, 10))
    }

    @Test
    fun `disabled gate drops all writes`() {
        val store = FakeStore()
        var on = false
        val w = writer(store, { ms(2026, 11, 9, 15) }, enabled = { on })
        w.log('I', "T", "async")
        w.logSync('E', "Crash", "sync", RuntimeException("boom"))
        assertTrue(store.files.isEmpty())
        on = true
        w.log('I', "T", "now on")
        assertEquals(1, store.files.size)
        on = false
        w.log('I', "T", "off again")
        assertFalse(store.files.values.single().contains("off again"))
    }

    @Test
    fun `prune still runs when logging is off`() {
        val store = FakeStore()
        store.append("sysmon-20261101-00.log", "old".toByteArray())
        writer(store, { ms(2026, 11, 9, 15) }, enabled = { false }).prune()
        assertTrue(store.files.isEmpty())
    }

    @Test
    fun `dir store appends and lists only sysmon files`() {
        val dir = tmp.newFolder("logs")
        val store = DirLogStore(dir)
        store.append("sysmon-20261109-15.log", "a".toByteArray())
        store.append("sysmon-20261109-15.log", "b".toByteArray())
        java.io.File(dir, "other.txt").writeText("x")
        assertEquals(listOf("sysmon-20261109-15.log"), store.list())
        assertEquals(2L, store.size("sysmon-20261109-15.log"))
    }

    @Test
    fun `redaction hides sensitive query values`() {
        val url = "http://h:1/api/x?path=C%3A%2Ffoo&token=abc.def&Access_Token=zz&password=p&code=123&size=5".toHttpUrl()
        assertEquals(
            "/api/x?path=C%3A%2Ffoo&token=***&Access_Token=***&password=***&code=***&size=5",
            LogRules.redactedTarget(url),
        )
        assertEquals("/api/fs/list", LogRules.redactedTarget("http://h/api/fs/list".toHttpUrl()))
        assertEquals(null, LogRules.redactQuery(null))
    }
}
