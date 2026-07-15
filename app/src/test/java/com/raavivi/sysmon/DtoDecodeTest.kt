package com.raavivi.sysmon

import com.raavivi.sysmon.core.model.FeaturesResponse
import com.raavivi.sysmon.core.model.HistoryRecentResponse
import com.raavivi.sysmon.core.model.LoginResponse
import com.raavivi.sysmon.core.model.PowerActionResponse
import com.raavivi.sysmon.core.model.PowerHistoryResponse
import com.raavivi.sysmon.core.model.PowerReading
import com.raavivi.sysmon.core.model.RelayResponse
import com.raavivi.sysmon.core.model.SearchResponse
import com.raavivi.sysmon.core.model.StatusResponse
import com.raavivi.sysmon.core.model.SystemSnapshot
import com.raavivi.sysmon.core.model.VerifyResponse
import com.raavivi.sysmon.core.model.VersionInfo
import com.raavivi.sysmon.core.net.SysMonJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the client DTOs to the Rust backend's actual JSON. Every fixture below
 * was captured verbatim from a live sysmon-server 1.0.0 (dev, port 11038);
 * if the server changes shape, these fail before the app ships.
 */
class DtoDecodeTest {

    @Test
    fun `history recent decodes wrapped items`() {
        val json = """{"items": [{"cpu": 2.348388671875, "disk": 56.620000000000005, "gpu": 2.0, "ram": 31.0, "ts": 1783116938.84498}, {"cpu": 1.935516357421875, "disk": 56.620000000000005, "gpu": 2.0, "ram": 31.0, "ts": 1783116943.8961523}]}"""
        val r = SysMonJson.decodeFromString(HistoryRecentResponse.serializer(), json)
        assertEquals(2, r.items.size)
        assertEquals(31.0, r.items[0].ram!!, 0.0001)
    }

    @Test
    fun `fs search decodes wrapped items with backend`() {
        val json = """{"backend": "http", "items": [{"is_dir": true, "modified": 1779225682.2180336, "name": "readme", "path": "C:\\readme", "size": null}]}"""
        val r = SysMonJson.decodeFromString(SearchResponse.serializer(), json)
        assertEquals("http", r.backend)
        assertEquals(1, r.items.size)
        assertTrue(r.items[0].isDir)
        assertNull(r.warning)
    }

    @Test
    fun `fs search decodes optional warning`() {
        val json = """{"backend": "native", "items": [], "warning": "slow fallback"}"""
        val r = SysMonJson.decodeFromString(SearchResponse.serializer(), json)
        assertEquals("slow fallback", r.warning)
    }

    @Test
    fun `power action and status envelopes`() {
        val restart = SysMonJson.decodeFromString(
            PowerActionResponse.serializer(),
            """{"status": "restart", "delay_seconds": 5}""",
        )
        assertEquals("restart", restart.status)
        assertEquals(5.0, restart.delaySeconds, 0.0001)

        val rc = SysMonJson.decodeFromString(StatusResponse.serializer(), """{"status": "launched"}""")
        assertEquals("launched", rc.status)
    }

    @Test
    fun `login and verify carry role, defaulting to admin`() {
        val login = SysMonJson.decodeFromString(
            LoginResponse.serializer(),
            """{"role":"viewer","token":"abc"}""",
        )
        assertEquals("viewer", login.role)

        val legacy = SysMonJson.decodeFromString(LoginResponse.serializer(), """{"token":"abc"}""")
        assertEquals("admin", legacy.role)

        val verify = SysMonJson.decodeFromString(
            VerifyResponse.serializer(),
            """{"role":"viewer","user":"public"}""",
        )
        assertEquals("public", verify.user)
        assertEquals("viewer", verify.role)
    }

    @Test
    fun `features payload with detail`() {
        val json = """{"detail":{"godot":{"enabled":false,"path_set":false},"model_log":{"enabled":true},"ollama_proxy":{"enabled":false,"running":false},"power":{"enabled":true,"url_set":true},"remote_control":{"shortcut_found":true},"whatsapp":{"enabled":false,"wacli_found":true}},"godot":false,"model_log":true,"ollama_proxy":false,"power":true,"remote_control":true,"whatsapp":false}"""
        val r = SysMonJson.decodeFromString(FeaturesResponse.serializer(), json)
        assertTrue(r.modelLog)
        assertFalse(r.whatsapp)
        assertTrue(r.power)
        assertTrue(r.remoteControl)
        assertNotNull(r.detail)
        assertTrue(r.detail!!.whatsapp.wacliFound)
        assertTrue(r.detail!!.power.urlSet)
        assertTrue(r.restartRequired.isEmpty())
    }

