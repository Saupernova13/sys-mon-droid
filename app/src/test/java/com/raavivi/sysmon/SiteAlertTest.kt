package com.raavivi.sysmon

import com.raavivi.sysmon.core.model.SiteStatus
import com.raavivi.sysmon.core.model.SitesResponse
import com.raavivi.sysmon.core.net.SysMonJson
import com.raavivi.sysmon.core.push.SiteAlert
import com.raavivi.sysmon.core.push.humanSpan
import com.raavivi.sysmon.ui.sites.SiteText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** Pins the site-monitoring push parser, texts and `/api/sites` DTO to the server contract. */
class SiteAlertTest {

    private val utc = ZoneId.of("UTC")

    private fun down(extra: Map<String, String> = emptyMap()) = mapOf(
        "type" to "site_status", "event" to "down", "id" to "jf", "name" to "Jellyfin",
        "url" to "https://jellyfin.example/health", "since" to "1794753196", "ts" to "1794753200",
        "error" to "timeout",
    ) + extra

    private fun up(extra: Map<String, String> = emptyMap()) = mapOf(
        "type" to "site_status", "event" to "up", "id" to "jf", "name" to "Jellyfin",
        "url" to "https://jellyfin.example/health", "since" to "1794753196", "ts" to "1794753200",
        "downtime_s" to "720",
    ) + extra

    @Test
    fun `down push parses and renders`() {
        val a = SiteAlert.from(down())!!
        assertTrue(a.down)
        assertEquals("jf", a.id)
        assertEquals("Jellyfin is down", a.title())
        // 1794753196 = 2026-11-15 14:33:16 UTC
        assertEquals("timeout - since 14:33", a.text(utc))
    }

    @Test
    fun `up push renders downtime`() {
        val a = SiteAlert.from(up())!!
        assertFalse(a.down)
        assertEquals("Jellyfin is back up", a.title())
        assertEquals("Down for 12 min", a.text(utc))
    }

    @Test
    fun `malformed pushes are rejected or degrade`() {
        assertNull(SiteAlert.from(down(mapOf("type" to "other"))))
        assertNull(SiteAlert.from(down(mapOf("event" to "sideways"))))
        assertNull(SiteAlert.from(down(mapOf("id" to " "))))
        val noName = SiteAlert.from(down(mapOf("name" to "", "since" to "abc", "error" to "")))!!
        assertEquals("https://jellyfin.example/health is down", noName.title())
        assertEquals("unreachable", noName.text(utc))
        assertEquals("Back up", SiteAlert.from(up(mapOf("downtime_s" to "x")))!!.text(utc))
    }

    @Test
    fun `humanSpan picks coarse units`() {
        assertEquals("0 s", humanSpan(-5))
        assertEquals("45 s", humanSpan(45))
        assertEquals("12 min", humanSpan(720))
        assertEquals("1 h", humanSpan(3600))
        assertEquals("3 h 20 min", humanSpan(12_000))
        assertEquals("3 d", humanSpan(3 * 86_400L + 100))
        assertEquals("2 d 4 h", humanSpan(2 * 86_400L + 4 * 3600L + 5))
    }

    @Test
    fun `sites response decodes the server payload`() {
        val raw = """{"enabled":true,"interval_s":60,"fail_threshold":3,"sites":[
            {"state":"up","since":1794753196.0,"consecutive_failures":0,"last_status":200,
             "last_latency_ms":123,"last_error":null,"last_checked":1794753200.5,
             "history":[{"ok":true,"latency_ms":120},{"ok":false,"latency_ms":0}],
             "id":"jf","name":"Jellyfin","url":"https://x/health","expect":"200-399","keyword":null},
            {"state":"unknown","since":null,"consecutive_failures":0,"last_status":null,
             "last_latency_ms":null,"last_error":null,"last_checked":null,"history":[],
             "id":"b","name":"B","url":"https://b","expect":"200-399","keyword":null}]}"""
        val r = SysMonJson.decodeFromString(SitesResponse.serializer(), raw)
        assertTrue(r.enabled)
        assertEquals(2, r.sites.size)
        val s = r.sites[0]
        assertEquals("up", s.state)
        assertEquals(1794753196.0, s.since!!, 0.0)
        assertEquals(123L, s.lastLatencyMs)
        assertEquals(listOf(true, false), s.history.map { it.ok })
        assertEquals(120L, s.history[0].latencyMs)
        assertNull(r.sites[1].since)
    }

    @Test
    fun `row status text`() {
        val now = 1_000_000L
        fun site(state: String, since: Double?, err: String? = null) =
            SiteStatus(state = state, since = since, lastError = err)
        assertEquals("Up 3 d", SiteText.status(site("up", (now - 3 * 86_400L - 60).toDouble()), now))
        assertEquals("Down 12 min - timeout", SiteText.status(site("down", (now - 720).toDouble(), "timeout"), now))
        assertEquals("Down", SiteText.status(site("down", null), now))
        assertEquals("Not checked yet", SiteText.status(site("unknown", null), now))
        assertEquals("123 ms", SiteText.latency(SiteStatus(lastLatencyMs = 123)))
        assertNull(SiteText.latency(SiteStatus()))
    }
}
