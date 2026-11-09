package com.raavivi.sysmon

import com.raavivi.sysmon.core.tracker.model.AckBody
import com.raavivi.sysmon.core.tracker.model.CommandsResponse
import com.raavivi.sysmon.core.tracker.model.Fix
import com.raavivi.sysmon.core.tracker.model.PairBody
import com.raavivi.sysmon.core.tracker.model.PairResponse
import com.raavivi.sysmon.core.tracker.model.PushBody
import com.raavivi.sysmon.core.tracker.model.ReportBody
import com.raavivi.sysmon.core.tracker.model.ReportResponse
import com.raavivi.sysmon.core.tracker.net.TrackerJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerContractJsonTest {

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun reportBodyUsesSnakeCaseAndAlwaysSendsRinging() {
        val body = ReportBody(
            fixes = listOf(
                Fix(
                    ts = 1700000000.0, lat = 1.5, lon = 2.5, accuracyM = 10.0, altitudeM = 5.0,
                    speedMps = 0.5, bearingDeg = 180.0, provider = "fused", source = "trail",
                    batteryPct = 70, charging = false, network = "cellular",
                ),
            ),
            batteryPct = 70,
            charging = false,
            network = "cellular",
            appVersion = "0.1.0",
            ringing = false,
        )
        val json = obj(TrackerJson.encodeToString(ReportBody.serializer(), body))
        assertEquals(70, json["battery_pct"]!!.jsonPrimitive.content.toInt())
        assertFalse(json["charging"]!!.jsonPrimitive.boolean)
        assertEquals("0.1.0", json["app_version"]!!.jsonPrimitive.content)
        assertFalse(json["ringing"]!!.jsonPrimitive.boolean)
        val fix = json["fixes"]!!.jsonArray[0].jsonObject
        for (key in listOf(
            "ts", "lat", "lon", "accuracy_m", "altitude_m", "speed_mps", "bearing_deg",
            "provider", "source", "battery_pct", "charging", "network",
        )) {
            assertTrue("missing $key", key in fix)
        }
        assertEquals(1700000000.0, fix["ts"]!!.jsonPrimitive.double, 0.0)
    }

    @Test
    fun heartbeatHasEmptyFixesAndOmitsNulls() {
        val json = obj(TrackerJson.encodeToString(ReportBody.serializer(), ReportBody(fixes = emptyList())))
        assertEquals(0, json["fixes"]!!.jsonArray.size)
        assertFalse("battery_pct" in json)
        assertFalse("network" in json)
        assertTrue("ringing" in json)
    }

    @Test
    fun fixOmitsUnknownOptionalFields() {
        val json = obj(TrackerJson.encodeToString(Fix.serializer(), Fix(ts = 1.0, lat = 2.0, lon = 3.0)))
        assertFalse("accuracy_m" in json)
        assertEquals("trail", json["source"]!!.jsonPrimitive.content)
    }

    @Test
    fun decodesReportResponseWithCommands() {
        val text = """
            {"ok": true, "interval_s": 120, "future_field": 1, "commands": [
              {"id": "c1", "kind": "locate", "created_at": 1700000000.5, "state": "pending",
               "delivered_at": null, "done_at": null, "error": null},
              {"id": "c2", "kind": "ring", "state": "delivered"}
            ]}
        """.trimIndent()
        val r = TrackerJson.decodeFromString(ReportResponse.serializer(), text)
        assertTrue(r.ok)
        assertEquals(120, r.intervalS)
        assertEquals(listOf("c1", "c2"), r.commands.map { it.id })
        assertEquals(listOf("locate", "ring"), r.commands.map { it.kind })
        assertNull(r.commands[0].deliveredAt)
        assertEquals(1700000000.5, r.commands[0].createdAt!!, 0.0)
    }

    @Test
    fun decodesPairResponseAndCommandsList() {
        val pair = TrackerJson.decodeFromString(
            PairResponse.serializer(),
            """{"device_id":"d1","token":"d1.secret","interval_s":300,"server_name":"homepc"}""",
        )
        assertEquals("d1", pair.deviceId)
        assertEquals("d1.secret", pair.token)
        assertEquals(300, pair.intervalS)
        assertEquals("homepc", pair.serverName)
        assertEquals(60, pair.sampleS)

        val withSample = TrackerJson.decodeFromString(
            PairResponse.serializer(),
            """{"device_id":"d1","token":"t","interval_s":300,"sample_s":30}""",
        )
        assertEquals(30, withSample.sampleS)
        val report = TrackerJson.decodeFromString(
            ReportResponse.serializer(),
            """{"ok":true,"interval_s":300,"sample_s":45,"commands":[]}""",
        )
        assertEquals(45, report.sampleS)
        assertNull(TrackerJson.decodeFromString(ReportResponse.serializer(), """{"ok":true}""").sampleS)

        val cmds = TrackerJson.decodeFromString(CommandsResponse.serializer(), """{"commands":[]}""")
        assertTrue(cmds.commands.isEmpty())
    }

    @Test
    fun requestBodiesMatchTheContract() {
        val pair = obj(
            TrackerJson.encodeToString(
                PairBody.serializer(),
                PairBody(code = "ABCDEFGH", name = "Phone", model = "Pixel", platform = "android", appVersion = "0.1.0"),
            ),
        )
        assertEquals(setOf("code", "name", "model", "platform", "app_version"), pair.keys)

        val ack = obj(TrackerJson.encodeToString(AckBody.serializer(), AckBody("failed", "No location fix")))
        assertEquals("failed", ack["state"]!!.jsonPrimitive.content)
        assertEquals("No location fix", ack["error"]!!.jsonPrimitive.content)
        assertFalse("error" in obj(TrackerJson.encodeToString(AckBody.serializer(), AckBody("done"))))

        val push = obj(TrackerJson.encodeToString(PushBody.serializer(), PushBody("tok")))
        assertEquals("tok", push["token"]!!.jsonPrimitive.content)
    }
}