    @Test
    fun `version info`() {
        val r = SysMonJson.decodeFromString(
            VersionInfo.serializer(),
            """{"commit":"6f2cdb5","name":"Sys-Mon","version":"1.0.0"}""",
        )
        assertEquals("Sys-Mon", r.name)
        assertEquals("1.0.0", r.version)
        assertEquals("6f2cdb5", r.commit)
    }

    @Test
    fun `live power reading decodes fully`() {
        val r = SysMonJson.decodeFromString(PowerReading.serializer(), LIVE_POWER_JSON)
        assertTrue(r.available)
        assertTrue(r.configured)
        assertFalse(r.stale)
        assertEquals(140.0, r.watts, 0.0001)
        assertEquals("Moderate", r.load.label)
        assertEquals(7.0, r.load.gaugePct, 0.0001)
        assertEquals("R", r.currency)
        assertEquals(6.28, r.cost.today, 0.0001)
        assertNull(r.cost.projectedToday)
        assertEquals("Excellent", r.quality.powerFactor.label)
        assertEquals("15.5.0(release-tasmota32)", r.device.firmware)
        // Tasmota sends rssi as an integer; must still land in a Double field.
        assertEquals(100.0, r.device.wifiRssi!!, 0.0001)
        assertEquals(-29.0, r.device.wifiSignalDbm!!, 0.0001)
    }

    @Test
    fun `multi-device power envelope decodes with aggregate and devices`() {
        val r = SysMonJson.decodeFromString(PowerReading.serializer(), MULTI_POWER_JSON)
        assertTrue(r.available)
        assertTrue(r.configured)
        assertEquals("R", r.currency)
        assertEquals(3.8152, r.effectiveRate, 0.0001)
        // Envelope has no watts of its own; the headline is the aggregate.
        assertNotNull(r.aggregate)
        assertEquals(165.0, r.headline.watts, 0.0001)
        assertEquals(3, r.aggregate!!.deviceCount)
        assertEquals(2, r.aggregate!!.onlineCount)
        assertTrue(r.aggregate!!.relayOn)
        assertEquals(3, r.devices.size)
        val heater = r.deviceById("heater")!!
        assertEquals("Heater", heater.deviceName)
        assertTrue(heater.relayOn)
        assertEquals(45.0, heater.watts, 0.0001)
        // A plug the poller hasn't reached yet keeps the flat error form.
        val fan = r.deviceById("fan-airfryer")!!
        assertFalse(fan.available)
        assertEquals("starting up", fan.error)
    }

    @Test
    fun `relay switch response decodes`() {
        val r = SysMonJson.decodeFromString(
            RelayResponse.serializer(),
            """{"id":"heater","relay_on":false}""",
        )
        assertEquals("heater", r.id)
        assertFalse(r.relayOn)
    }

    @Test
    fun `unconfigured power reading decodes`() {
        val json = """{"available": false, "reachable": false, "configured": false, "error": "not configured"}"""
        val r = SysMonJson.decodeFromString(PowerReading.serializer(), json)
        assertFalse(r.available)
        assertEquals("not configured", r.error)
    }

    @Test
    fun `power history handles integer bucketed ts`() {
        val json = """{"bucket_seconds": 7200, "currency": "R", "gauge_max_w": 2000.0, "items": [{"pf": 0.9623376623376623, "ts": 1782828000, "voltage": 225.31926406926408, "watts": 96.41774891774892}], "tariff": 3.5}"""
        val r = SysMonJson.decodeFromString(PowerHistoryResponse.serializer(), json)
        assertEquals(7200L, r.bucketSeconds)
        assertEquals(2000.0, r.gaugeMaxW, 0.0001)
        assertEquals(1782828000.0, r.items[0].ts, 0.0001)
    }

    @Test
    fun `snapshot carries optional power block`() {
        val json = """{"timestamp": 1.0, "cpu": {"overall_pct": 2.0}, "gpu": {"available": false}, "ram": {"usage_pct": 31.0}, "disk": {"drives": []}, "power": $LIVE_POWER_JSON}"""
        val s = SysMonJson.decodeFromString(SystemSnapshot.serializer(), json)
        assertNotNull(s.power)
        assertEquals(140.0, s.power!!.watts, 0.0001)

        val bare = """{"timestamp": 1.0, "cpu": {"overall_pct": 2.0}, "gpu": {"available": false}, "ram": {"usage_pct": 31.0}, "disk": {"drives": []}}"""
        assertNull(SysMonJson.decodeFromString(SystemSnapshot.serializer(), bare).power)
    }

