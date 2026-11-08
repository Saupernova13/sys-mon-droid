package com.raavivi.sysmon

import com.raavivi.sysmon.core.model.FindMeCommand
import com.raavivi.sysmon.core.model.FindMeCommandResponse
import com.raavivi.sysmon.core.model.FindMeDevice
import com.raavivi.sysmon.core.model.FindMeDevicesResponse
import com.raavivi.sysmon.core.model.FindMeFix
import com.raavivi.sysmon.core.model.FindMeHistoryResponse
import com.raavivi.sysmon.core.model.FindMePairBody
import com.raavivi.sysmon.core.model.FindMePairResponse
import com.raavivi.sysmon.core.model.FindMePatchBody
import com.raavivi.sysmon.core.model.FeaturesResponse
import com.raavivi.sysmon.core.net.SysMonJson
import com.raavivi.sysmon.ui.common.Severity
import com.raavivi.sysmon.ui.findme.CommandPhase
import com.raavivi.sysmon.ui.findme.anyPending
import com.raavivi.sysmon.ui.findme.batterySeverity
import com.raavivi.sysmon.ui.findme.countdown
import com.raavivi.sysmon.ui.findme.deviceSeverity
import com.raavivi.sysmon.ui.findme.fixAgeSeverity
import com.raavivi.sysmon.ui.findme.formatAccuracy
import com.raavivi.sysmon.ui.findme.formatAge
import com.raavivi.sysmon.ui.findme.formatCode
import com.raavivi.sysmon.ui.findme.geocodeKey
import com.raavivi.sysmon.ui.findme.locateStatus
import com.raavivi.sysmon.ui.findme.newDevices
import com.raavivi.sysmon.ui.findme.parseInterval
import com.raavivi.sysmon.ui.findme.pollDelayMs
import com.raavivi.sysmon.ui.findme.ringStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DTO shapes from API.md "Find Me" plus the pending-command display state. */
class FindMeTest {

    private val now = 1_700_000_000.0

    private fun cmd(kind: String, state: String, createdAt: Double = now - 5, doneAt: Double? = null, error: String? = null) =
        FindMeCommand(id = "c-$kind-$state", kind = kind, createdAt = createdAt, state = state, doneAt = doneAt, error = error)

    private fun device(
        commands: List<FindMeCommand> = emptyList(),
        ringing: Boolean = false,
        fixTs: Double? = now - 30,
        battery: Double? = 80.0,
        charging: Boolean? = false,
    ) = FindMeDevice(
        id = "d1",
        name = "Pixel",
        intervalS = 300,
        ringing = ringing,
        lastFix = fixTs?.let { FindMeFix(ts = it, lat = 1.0, lon = 2.0) },
        batteryPct = battery,
        charging = charging,
        commands = commands,
    )

    // ---- DTO decoding ---------------------------------------------------------

    @Test
    fun `devices response decodes`() {
        val json = """
            {"devices":[{"id":"d1","name":"Pixel 8","platform":"android","model":"Pixel 8","app_version":"1.0.0",
              "created_at":1700000000.5,"last_seen":1700000100.0,"interval_s":300,"push":true,"ringing":true,
              "last_fix":{"ts":1700000090.0,"lat":-33.9249,"lon":18.4241,"accuracy_m":12.5,"altitude_m":null,
                "speed_mps":0.0,"bearing_deg":null,"provider":"fused","source":"locate","battery_pct":81,
                "charging":true,"network":"wifi"},
              "battery_pct":81,"charging":true,"network":"wifi",
              "commands":[{"id":"c1","kind":"ring","created_at":1700000050.0,"state":"delivered",
                "delivered_at":1700000055.0,"done_at":null,"error":null}]}],
             "retention_days":30,"push_configured":true}
        """.trimIndent()
        val r = SysMonJson.decodeFromString(FindMeDevicesResponse.serializer(), json)
        assertEquals(1, r.devices.size)
        val d = r.devices[0]
        assertEquals("Pixel 8", d.name)
        assertEquals(300, d.intervalS)
        assertTrue(d.ringing)
        assertEquals(-33.9249, d.lastFix!!.lat, 1e-9)
        assertEquals(12.5, d.lastFix!!.accuracyM!!, 1e-9)
        assertNull(d.lastFix!!.altitudeM)
        assertEquals("wifi", d.network)
        assertEquals(81.0, d.batteryPct!!, 1e-9)
        assertEquals("ring", d.commands[0].kind)
        assertEquals("delivered", d.commands[0].state)
        assertNull(d.commands[0].doneAt)
        assertEquals(30, r.retentionDays)
        assertTrue(r.pushConfigured)
    }

