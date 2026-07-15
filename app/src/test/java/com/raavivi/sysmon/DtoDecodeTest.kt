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
        assertEquals(120.0, r.headline.watts, 0.0001)
        assertEquals(3, r.aggregate!!.deviceCount)
        assertEquals(3, r.aggregate!!.onlineCount)
        assertTrue(r.aggregate!!.relayOn)
        assertEquals(0.46, r.aggregate!!.cost.perHour, 0.0001)
        assertEquals(3, r.devices.size)
        val pc = r.deviceById("default")!!
        assertEquals("PC Plug", pc.deviceName)
        assertTrue(pc.relayOn)
        assertEquals(120.0, pc.watts, 0.0001)
        val heater = r.deviceById("heater")!!
        assertEquals("Heater", heater.deviceName)
        assertFalse(heater.relayOn)
        assertEquals("Switched off", heater.load.label)
    }

    @Test
    fun `device still starting up keeps flat error form inside envelope`() {
        val json = """{"available":false,"configured":true,"currency":"R","effective_rate":3.8152,"devices":[{"id":"heater","device_name":"Heater","available":false,"reachable":false,"configured":true,"error":"starting up"}]}"""
        val r = SysMonJson.decodeFromString(PowerReading.serializer(), json)
        assertNull(r.aggregate)
        val heater = r.deviceById("heater")!!
        assertFalse(heater.available)
        assertEquals("starting up", heater.error)
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
        const val MULTI_POWER_JSON = """{"aggregate":{"apparent_va":120.0,"cost":{"per_day":10.99,"per_hour":0.46,"per_month":329.64,"projected_today":12.89,"projected_today_kwh":3.378,"today":5.72,"total":114.46,"yesterday":11.45},"device_count":3,"load":{"gauge_pct":2.0,"hint":"a TV, desktop PC, or games console","label":"Moderate","level":"moderate"},"online_count":3,"reactive_var":0.0,"relay_on":true,"today_kwh":1.5,"total_kwh":30.0,"watts":120.0,"yesterday_kwh":3.0},"available":true,"configured":true,"currency":"R","devices":[{"apparent_va":120.0,"available":true,"configured":true,"cost":{"per_day":10.99,"per_hour":0.46,"per_month":329.64,"projected_today":4.3,"projected_today_kwh":1.126,"projected_vs_yesterday_pct":12.6,"today":1.91,"total":38.15,"yesterday":3.82},"currency":"R","current":0.5,"device":{"firmware":"13.0","ip":"http://127.0.0.1:9801","uptime":null,"wifi_rssi":80,"wifi_signal_dbm":-50},"device_name":"PC Plug","effective_rate":3.8152,"id":"default","load":{"gauge_pct":6.0,"hint":"a TV, desktop PC, or games console","label":"Moderate","level":"moderate"},"power_factor":0.98,"quality":{"power_factor":{"desc":"share of the supplied power doing real work — the rest is reactive current the grid carries but the device doesn't consume. Low values are normal for chargers, LED drivers, and motors","label":"Excellent","level":"excellent"},"reactive_pct":0.0,"voltage":{"desc":"nominal 230 V, acceptable band 207–253.00000000000003 V","label":"Healthy","level":"nominal"}},"reachable":true,"reactive_var":0.0,"relay_on":true,"stale":false,"tariff":3.5,"tariff_tiers":[{"rate":2.4137,"up_to":100.0},{"rate":2.8247,"up_to":400.0},{"rate":3.0775,"up_to":650.0},{"rate":3.3176,"up_to":null}],"tariff_vat":0.15,"today_kwh":0.5,"total_kwh":10.0,"total_start":null,"ts":1784104767.367324,"voltage":230.0,"watts":120.0,"yesterday_kwh":1.0},{"apparent_va":0.0,"available":true,"configured":true,"cost":{"per_day":0.0,"per_hour":0.0,"per_month":0.0,"projected_today":4.3,"projected_today_kwh":1.126,"projected_vs_yesterday_pct":12.6,"today":1.91,"total":38.15,"yesterday":3.82},"currency":"R","current":0.0,"device":{"firmware":"13.0","ip":"http://127.0.0.1:9802","uptime":null,"wifi_rssi":80,"wifi_signal_dbm":-50},"device_name":"Heater","effective_rate":3.8152,"id":"heater","load":{"gauge_pct":0.0,"hint":"the plug's relay is open — no power is passing through","label":"Switched off","level":"off"},"power_factor":0.98,"quality":{"power_factor":{"desc":"too little load to judge efficiency","label":"—","level":"idle"},"reactive_pct":0.0,"voltage":{"desc":"nominal 230 V, acceptable band 207–253.00000000000003 V","label":"Healthy","level":"nominal"}},"reachable":true,"reactive_var":0.0,"relay_on":false,"stale":false,"tariff":3.5,"tariff_tiers":[{"rate":2.4137,"up_to":100.0},{"rate":2.8247,"up_to":400.0},{"rate":3.0775,"up_to":650.0},{"rate":3.3176,"up_to":null}],"tariff_vat":0.15,"today_kwh":0.5,"total_kwh":10.0,"total_start":null,"ts":1784104767.3689587,"voltage":230.0,"watts":0.0,"yesterday_kwh":1.0},{"apparent_va":0.0,"available":true,"configured":true,"cost":{"per_day":0.0,"per_hour":0.0,"per_month":0.0,"projected_today":4.3,"projected_today_kwh":1.126,"projected_vs_yesterday_pct":12.6,"today":1.91,"total":38.15,"yesterday":3.82},"currency":"R","current":0.0,"device":{"firmware":"13.0","ip":"http://127.0.0.1:9803","uptime":null,"wifi_rssi":80,"wifi_signal_dbm":-50},"device_name":"Fan - Airfryer","effective_rate":3.8152,"id":"fan-airfryer","load":{"gauge_pct":0.0,"hint":"the plug's relay is open — no power is passing through","label":"Switched off","level":"off"},"power_factor":0.98,"quality":{"power_factor":{"desc":"too little load to judge efficiency","label":"—","level":"idle"},"reactive_pct":0.0,"voltage":{"desc":"nominal 230 V, acceptable band 207–253.00000000000003 V","label":"Healthy","level":"nominal"}},"reachable":true,"reactive_var":0.0,"relay_on":false,"stale":false,"tariff":3.5,"tariff_tiers":[{"rate":2.4137,"up_to":100.0},{"rate":2.8247,"up_to":400.0},{"rate":3.0775,"up_to":650.0},{"rate":3.3176,"up_to":null}],"tariff_vat":0.15,"today_kwh":0.5,"total_kwh":10.0,"total_start":null,"ts":1784104767.370012,"voltage":230.0,"watts":0.0,"yesterday_kwh":1.0}],"effective_rate":3.8152}"""
    }
}
