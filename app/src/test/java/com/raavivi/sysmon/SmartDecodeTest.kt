package com.raavivi.sysmon

import com.raavivi.sysmon.core.model.FeaturesResponse
import com.raavivi.sysmon.core.model.HostInfo
import com.raavivi.sysmon.core.model.RecycleClearResponse
import com.raavivi.sysmon.core.model.SmartResponse
import com.raavivi.sysmon.core.model.SystemSnapshot
import com.raavivi.sysmon.core.net.SysMonJson
import com.raavivi.sysmon.ui.dashboard.pickDisk
import com.raavivi.sysmon.ui.dashboard.smartSourceName
import com.raavivi.sysmon.ui.files.recycleClearMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the S.M.A.R.T., absent-reading and host DTOs to the documented server JSON. */
class SmartDecodeTest {

    private val smartJson = """{ "disks": [ { "id": "ST4000NM0085-1YY107ZC11NATD",
               "model": "ST4000NM0085-1YY107", "serial": "ZC11NATD",
               "firmware": "NN02", "interface": "UASP (Serial ATA)",
               "rotation": "7200 RPM", "size": "4000,7 GB",
               "letters": ["D:"], "health": "caution", "health_code": 2,
               "state": "caution", "temperature_c": 64.0,
               "power_on_hours": 68734, "power_on_count": 58,
               "life_pct": null, "scanned_at": "2026/08/17 15:26:01",
               "values": { "CurrentPendingSectorCount": "1" },
               "attributes": { "BE": "36" },
               "thresholds": { "BE": "40" },
               "failing": ["BE"] } ],
  "error": "", "rescanned": false, "probed_at": 1755442000.0,
  "installed": true, "source": "CrystalDiskInfo" }"""

    @Test
    fun `smart response decodes`() {
        val r = SysMonJson.decodeFromString(SmartResponse.serializer(), smartJson)
        val d = r.disks.single()
        assertEquals("UASP (Serial ATA)", d.interfaceName)
        assertEquals(64.0, d.temperatureC!!, 0.001)
        assertEquals(68734L, d.powerOnHours)
        assertNull(d.lifePct)
        assertEquals(listOf("BE"), d.failing)
        assertEquals("1", (d.values!!["CurrentPendingSectorCount"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("CrystalDiskInfo", r.source)
        assertTrue(r.installed)
    }

    @Test
    fun `smart failing is null when thresholds are unavailable`() {
        val r = SysMonJson.decodeFromString(
            SmartResponse.serializer(),
            """{"disks":[{"id":"a","failing":null,"values":{"Life":87}}]}""",
        )
        assertNull(r.disks[0].failing)
        assertEquals("87", (r.disks[0].values!!["Life"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `snapshot drive with smart fields`() {
        val s = SysMonJson.decodeFromString(SystemSnapshot.serializer(), snapshot(
            """{"device":"C:\\","mountpoint":"C:\\","label":"Windows","media_type":"SSD","bus_type":"NVMe",
               "model":"WD","total_bytes":10,"used_bytes":5,"free_bytes":5,"usage_pct":50.0,
               "read_bytes_sec":1.5,"write_bytes_sec":2.5,"smart_temp_c":41.0,"smart_health":"good",
               "smart_disk_id":"abc","smart_rotational":false}""",
        ))
        val d = s.disk.drives.single()
        assertEquals("Windows", d.label)
        assertEquals("NVMe", d.busType)
        assertEquals("good", d.smartHealth)
        assertEquals(false, d.smartRotational)
        assertEquals("abc", d.smartDiskId)
    }

    @Test
    fun `null rates stay null not zero`() {
        val s = SysMonJson.decodeFromString(SystemSnapshot.serializer(), snapshot(
            """{"device":"D:\\","usage_pct":10.0,"read_bytes_sec":null,"write_bytes_sec":null,
               "smart_health":null,"smart_temp_c":null}""",
        ))
        val d = s.disk.drives.single()
        assertNull(d.readBytesSec)
        assertNull(d.writeBytesSec)
        assertNull(d.smartHealth)
        assertEquals("", d.label)
    }

    @Test
    fun `null frequencies and gpu readings stay null`() {
        val s = SysMonJson.decodeFromString(
            SystemSnapshot.serializer(),
            """{"cpu":{"overall_pct":1.0,"freq_current_mhz":null,"cores":[{"id":0,"usage_pct":1.0,"freq_mhz":null}]},
               "gpu":{"available":true,"devices":[{"name":"g","usage_pct":null,"mem_used_mb":null}]},
               "ram":{},"disk":{"drives":[]}}""",
        )
        assertNull(s.cpu.freqCurrentMhz)
        assertNull(s.cpu.cores[0].freqMhz)
        assertNull(s.gpu.devices[0].usagePct)
        assertNull(s.gpu.devices[0].memUsedMb)
    }

    @Test
    fun `features payload with host and crystaldisk`() {
        val f = SysMonJson.decodeFromString(
            FeaturesResponse.serializer(),
            """{"crystaldisk":true,"settings_unreadable":true,
               "host":{"os":"linux","fs_root":"/","path_sep":"/","shell":"bash","power_delay_s":60},
               "detail":{"crystaldisk":{"enabled":true,"installed":true,"source":"smartctl","path":"/usr/sbin/smartctl"},
                         "push":{"configured":true,"devices":0,"error":"db locked"}}}""",
        )
        assertTrue(f.crystaldisk)
        assertTrue(f.settingsUnreadable)
        assertEquals("/", f.host!!.fsRoot)
        assertEquals(60, f.host!!.powerDelayS)
        assertEquals("smartctl", f.detail!!.crystaldisk.source)
        assertEquals("db locked", f.detail!!.push.error)
    }

    @Test
    fun `host info os helpers`() {
        assertTrue(HostInfo(os = "windows").isWindows)
        assertFalse(HostInfo(os = "linux").isWindows)
        assertTrue(HostInfo().isWindows)
    }

    @Test
    fun `smart source falls back by host os`() {
        assertEquals("smartctl", smartSourceName("smartctl", true))
        assertEquals("CrystalDiskInfo", smartSourceName(null, true))
        assertEquals("smartctl", smartSourceName("", false))
    }

    @Test
    fun `pickDisk prefers id then letter then first`() {
        val r = SysMonJson.decodeFromString(
            SmartResponse.serializer(),
            """{"disks":[{"id":"a","letters":["C:"]},{"id":"b","letters":["D:","/mnt/x"]}]}""",
        )
        assertEquals("b", pickDisk(r.disks, "b", null))
        assertEquals("b", pickDisk(r.disks, "zzz", "D:\\"))
        assertEquals("b", pickDisk(r.disks, null, "/mnt/x/"))
        assertEquals("a", pickDisk(r.disks, null, "Q:\\"))
    }

    @Test
    fun `recycle clear messages`() {
        assertEquals("Recycle bin emptied (3 items)", recycleClearMessage(RecycleClearResponse(deleted = 3)))
        assertEquals(
            "Emptied 2 items; 1 could not be removed",
            recycleClearMessage(RecycleClearResponse(partial = true, deleted = 2, errors = listOf("busy"))),
        )
        assertEquals(
            "Could not empty the recycle bin: locked",
            recycleClearMessage(RecycleClearResponse(ok = false, errors = listOf("locked"))),
        )
    }

    private fun snapshot(drive: String) =
        """{"cpu":{"overall_pct":1.0},"gpu":{},"ram":{},"disk":{"drives":[$drive]}}"""
}
