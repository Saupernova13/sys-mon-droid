package com.raavivi.sysmon

import com.raavivi.sysmon.core.push.JellyfinAlert
import com.raavivi.sysmon.core.push.JellyfinAlertNotifier
import com.raavivi.sysmon.core.push.JellyfinStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the Jellyfin push parser to the server contract (string-only FCM data). */
class JellyfinStreamParseTest {

    private fun payload(event: String = "start", streams: String? = null): Map<String, String> =
        buildMap {
            put("type", "jellyfin_stream")
            put("event", event)
            put("count", "1")
            put("ts", "1752671000")
            if (streams != null) put("streams", streams)
        }

    @Test
    fun `full stream parses every field`() {
        val alert = JellyfinAlert.from(
            payload(
                streams = """[{"session_id":"abc","title":"Show - S01E02 - Name",
                    "item_type":"Episode","paused":true,"user":"sam","position_s":754,
                    "runtime_s":2700,"device":"Living Room TV","client":"Android TV",
                    "play_method":"Transcode"}]""",
            ),
        )!!
        assertEquals("start", alert.event)
        val s = alert.streams.single()
        assertEquals("abc", s.sessionId)
        assertEquals("Show - S01E02 - Name", s.title)
        assertTrue(s.paused)
        assertEquals("sam", s.user)
        assertEquals(754L, s.positionS)
        assertEquals(2700L, s.runtimeS)
        assertEquals("Living Room TV", s.device)
        assertEquals("Android TV", s.client)
    }

    @Test
    fun `optional fields may be absent`() {
        val s = JellyfinAlert.from(
            payload(streams = """[{"session_id":"abc","title":"Film","item_type":"Movie","paused":false}]"""),
        )!!.streams.single()
        assertNull(s.user)
        assertNull(s.positionS)
        assertNull(s.runtimeS)
        assertNull(s.device)
        assertNull(s.client)
        assertFalse(s.paused)
    }

    @Test
    fun `empty streams array means clear`() {
        val alert = JellyfinAlert.from(payload(event = "stop", streams = "[]"))!!
        assertEquals("stop", alert.event)
        assertTrue(alert.streams.isEmpty())
    }

    @Test
    fun `bad json reads as empty`() {
        assertTrue(JellyfinAlert.from(payload(streams = "not json"))!!.streams.isEmpty())
    }

    @Test
    fun `missing streams reads as empty`() {
        assertTrue(JellyfinAlert.from(payload())!!.streams.isEmpty())
    }

    @Test
    fun `notification text omits absent parts`() {
        val full = JellyfinStream(
            sessionId = "a", title = "Film", paused = true, user = "sam",
            positionS = 754, runtimeS = 2700, device = "TV", client = "Android TV",
        )
        assertEquals("sam is streaming on Jellyfin", JellyfinAlertNotifier.title(full))
        assertEquals(
            "Film · 12:34 / 45:00 · Paused · TV (Android TV)",
            JellyfinAlertNotifier.detail(full),
        )
        val bare = JellyfinStream(sessionId = "a", title = "Film")
        assertEquals("Someone is streaming on Jellyfin", JellyfinAlertNotifier.title(bare))
        assertEquals("Film", JellyfinAlertNotifier.detail(bare))
        assertEquals("1:02:03", JellyfinAlertNotifier.clock(3723))
    }

    @Test
    fun `missing event is unusable`() {
        assertNull(JellyfinAlert.from(mapOf("type" to "jellyfin_stream", "streams" to "[]")))
    }

    @Test
    fun `streams without a session id are dropped`() {
        val alert = JellyfinAlert.from(payload(streams = """[{"title":"x"}]"""))!!
        assertTrue(alert.streams.isEmpty())
    }
}
