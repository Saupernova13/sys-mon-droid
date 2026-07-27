package com.raavivi.sysmon

import com.raavivi.sysmon.core.model.PowerCalendarResponse
import com.raavivi.sysmon.core.model.PowerDevicePatch
import com.raavivi.sysmon.core.model.PowerDevicesBody
import com.raavivi.sysmon.core.model.PowerDevicesResponse
import com.raavivi.sysmon.core.model.PowerSchedule
import com.raavivi.sysmon.core.model.PowerSchedulesBody
import com.raavivi.sysmon.core.model.PowerSchedulesResponse
import com.raavivi.sysmon.core.model.RefreshResponse
import com.raavivi.sysmon.core.net.SysMonJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the plug-configuration DTOs — calendar, schedules, device manager, token
 * refresh — to the JSON the Rust backend emits.
 *
 * These fixtures are built from the `json!` literals in `power::fetch_calendar`,
 * `power_schedules::to_json`, `routes::power_admin::masked` and
 * `routes::auth::refresh` rather than captured from a live host, so keep them in
 * step with those four sites. Plug ids match this deployment (`default`,
 * `heater`, `fan-airfryer`).
 */
class PowerConfigDecodeTest {

    // ── calendar ──────────────────────────────────────────────────────────────

    @Test
    fun `calendar decodes days with offline hours as nulls`() {
        val r = SysMonJson.decodeFromString(PowerCalendarResponse.serializer(), CALENDAR_JSON)
        assertEquals("2026-07", r.month)
        assertEquals("all", r.plug)
        assertEquals("R", r.currency)
        assertEquals(3.8152, r.effectiveRate, 0.0001)
        assertEquals(2, r.days.size)

        val first = r.days[0]
        assertEquals("2026-07-01", first.date)
        assertEquals(1, first.dayOfMonth)
        assertEquals(4.212, first.kwh, 0.0001)
        assertEquals(16.07, first.cost, 0.0001)
        // Always 24 slots; a null hour is "plug offline", not "drew nothing".
        assertEquals(24, first.hours.size)
        assertNull(first.hours[3])
        assertEquals(212.4, first.hours[0]!!, 0.0001)

        assertEquals(9.06, r.monthKwh, 0.0001)
        assertEquals(34.56, r.monthCost, 0.0001)
    }

    @Test
    fun `calendar decodes a month with no recorded days`() {
        val json =
            """{"month":"2026-01","plug":"heater","currency":"R","effective_rate":3.8152,"days":[],"month_kwh":0.0,"month_cost":0.0}"""
        val r = SysMonJson.decodeFromString(PowerCalendarResponse.serializer(), json)
        assertEquals("heater", r.plug)
        assertTrue(r.days.isEmpty())
        assertEquals(0.0, r.monthKwh, 0.0001)
    }

    // ── schedules ─────────────────────────────────────────────────────────────

    @Test
    fun `schedules decode with weekday indices and wrapping windows`() {
        val r = SysMonJson.decodeFromString(PowerSchedulesResponse.serializer(), SCHEDULES_JSON)
        assertEquals(2, r.schedules.size)

        val work = r.schedules[0]
        assertEquals("heater-1", work.id)
        assertEquals("heater", work.plug)
        assertEquals("Work hours", work.label)
        assertEquals("08:00", work.on)
        assertEquals("18:00", work.off)
        assertEquals(listOf(0, 1, 2, 3, 4), work.days)
        assertTrue(work.enabled)

        // A window that wraps past midnight is just off < on; nothing special.
        val overnight = r.schedules[1]
        assertEquals("22:00", overnight.on)
        assertEquals("06:00", overnight.off)
        assertEquals(PowerSchedule.ALL_DAYS, overnight.days)
        assertFalse(overnight.enabled)
    }

    /**
     * The regression that motivated `@EncodeDefault`: the shared JSON config has
     * `encodeDefaults = false`, so a window left at the default 08:00/18:00 would
     * serialize without `on`/`off` — and the server drops any row it can't parse
     * a time from, silently discarding the window on save.
     */
    @Test
    fun `saving a default-valued window still sends every field`() {
        val body = PowerSchedulesBody(
            plug = "heater",
            schedules = listOf(PowerSchedule(plug = "heater")),
        )
        val json = SysMonJson.encodeToString(PowerSchedulesBody.serializer(), body)
        assertTrue("on time must survive encoding", json.contains("\"on\":\"08:00\""))
        assertTrue("off time must survive encoding", json.contains("\"off\":\"18:00\""))
        assertTrue("enabled must survive encoding", json.contains("\"enabled\":true"))
        assertTrue("days must survive encoding", json.contains("\"days\":[0,1,2,3,4,5,6]"))
        // A new row carries a blank id — that is the server's cue to assign one.
        assertTrue(json.contains("\"id\":\"\""))
    }

    @Test
    fun `schedule bodies round-trip through the server shape`() {
        val original = PowerSchedule(
            id = "heater-2",
            plug = "heater",
            label = "Overnight",
            on = "22:00",
            off = "06:00",
            days = listOf(5, 6),
            enabled = false,
        )
        val encoded = SysMonJson.encodeToString(PowerSchedule.serializer(), original)
        assertEquals(original, SysMonJson.decodeFromString(PowerSchedule.serializer(), encoded))
    }

