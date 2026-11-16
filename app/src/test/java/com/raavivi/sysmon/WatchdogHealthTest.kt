package com.raavivi.sysmon

import com.raavivi.sysmon.core.model.WatchdogEventsResponse
import com.raavivi.sysmon.core.model.WatchdogPauseBody
import com.raavivi.sysmon.core.model.WatchdogStatus
import com.raavivi.sysmon.core.model.WatchdogTarget
import com.raavivi.sysmon.core.net.SysMonJson
import com.raavivi.sysmon.core.push.WatchdogAlert
import com.raavivi.sysmon.core.push.WatchdogLevel
import com.raavivi.sysmon.ui.common.humanSpan
import com.raavivi.sysmon.ui.health.HealthText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

/** Pins the watchdog push parser, event mapping, DTOs and state text to the server contract. */
class WatchdogHealthTest {

    private val utc = ZoneId.of("UTC")

    private fun push(event: String, extra: Map<String, String> = emptyMap()) = mapOf(
        "type" to "watchdog", "event" to event, "id" to "jellyfin", "name" to "Jellyfin",
        "detail" to "HTTP 502", "state" to "down", "ts" to "1794753196", "eid" to "e-1",
    ) + extra

    @Test
    fun `push parses`() {
        val a = WatchdogAlert.from(push("degraded"))!!
        assertEquals("jellyfin", a.id)
        assertEquals("HTTP 502", a.detail)
        assertEquals("down", a.state)
        assertEquals(1794753196.0, a.ts!!, 0.0)
        assertEquals("e-1", a.eid)
        assertNull(WatchdogAlert.from(push("degraded", mapOf("type" to "site_status"))))
        assertNull(WatchdogAlert.from(push("degraded", mapOf("id" to ""))))
        assertNull(WatchdogAlert.from(push("", emptyMap())))
        assertEquals("jellyfin", WatchdogAlert.from(push("degraded", mapOf("name" to "")))!!.name)
    }

    @Test
    fun `events map to channels`() {
        for (e in listOf("degraded", "heal_failed", "given_up", "crashed", "loose_unnamed", "job_failed")) {
            assertEquals(e, WatchdogLevel.ALERT, WatchdogAlert.levelOf(e))
        }
        for (e in listOf("healing", "healed", "recovered", "filed")) {
            assertEquals(e, WatchdogLevel.UPDATE, WatchdogAlert.levelOf(e))
        }
        for (e in listOf("paused", "resumed", "brand_new")) assertNull(e, WatchdogAlert.levelOf(e))
    }

    @Test
    fun `titles`() {
        fun t(e: String) = WatchdogAlert.from(push(e))!!.title()
        assertEquals("Jellyfin is down", t("degraded"))
        assertEquals("Jellyfin was restarted", t("healing"))
        assertEquals("Jellyfin is back", t("healed"))
        assertEquals("Jellyfin is back", t("recovered"))
        assertEquals("Jellyfin crashed", t("crashed"))
        assertEquals("Watchdog gave up on Jellyfin", t("given_up"))
        assertEquals("Jellyfin: filed a loose file", t("filed"))
    }

    @Test
    fun `status decodes snake_case`() {
        val raw = """{"queued_events":0,"targets":[
          {"id":"jf","name":"Jellyfin","enabled":true,"state":"down",
           "last_check":{"at":1794753196.5,"ok":false,"results":[
             {"label":"http","ok":false,"detail":"timeout","latency_ms":10000}]},
           "consecutive_failures":3,"restarts_in_window":2,
           "last_heal":{"at":1794753100.0,"ok":true,"detail":"restarted"},"paused_until":null},
          {"id":"x","name":"X","enabled":true,"state":"unknown","last_check":null,
           "consecutive_failures":0,"restarts_in_window":0,"last_heal":null,"paused_until":1794754000.0}]}"""
        val s = SysMonJson.decodeFromString(WatchdogStatus.serializer(), raw)
        assertEquals(2, s.targets.size)
        val t = s.targets[0]
        assertEquals("down", t.state)
        assertEquals(3, t.consecutiveFailures)
        assertEquals(2, t.restartsInWindow)
        assertEquals("timeout", t.lastCheck!!.results[0].detail)
        assertEquals(10000L, t.lastCheck!!.results[0].latencyMs)
        assertEquals("restarted", t.lastHeal!!.detail)
        assertNull(t.pausedUntil)
        assertEquals(1794754000.0, s.targets[1].pausedUntil!!, 0.0)
        assertNull(s.targets[1].lastCheck)
    }

    @Test
    fun `events decode and pause body encodes`() {
        val raw = """{"events":[{"eid":"e1","event":"given_up","id":"jf","name":"Jellyfin",
          "detail":"d","state":"given_up","ts":1794753196.0,"received_at":1794753197.0,"pushed":true}]}"""
        val e = SysMonJson.decodeFromString(WatchdogEventsResponse.serializer(), raw).events.single()
        assertEquals("given_up", e.event)
        assertEquals(1794753197.0, e.receivedAt, 0.0)
        assertEquals("""{"minutes":30}""", SysMonJson.encodeToString(WatchdogPauseBody.serializer(), WatchdogPauseBody(30)))
    }

    @Test
    fun `state text`() {
        // 1794753196 = 2026-11-15 14:33:16 UTC
        fun t(state: String, paused: Double? = null, restarts: Int = 0, err: String? = null) = WatchdogTarget(
            state = state, pausedUntil = paused, restartsInWindow = restarts,
            lastCheck = err?.let {
                com.raavivi.sysmon.core.model.WatchdogLastCheck(
                    results = listOf(com.raavivi.sysmon.core.model.WatchdogCheckResult(ok = false, detail = it)),
                )
            },
        )
        assertEquals("Healthy", HealthText.stateText(t("healthy"), utc))
        assertEquals("Down - timeout", HealthText.stateText(t("down", err = "timeout"), utc))
        assertEquals("Down", HealthText.stateText(t("down"), utc))
        assertEquals("Paused until 14:33", HealthText.stateText(t("paused", paused = 1794753196.0), utc))
        assertEquals("Gave up after 3 restarts", HealthText.stateText(t("given_up", restarts = 3), utc))
        assertEquals("Gave up after 1 restart", HealthText.stateText(t("given_up", restarts = 1), utc))
        assertEquals("Not checked yet", HealthText.stateText(t("unknown"), utc))
        assertEquals("Odd state", HealthText.stateText(t("odd_state"), utc))
        assertNull(HealthText.restartsText(t("down")))
        assertEquals("2 restarts in the window", HealthText.restartsText(t("down", restarts = 2)))
    }

    @Test
    fun `humanSpan picks coarse units`() {
        assertEquals("0 s", humanSpan(-5))
        assertEquals("12 min", humanSpan(720))
        assertEquals("3 h 20 min", humanSpan(12_000))
        assertEquals("2 d 4 h", humanSpan(2 * 86_400L + 4 * 3600L + 5))
    }
}
