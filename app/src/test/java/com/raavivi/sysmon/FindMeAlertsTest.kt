package com.raavivi.sysmon

import com.raavivi.sysmon.core.model.FindMeDevice
import com.raavivi.sysmon.core.model.FindMeDevicesResponse
import com.raavivi.sysmon.core.net.SysMonJson
import com.raavivi.sysmon.core.push.FindMeAlert
import com.raavivi.sysmon.ui.common.Severity
import com.raavivi.sysmon.ui.findme.formatSince
import com.raavivi.sysmon.ui.findme.statusSeverity
import com.raavivi.sysmon.ui.findme.statusText
import com.raavivi.sysmon.ui.findme.streetViewLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class FindMeAlertsTest {

    private val zone: ZoneId = ZoneId.of("Africa/Johannesburg")
    private val uk: Locale = Locale.UK

    /** Unix seconds for a wall-clock time in [zone]. */
    private fun at(day: Int, h: Int, m: Int): Double =
        ZonedDateTime.of(2026, 11, day, h, m, 0, 0, zone).toEpochSecond().toDouble()

    private val now = at(8, 23, 30)

    // The server's watcher payload: every value arrives as a string.
    private fun push(vararg extra: Pair<String, String>): Map<String, String> = mapOf(
        "type" to "findme_device",
        "event" to "unreachable",
        "device_id" to "fm_1",
        "name" to "Pixel 8",
        "last_seen" to at(8, 14, 2).toString(),
        "battery_pct" to "23",
        "lat" to "-26.1076",
        "lon" to "28.0567",
        "ts" to now.toString(),
    ) + extra

    @Test
    fun parsesTheWatcherPayload() {
        val a = FindMeAlert.from(push())!!
        assertTrue(a.unreachable)
        assertEquals("fm_1", a.deviceId)
        assertEquals("Pixel 8", a.name)
        assertEquals(at(8, 14, 2), a.lastSeen!!, 0.0)
        assertEquals(23, a.batteryPct)
        assertEquals(-26.1076, a.lat!!, 1e-9)
        assertEquals(28.0567, a.lon!!, 1e-9)
    }

    @Test
    fun rejectsOtherTypesUnknownEventsAndMissingIds() {
        assertNull(FindMeAlert.from(push("type" to "plug_alert")))
        assertNull(FindMeAlert.from(push("event" to "moved")))
        assertNull(FindMeAlert.from(push() - "event"))
        assertNull(FindMeAlert.from(push("device_id" to " ")))
        assertNull(FindMeAlert.from(push() - "device_id"))
    }

    @Test
    fun badNumbersReadAsAbsent() {
        val a = FindMeAlert.from(push("battery_pct" to "n/a", "lat" to "NaN", "last_seen" to ""))!!
        assertNull(a.batteryPct)
        assertNull(a.lat)
        assertNull(a.lastSeen)
        assertEquals("A device", FindMeAlert.from(push("name" to ""))!!.name)
        assertEquals(24, FindMeAlert.from(push("battery_pct" to "23.6"))!!.batteryPct)
    }

    @Test
    fun unreachableTextNamesTheLastSeenTimeAndBattery() {
        val a = FindMeAlert.from(push())!!
        assertEquals("Pixel 8 is unreachable", a.title())
        assertEquals("Last seen 14:02 (battery 23%)", a.text(zone, uk))
        assertEquals("Last seen 14:02", FindMeAlert.from(push() - "battery_pct")!!.text(zone, uk))
        assertEquals("Not seen recently", FindMeAlert.from(push() - "last_seen" - "battery_pct")!!.text(zone, uk))
        val overnight = FindMeAlert.from(push("last_seen" to at(7, 22, 15).toString()))!!
        assertEquals("Last seen yesterday 22:15 (battery 23%)", overnight.text(zone, uk))
    }

    @Test
    fun reachableSaysTheDeviceIsBack() {
        val a = FindMeAlert.from(push("event" to "reachable", "last_seen" to at(8, 15, 30).toString()))!!
        assertFalse(a.unreachable)
        assertEquals("Pixel 8 is back", a.title())
        assertEquals("Reporting again since 15:30", a.text(zone, uk))
    }

    @Test
    fun sinceShowsTheDayWhenItIsNotToday() {
        assertEquals("13:05", formatSince(at(8, 13, 5), now, zone, uk))
        assertEquals("yesterday 11:27", formatSince(at(7, 11, 27), now, zone, uk))
        assertEquals("Thu 5 Nov 09:00", formatSince(at(5, 9, 0), now, zone, uk))
        // Just after local midnight the previous evening is already "yesterday".
        assertEquals("yesterday 23:50", formatSince(at(7, 23, 50), at(8, 0, 10), zone, uk))
    }

    @Test
    fun statusTextAndSeverityFollowTheLiveStatus() {
        fun dev(status: String?, since: Double? = null, seen: Double? = null) =
            FindMeDevice(id = "d", name = "D", status = status, statusSince = since, lastSeen = seen)
        assertEquals("Moving", statusText(dev("moving"), now, zone, uk))
        assertEquals("Stationary since 13:05", statusText(dev("stationary", since = at(8, 13, 5)), now, zone, uk))
        assertEquals("Stationary", statusText(dev("stationary"), now, zone, uk))
        assertEquals(
            "Unreachable - last seen yesterday 22:15",
            statusText(dev("unreachable", seen = at(7, 22, 15)), now, zone, uk),
        )
        assertEquals("Unreachable", statusText(dev("unreachable"), now, zone, uk))
        assertEquals("Status unknown", statusText(dev("unknown"), now, zone, uk))

        assertEquals(Severity.Warning, statusSeverity("unreachable"))
        assertEquals(Severity.Nominal, statusSeverity("moving"))
        assertEquals(Severity.Nominal, statusSeverity("stationary"))
        assertEquals(Severity.Offline, statusSeverity("unknown"))
        assertEquals(Severity.Offline, statusSeverity(null))
    }

    @Test
    fun streetViewLinksUseSixDecimals() {
        val l = streetViewLinks(-33.9, 18.42)
        assertEquals("https://www.google.com/maps/@?api=1&map_action=pano&viewpoint=-33.900000,18.420000", l.google)
        assertEquals("https://www.mapillary.com/app/?lat=-33.900000&lng=18.420000&z=17", l.mapillary)
    }

    @Test
    fun decodesLiveStatusFieldsAndToleratesOlderServers() {
        val json = """
            {"devices":[
              {"id":"a","name":"A","status":"stationary","status_since":1794043620.0,
               "stationary_at":{"lat":-26.1,"lon":28.05,"radius_m":15.0}},
              {"id":"b","name":"B"}
            ]}
        """.trimIndent()
        val r = SysMonJson.decodeFromString(FindMeDevicesResponse.serializer(), json)
        val a = r.devices[0]
        assertEquals("stationary", a.status)
        assertEquals(1794043620.0, a.statusSince!!, 0.0)
        assertEquals(15.0, a.stationaryAt!!.radiusM, 0.0)
        assertEquals(-26.1, a.stationaryAt!!.lat, 0.0)
        assertNull(r.devices[1].status)
        assertNull(r.devices[1].stationaryAt)
    }
}