    // ── device manager ────────────────────────────────────────────────────────

    @Test
    fun `devices decode with masked passwords and alert flags`() {
        val r = SysMonJson.decodeFromString(PowerDevicesResponse.serializer(), DEVICES_JSON)
        assertEquals(3, r.devices.size)

        val pc = r.devices[0]
        assertEquals("default", pc.id)
        assertEquals("PC Plug", pc.display)
        assertTrue("the PC plug is guarded against automation", pc.protected)
        assertFalse(pc.alert)
        assertTrue(pc.hasPassword)

        val heater = r.devices[1]
        assertTrue(heater.alert)
        assertEquals("space heater", heater.alertLabel)
        assertFalse(heater.hasPassword)

        // A row written before alerts existed reads as "no alert", never as on.
        assertFalse(r.devices[2].alert)
        assertEquals("", r.devices[2].alertLabel)
    }

    /**
     * The device POST replaces the whole list, so an alert edit has to send every
     * plug back. Omitting a key means "keep what you stored" server-side — which
     * is the only reason the client can save without ever holding the passwords.
     */
    @Test
    fun `device patch omits the password and any untouched flag`() {
        val body = PowerDevicesBody(
            listOf(
                PowerDevicePatch(
                    id = "heater",
                    name = "Heater",
                    url = "http://127.0.0.1:9802",
                    user = "",
                    enabled = true,
                    alert = true,
                    alertLabel = "space heater",
                ),
            ),
        )
        val json = SysMonJson.encodeToString(PowerDevicesBody.serializer(), body)
        assertFalse("a masked password must never round-trip", json.contains("password"))
        assertTrue(json.contains("\"alert\":true"))
        assertTrue(json.contains("\"alert_label\":\"space heater\""))
        // `protected` was not edited here, so it stays absent and the server keeps
        // whatever it had rather than clearing the guard.
        assertFalse(json.contains("protected"))
    }

    @Test
    fun `device patch can turn an alert off explicitly`() {
        val body = PowerDevicesBody(
            listOf(
                PowerDevicePatch(
                    id = "heater",
                    name = "Heater",
                    url = "http://127.0.0.1:9802",
                    user = "",
                    enabled = true,
                    alert = false,
                ),
            ),
        )
        val json = SysMonJson.encodeToString(PowerDevicesBody.serializer(), body)
        // false is not the property default (null), so it encodes — unchecking
        // has to actually stick.
        assertTrue(json.contains("\"alert\":false"))
    }

    // ── token refresh ─────────────────────────────────────────────────────────

    @Test
    fun `refresh response decodes`() {
        val json = """{"token":"eyJhbGciOiJIUzI1NiJ9.stub.sig","role":"admin","expires_in":259200}"""
        val r = SysMonJson.decodeFromString(RefreshResponse.serializer(), json)
        assertEquals("admin", r.role)
        assertEquals(259200L, r.expiresIn)
        assertTrue(r.token.isNotBlank())
    }

    private companion object {
        // power::fetch_calendar — `hours` is a fixed 24-slot array of Option<f64>.
        const val CALENDAR_JSON = """{"month":"2026-07","plug":"all","currency":"R","effective_rate":3.8152,"days":[{"date":"2026-07-01","kwh":4.212,"cost":16.07,"hours":[212.4,198.1,203.7,null,null,0.0,12.5,180.2,1920.6,1885.3,240.1,238.9,231.0,229.4,233.7,240.2,251.8,1902.4,1877.1,320.5,298.2,275.6,244.8,220.1]},{"date":"2026-07-02","kwh":4.848,"cost":18.49,"hours":[210.0,205.5,201.2,199.8,198.4,200.1,215.6,1940.2,1912.7,260.3,248.9,241.6,236.2,233.1,235.8,242.4,258.9,1899.5,1888.2,331.7,305.4,281.3,250.2,225.9]}],"month_kwh":9.06,"month_cost":34.56}"""

        // power_schedules::to_json_list — days is 0=Mon .. 6=Sun.
        const val SCHEDULES_JSON = """{"schedules":[{"id":"heater-1","plug":"heater","label":"Work hours","on":"08:00","off":"18:00","days":[0,1,2,3,4],"enabled":true},{"id":"heater-2","plug":"heater","label":"Overnight","on":"22:00","off":"06:00","days":[0,1,2,3,4,5,6],"enabled":false}]}"""

        // routes::power_admin::masked — passwords collapse to has_password.
        const val DEVICES_JSON = """{"devices":[{"id":"default","name":"PC Plug","url":"http://127.0.0.1:9801","user":"admin","enabled":true,"has_password":true,"protected":true,"alert":false,"alert_label":""},{"id":"heater","name":"Heater","url":"http://127.0.0.1:9802","user":"","enabled":true,"has_password":false,"protected":false,"alert":true,"alert_label":"space heater"},{"id":"fan-airfryer","name":"Fan - Airfryer","url":"http://127.0.0.1:9803","user":"","enabled":true,"has_password":false,"protected":false,"alert":false,"alert_label":""}]}"""
    }
}