    @Test
    fun `device without a fix decodes`() {
        val r = SysMonJson.decodeFromString(
            FindMeDevicesResponse.serializer(),
            """{"devices":[{"id":"d2","name":"Tablet","last_fix":null,"commands":[]}],"retention_days":7,"push_configured":false}""",
        )
        assertNull(r.devices[0].lastFix)
        assertFalse(r.pushConfigured)
    }

    @Test
    fun `history decodes oldest first`() {
        val r = SysMonJson.decodeFromString(
            FindMeHistoryResponse.serializer(),
            """{"fixes":[{"ts":1.0,"lat":1.0,"lon":2.0},{"ts":2.0,"lat":1.5,"lon":2.5,"accuracy_m":8,"source":"trail"}]}""",
        )
        assertEquals(2, r.fixes.size)
        assertEquals(1.0, r.fixes[0].ts, 0.0)
        assertEquals("trail", r.fixes[1].source)
    }

    @Test
    fun `pair and command responses decode`() {
        val pair = SysMonJson.decodeFromString(
            FindMePairResponse.serializer(),
            """{"code":"ABCDEFGH","expires_at":1700000600.0,"link":"findme://pair?server=x&code=ABCDEFGH","qr_svg":"<svg/>"}""",
        )
        assertEquals("ABCDEFGH", pair.code)
        assertEquals(1700000600.0, pair.expiresAt, 0.0)
        assertEquals("<svg/>", pair.qrSvg)

        val c = SysMonJson.decodeFromString(
            FindMeCommandResponse.serializer(),
            """{"command":{"id":"c9","kind":"locate","created_at":5.0,"state":"pending"},"pushed":false}""",
        )
        assertEquals("locate", c.command.kind)
        assertEquals("pending", c.command.state)
        assertFalse(c.pushed)
    }

    @Test
    fun `request bodies use snake case and omit unset fields`() {
        assertEquals(
            """{"server":"https://sys.example"}""",
            SysMonJson.encodeToString(FindMePairBody.serializer(), FindMePairBody(server = "https://sys.example")),
        )
        assertEquals(
            """{"interval_s":120}""",
            SysMonJson.encodeToString(FindMePatchBody.serializer(), FindMePatchBody(intervalS = 120)),
        )
    }

    @Test
    fun `features flag defaults off and decodes`() {
        assertFalse(SysMonJson.decodeFromString(FeaturesResponse.serializer(), "{}").findme)
        assertTrue(SysMonJson.decodeFromString(FeaturesResponse.serializer(), """{"findme":true}""").findme)
    }

    // ---- Pending-command display state ---------------------------------------

    @Test
    fun `no commands means no status`() {
        assertNull(locateStatus(device(), now))
        assertNull(ringStatus(device(), now))
    }

    @Test
    fun `open locate shows locating`() {
        for (state in listOf("pending", "delivered")) {
            val s = locateStatus(device(listOf(cmd("locate", state))), now)!!
            assertEquals(CommandPhase.Pending, s.phase)
            assertEquals("Locating...", s.text)
        }
    }

    @Test
    fun `finished locate shows a result that fades after ten minutes`() {
        val done = cmd("locate", "done", doneAt = now - 90)
        assertEquals("Located 1 min ago", locateStatus(device(listOf(done)), now)!!.text)
        assertNull(locateStatus(device(listOf(done)), now + 700))

        val failed = locateStatus(device(listOf(cmd("locate", "failed", doneAt = now - 5, error = "no GPS"))), now)!!
        assertEquals(CommandPhase.Failed, failed.phase)
        assertEquals("Locate failed: no GPS", failed.text)

        val expired = locateStatus(device(listOf(cmd("locate", "expired"))), now)!!
        assertEquals(CommandPhase.Expired, expired.phase)
        assertEquals("No answer - phone offline?", expired.text)
    }

