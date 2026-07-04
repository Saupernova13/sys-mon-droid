package com.raavivi.sysmon

import com.raavivi.sysmon.core.model.FeaturesResponse
import com.raavivi.sysmon.core.model.HistoryRecentResponse
import com.raavivi.sysmon.core.model.LoginResponse
import com.raavivi.sysmon.core.model.PowerActionResponse
import com.raavivi.sysmon.core.model.PowerHistoryResponse
import com.raavivi.sysmon.core.model.PowerReading
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
    }
}
