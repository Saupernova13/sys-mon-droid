package com.raavivi.sysmon

import com.raavivi.sysmon.core.push.AlertStyle
import com.raavivi.sysmon.core.push.PlugAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the plug-alert payload parser to what sysmon-server actually sends.
 * Fixtures mirror `alert_payload` in sysmon-server/src/main.rs — FCM data maps
 * are string-to-string, so every value here is a string exactly as it arrives.
 */
class PlugAlertParseTest {

    private fun multiPlug(
        event: String = "on",
        style: String = "separate",
    ): Map<String, String> = mapOf(
        "type" to "plug_alert",
        "event" to event,
        "style" to style,
        "ongoing" to "true",
        "currency" to "R",
        "count" to "2",
        "total_watts" to "2100.0",
        "total_cost_per_hour" to "8.01",
        "plugs" to """[
            {"id":"heater","device_name":"Heater","label":"heater","watts":1800.0,
             "cost_per_hour":6.87,"started_at":1752670000.0},
            {"id":"fan-airfryer","device_name":"Fan - Airfryer","label":"","watts":300.0,
             "cost_per_hour":1.14,"started_at":1752670500.0}
        ]""",
        "ts" to "1752671000",
        // The single-plug mirror the server still sends for old clients.
        "relay_on" to "true",
        "plug_id" to "heater",
        "device_name" to "Heater",
        "label" to "heater",
        "watts" to "1800.0",
        "cost_per_hour" to "6.87",
        "started_at" to "1752670000",
    )

    @Test
    fun parsesEveryPlugInOrder() {
        val a = PlugAlert.from(multiPlug())!!
        assertEquals("on", a.event)
        assertEquals(AlertStyle.SEPARATE, a.style)
        assertTrue(a.ongoing)
        assertEquals("R", a.currency)
        assertEquals(2, a.plugs.size)
        // Panel order is preserved, so cards don't reshuffle between pushes.
        assertEquals("heater", a.plugs[0].id)
        assertEquals("fan-airfryer", a.plugs[1].id)
        assertEquals(1800.0, a.plugs[0].watts, 0.001)
        assertEquals(6.87, a.plugs[0].costPerHour, 0.001)
        assertEquals(2100.0, a.totalWatts, 0.001)
        assertEquals(8.01, a.totalCostPerHour, 0.001)
    }

    @Test
    fun labelFallsBackToDeviceNameThenAGenericWord() {
        val a = PlugAlert.from(multiPlug())!!
        // An explicit alert_label wins: "Your heater is on", not "Your Heater".
        assertEquals("heater", a.plugs[0].display)
        // A plug with no label wording uses its device name.
        assertEquals("Fan - Airfryer", a.plugs[1].display)
        // Neither: never render "Your  is on".
        val blank = PlugAlert.from(
            multiPlug().toMutableMap().apply {
                this["plugs"] = """[{"id":"x","device_name":"","label":"","watts":5.0}]"""
            },
        )!!
        assertEquals("appliance", blank.plugs[0].display)
    }

    @Test
    fun startedAtConvertsToMillis() {
        val a = PlugAlert.from(multiPlug())!!
        // The chronometer takes millis; unix seconds would put it in 1970.
        assertEquals(1752670000000L, a.plugs[0].startedAtMs)
    }

    @Test
    fun combinedStyleIsHonoured() {
        assertEquals(AlertStyle.COMBINED, PlugAlert.from(multiPlug(style = "combined"))!!.style)
        // An unknown style degrades to the scaling one rather than dropping the
        // alert entirely.
        assertEquals(AlertStyle.SEPARATE, PlugAlert.from(multiPlug(style = "wat"))!!.style)
    }

    @Test
    fun ongoingDefaultsOnAndOnlyFalseTurnsItOff() {
        val off = PlugAlert.from(multiPlug().toMutableMap().apply { this["ongoing"] = "false" })!!
        assertEquals(false, off.ongoing)
        val absent = PlugAlert.from(multiPlug().toMutableMap().apply { remove("ongoing") })!!
        assertTrue(absent.ongoing)
    }