    private companion object {
        // Captured verbatim from GET /api/power-usage on sysmon-server 1.0.0.
        const val LIVE_POWER_JSON = """{"apparent_va":143.0,"available":true,"configured":true,"cost":{"per_day":11.76,"per_hour":0.49,"per_month":352.8,"projected_today":null,"projected_today_kwh":null,"projected_vs_yesterday_pct":null,"today":6.28,"total":23.58,"yesterday":7.92},"currency":"R","current":0.623,"device":{"firmware":"15.5.0(release-tasmota32)","ip":"http://192.168.0.130","uptime":"3T09:28:29","wifi_rssi":100,"wifi_signal_dbm":-29},"device_name":"Tasmota","load":{"gauge_pct":7.0,"hint":"a TV, desktop PC, or games console","label":"Moderate","level":"moderate"},"power_factor":0.98,"quality":{"power_factor":{"desc":"share of the supplied power doing real work","label":"Excellent","level":"excellent"},"reactive_pct":18.2,"voltage":{"desc":"nominal 230 V","label":"Healthy","level":"nominal"}},"reachable":true,"reactive_var":26.0,"relay_on":true,"stale":false,"tariff":3.5,"today_kwh":1.795,"total_kwh":6.736,"total_start":"2026-06-29T21:12:32","ts":1783116990.4954665,"voltage":229.0,"watts":140.0,"yesterday_kwh":2.264}"""

        // Multi-device envelope from power::latest_all(): aggregate + devices,
        // with one plug still in the "starting up" flat-error form.
        const val MULTI_POWER_JSON = """{"available":true,"configured":true,"currency":"R","effective_rate":3.8152,"aggregate":{"device_count":3,"online_count":2,"relay_on":true,"watts":165.0,"apparent_va":170.5,"reactive_var":28.0,"today_kwh":2.295,"yesterday_kwh":3.264,"total_kwh":30.736,"load":{"gauge_pct":4.1,"hint":"a fridge compressor, monitor, or vacuum","label":"Heavy","level":"heavy"},"cost":{"per_day":15.11,"per_hour":0.63,"per_month":453.28,"projected_today":22.28,"projected_today_kwh":5.84,"today":8.76,"total":117.26,"yesterday":12.45}},"devices":[{"id":"default","apparent_va":143.0,"available":true,"configured":true,"cost":{"per_day":11.76,"per_hour":0.49,"per_month":352.8,"projected_today":null,"projected_today_kwh":null,"projected_vs_yesterday_pct":null,"today":6.28,"total":23.58,"yesterday":7.92},"currency":"R","current":0.623,"device":{"firmware":"15.5.0(release-tasmota32)","ip":"http://192.168.0.130","uptime":"3T09:28:29","wifi_rssi":100,"wifi_signal_dbm":-29},"device_name":"PC Plug","load":{"gauge_pct":7.0,"hint":"a TV, desktop PC, or games console","label":"Moderate","level":"moderate"},"power_factor":0.98,"quality":{"power_factor":{"desc":"share of the supplied power doing real work","label":"Excellent","level":"excellent"},"reactive_pct":18.2,"voltage":{"desc":"nominal 230 V","label":"Healthy","level":"nominal"}},"reachable":true,"reactive_var":26.0,"relay_on":true,"stale":false,"tariff":3.5,"today_kwh":1.795,"total_kwh":6.736,"total_start":"2026-06-29T21:12:32","ts":1783116990.4954665,"voltage":229.0,"watts":120.0,"yesterday_kwh":2.264},{"id":"heater","apparent_va":47.5,"available":true,"configured":true,"cost":{"per_day":4.12,"per_hour":0.17,"per_month":123.6,"today":2.48,"total":93.68,"yesterday":4.53},"currency":"R","current":0.205,"device":{"firmware":"13.0","ip":"http://192.168.0.131","uptime":"1T02:11:09","wifi_rssi":80,"wifi_signal_dbm":-50},"device_name":"Heater","load":{"gauge_pct":2.3,"hint":"a few LED bulbs, a router, or a laptop charger","label":"Light","level":"light"},"power_factor":0.98,"quality":{"power_factor":{"desc":"share of the supplied power doing real work","label":"Excellent","level":"excellent"},"reactive_pct":10.5,"voltage":{"desc":"nominal 230 V","label":"Healthy","level":"nominal"}},"reachable":true,"reactive_var":5.0,"relay_on":true,"stale":false,"tariff":3.5,"today_kwh":0.5,"total_kwh":24.0,"total_start":"2026-06-29T21:12:32","ts":1783116990.4954665,"voltage":230.0,"watts":45.0,"yesterday_kwh":1.0},{"id":"fan-airfryer","device_name":"Fan - Airfryer","available":false,"reachable":false,"configured":true,"error":"starting up"}]}"""
    }
}