    @Test
    fun `ring status prefers stopping then ringing then history`() {
        val stopping = ringStatus(
            device(listOf(cmd("stop_ring", "pending"), cmd("ring", "done")), ringing = true), now,
        )!!
        assertEquals(CommandPhase.Stopping, stopping.phase)

        val ringing = ringStatus(device(listOf(cmd("ring", "delivered")), ringing = true), now)!!
        assertEquals(CommandPhase.Ringing, ringing.phase)

        val sent = ringStatus(device(listOf(cmd("ring", "pending"))), now)!!
        assertEquals(CommandPhase.Pending, sent.phase)
        assertEquals("Ring sent...", sent.text)

        assertEquals(CommandPhase.Expired, ringStatus(device(listOf(cmd("ring", "expired"))), now)!!.phase)
        assertEquals("Ring failed", ringStatus(device(listOf(cmd("ring", "failed"))), now)!!.text)
        // A finished stop leaves nothing to show.
        assertNull(ringStatus(device(listOf(cmd("stop_ring", "done"))), now))
    }

    @Test
    fun `polling goes fast while a command is open or pairing`() {
        val idle = listOf(device(listOf(cmd("locate", "done"))))
        val busy = listOf(device(listOf(cmd("ring", "pending"))))
        assertFalse(anyPending(idle))
        assertTrue(anyPending(busy))
        assertEquals(30_000L, pollDelayMs(pending = false, pairOpen = false))
        assertEquals(5_000L, pollDelayMs(pending = true, pairOpen = false))
        assertEquals(5_000L, pollDelayMs(pending = false, pairOpen = true))
    }

    // ---- Formatting and severity ---------------------------------------------

    @Test
    fun `age and accuracy formatting`() {
        assertEquals("never", formatAge(null))
        assertEquals("just now", formatAge(1.0))
        assertEquals("45 s ago", formatAge(45.0))
        assertEquals("2 min ago", formatAge(130.0))
        assertEquals("3 h ago", formatAge(3 * 3600.0 + 5))
        assertEquals("2 d ago", formatAge(2 * 86400.0))
        assertEquals("", formatAccuracy(null))
        assertEquals("±12 m", formatAccuracy(12.4))
        assertEquals("±1.2 km", formatAccuracy(1234.0))
    }

    @Test
    fun `fix and battery severity follow the web bands`() {
        assertEquals(Severity.Offline, fixAgeSeverity(null, 300))
        assertEquals(Severity.Nominal, fixAgeSeverity(100.0, 300))
        assertEquals(Severity.Caution, fixAgeSeverity(700.0, 300))
        assertEquals(Severity.Warning, fixAgeSeverity(90_000.0, 300))
        assertEquals(Severity.Nominal, batterySeverity(5.0, true))
        assertEquals(Severity.Caution, batterySeverity(18.0, false))
        assertEquals(Severity.Warning, batterySeverity(9.0, false))
        assertEquals(Severity.Offline, deviceSeverity(device(fixTs = null, battery = 5.0), now))
        assertEquals(Severity.Warning, deviceSeverity(device(battery = 5.0), now))
    }

    @Test
    fun `pairing helpers`() {
        assertEquals("ABCD-EFGH", formatCode("abcdefgh"))
        assertEquals("ABCD-EFGH", formatCode("ABCD-EFGH"))
        assertEquals("2:05", countdown(now + 125, now))
        assertEquals("0:00", countdown(now - 5, now))
        val known = setOf("d1")
        assertEquals(listOf("d2"), newDevices(known, listOf(device(), device().copy(id = "d2"))).map { it.id })
        assertEquals(120, parseInterval(" 120 "))
        assertNull(parseInterval("59"))
        assertNull(parseInterval("3601"))
        assertNull(parseInterval("abc"))
        assertEquals("-33.9249,18.4241", geocodeKey(-33.92491, 18.42409))
    }
}