    @Test
    fun offMeansNothingIsOn() {
        val a = PlugAlert.from(
            mapOf(
                "type" to "plug_alert",
                "event" to "off",
                "style" to "separate",
                "ongoing" to "true",
                "currency" to "R",
                "count" to "0",
                "total_watts" to "0.0",
                "total_cost_per_hour" to "0.00",
                "plugs" to "[]",
                "relay_on" to "false",
                "watts" to "0.0",
                "cost_per_hour" to "0.00",
            ),
        )!!
        // An empty list is what tells the notifier to clear every card.
        assertTrue(a.plugs.isEmpty())
        assertEquals(0.0, a.totalWatts, 0.001)
    }

    @Test
    fun oneOfSeveralGoingOffArrivesAsTheRemainingSet() {
        // The server sends the whole picture, not a delta: the fryer is simply
        // absent. The notifier prunes cards not in this set.
        val a = PlugAlert.from(
            multiPlug(event = "update").toMutableMap().apply {
                this["plugs"] =
                    """[{"id":"heater","device_name":"Heater","label":"heater","watts":1800.0,
                        "cost_per_hour":6.87,"started_at":1752670000.0}]"""
            },
        )!!
        assertEquals(1, a.plugs.size)
        assertEquals("heater", a.plugs[0].id)
    }

    // ── an un-redeployed server (pre-multi-plug) ─────────────────────────────

    @Test
    fun oldServerSinglePlugPayloadStillRenders() {
        // Verbatim shape of the previous server's "on" push: no plugs array, no
        // style, no totals. A new app must not go silent against it.
        val a = PlugAlert.from(
            mapOf(
                "type" to "plug_alert",
                "event" to "on",
                "plug_id" to "heater",
                "device_name" to "Heater",
                "label" to "heater",
                "ongoing" to "true",
                "relay_on" to "true",
                "watts" to "1800.0",
                "cost_per_hour" to "6.87",
                "currency" to "R",
                "started_at" to "1752670000",
                "ts" to "1752671000",
            ),
        )!!
        assertEquals(1, a.plugs.size)
        assertEquals("heater", a.plugs[0].id)
        assertEquals("heater", a.plugs[0].display)
        assertEquals(1800.0, a.plugs[0].watts, 0.001)
        assertEquals(1752670000000L, a.plugs[0].startedAtMs)
        // Totals are absent from that payload, so they come off the list.
        assertEquals(1800.0, a.totalWatts, 0.001)
        assertEquals(6.87, a.totalCostPerHour, 0.001)
        // No style key: the default still renders a card.
        assertEquals(AlertStyle.SEPARATE, a.style)
    }

    @Test
    fun oldServerOffPayloadClearsEverything() {
        val a = PlugAlert.from(
            mapOf(
                "type" to "plug_alert",
                "event" to "off",
                "plug_id" to "heater",
                "device_name" to "Heater",
                "relay_on" to "false",
                "watts" to "0.0",
                "cost_per_hour" to "0.00",
            ),
        )!!
        // Without this the flat plug_id would rebuild a card on an "off" and
        // strand a notification saying the heater is on forever.
        assertTrue(a.plugs.isEmpty())
    }

    @Test
    fun junkPayloadsAreDroppedNotCrashed() {
        // No event: not something we can act on.
        assertNull(PlugAlert.from(mapOf("type" to "plug_alert")))
        // Malformed JSON must not take the FCM callback down.
        val bad = PlugAlert.from(mapOf("event" to "on", "plugs" to "{not json"))!!
        assertTrue(bad.plugs.isEmpty())
        // Entries with no id can't be stopped or tracked, so they're dropped.
        val noId = PlugAlert.from(
            mapOf("event" to "on", "plugs" to """[{"device_name":"Ghost","watts":5.0}]"""),
        )!!
        assertTrue(noId.plugs.isEmpty())
    }
}
