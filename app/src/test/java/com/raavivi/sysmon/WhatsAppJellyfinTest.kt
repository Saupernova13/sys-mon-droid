package com.raavivi.sysmon

import com.raavivi.sysmon.core.model.JellyfinConfig
import com.raavivi.sysmon.core.model.JellyfinConfigPatch
import com.raavivi.sysmon.core.model.JellyfinNotifyPatch
import com.raavivi.sysmon.core.model.JellyfinStreamsResponse
import com.raavivi.sysmon.core.model.JellyfinTestResponse
import com.raavivi.sysmon.core.model.WaAvatarsRefreshResponse
import com.raavivi.sysmon.core.model.WaStatus
import com.raavivi.sysmon.core.model.waInitials
import com.raavivi.sysmon.core.net.SysMonJson
import com.raavivi.sysmon.ui.common.formatClock
import com.raavivi.sysmon.ui.common.formatStreamPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppJellyfinTest {

    @Test fun avatarRefreshDecodes() {
        val r = SysMonJson.decodeFromString(
            WaAvatarsRefreshResponse.serializer(),
            """{"ok":true,"updated":["a@s.whatsapp.net","b@g.us"],"attempted":40,"limit":40}""",
        )
        assertEquals(listOf("a@s.whatsapp.net", "b@g.us"), r.updated)
        assertTrue(r.attempted >= r.limit)
    }

    @Test fun statusIgnoresMissingDaemonFields() {
        val s = SysMonJson.decodeFromString(WaStatus.serializer(), """{"enabled":true,"error":"sync daemon exited (code 1): boom"}""")
        assertNull(s.daemonPhase)
        assertEquals("sync daemon exited (code 1): boom", s.error)
        val d = SysMonJson.decodeFromString(WaStatus.serializer(), """{"daemon_phase":"syncing","daemon_uptime_s":12.5}""")
        assertEquals("syncing", d.daemonPhase)
        assertEquals(12.5, d.daemonUptimeS!!, 0.001)
    }

    @Test fun initialsRule() {
        assertEquals("AB", waInitials("alice bob carol"))
        assertEquals("A", waInitials("alice"))
        assertEquals("#", waInitials("   "))
        assertEquals("#", waInitials(""))
    }

    @Test fun jellyfinConfigDecodes() {
        val c = SysMonJson.decodeFromString(
            JellyfinConfig.serializer(),
            """{"url":"http://127.0.0.1:8096","api_key_set":true,"enabled":true,"poll_interval_s":10,"notify":{"web":true,"push":false,"include_user":true,"include_title":true,"include_position":false,"include_device":true,"update_interval_s":60,"gate_power":true}}""",
        )
        assertTrue(c.apiKeySet)
        assertFalse(c.notify.push)
        assertTrue(c.notify.gatePower)
        assertEquals(60, c.notify.updateIntervalS)
    }

    @Test fun jellyfinPatchOmitsUnchangedFields() {
        val json = SysMonJson.encodeToString(
            JellyfinConfigPatch.serializer(),
            JellyfinConfigPatch(enabled = true, notify = JellyfinNotifyPatch(gatePower = true)),
        )
        assertEquals("""{"enabled":true,"notify":{"gate_power":true}}""", json)
    }

    @Test fun jellyfinTestDecodes() {
        val r = SysMonJson.decodeFromString(JellyfinTestResponse.serializer(), """{"ok":true,"server_name":"Home","version":"10.9.0"}""")
        assertEquals("Home", r.serverName)
    }

    @Test fun jellyfinStreamsDecode() {
        val r = SysMonJson.decodeFromString(
            JellyfinStreamsResponse.serializer(),
            """{"linked":true,"enabled":true,"ok":true,"error":null,"updated_at":1.7e9,"web":true,"poll_interval_s":10,"streams":[{"session_id":"s1","title":"Show - S01E02","item_type":"Episode","paused":true,"user":"sam","position_s":754,"runtime_s":2700.5,"device":"TV","client":"Android TV","play_method":"DirectPlay"},{"session_id":"s2","title":"Film","item_type":"Movie","paused":false}]}""",
        )
        assertEquals(2, r.streams.size)
        assertTrue(r.streams[0].paused)
        assertEquals(754.0, r.streams[0].positionS!!, 0.001)
        assertNull(r.streams[1].user)
        assertNull(r.streams[1].positionS)
    }

    @Test fun streamsWebFalseAndMissingFieldsDefault() {
        val r = SysMonJson.decodeFromString(JellyfinStreamsResponse.serializer(), """{"linked":false,"enabled":false,"web":false}""")
        assertFalse(r.web)
        assertTrue(r.streams.isEmpty())
    }

    @Test fun positionFormatter() {
        assertEquals("0:59", formatClock(59.0))
        assertEquals("59:59", formatClock(3599.0))
        assertEquals("1:00:00", formatClock(3600.0))
        assertEquals("12:34 / 45:00", formatStreamPosition(754.0, 2700.0))
        assertEquals("12:34", formatStreamPosition(754.0, null))
        assertEquals("", formatStreamPosition(null, 2700.0))
    }
}
