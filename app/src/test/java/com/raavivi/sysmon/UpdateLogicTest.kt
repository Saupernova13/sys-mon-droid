package com.raavivi.sysmon

import android.content.pm.PackageInstaller
import com.raavivi.sysmon.core.model.DroidUpdateResponse
import com.raavivi.sysmon.core.net.SysMonJson
import com.raavivi.sysmon.core.update.DroidUpdatePush
import com.raavivi.sysmon.core.update.Sha256
import com.raavivi.sysmon.core.update.UpdateCheck
import com.raavivi.sysmon.core.update.UpdateLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Pins the update DTO, state mapping, push parser, check throttle and SHA-256 verifier. */
class UpdateLogicTest {

    private fun decode(raw: String) = SysMonJson.decodeFromString(DroidUpdateResponse.serializer(), raw)

    private val availableJson = """{"enabled":true,"available":true,"versionCode":16,
        "versionName":"0.13.0","size":12345678,"sha256":"ABCDEF","notes":"Fixes","builtAt":"2026-11-15T10:00:00Z",
        "apkPath":"/api/droid/update/apk?v=16"}"""

    @Test
    fun `available payload decodes camelCase keys`() {
        val r = decode(availableJson)
        assertTrue(r.enabled && r.available)
        assertEquals(16L, r.versionCode)
        assertEquals("0.13.0", r.versionName)
        assertEquals(12345678L, r.size)
        assertEquals("Fixes", r.notes)
        assertEquals("2026-11-15T10:00:00Z", r.builtAt)
        assertEquals("/api/droid/update/apk?v=16", r.apkPath)
    }

    @Test
    fun `unavailable and null-note payloads decode`() {
        val off = decode("""{"enabled":false,"available":false}""")
        assertFalse(off.enabled)
        assertNull(off.versionCode)
        val nulls = decode(
            """{"enabled":true,"available":true,"versionCode":16,"versionName":"x","size":1,
            "sha256":"aa","notes":null,"builtAt":null,"apkPath":"/p"}""",
        )
        assertNull(nulls.notes)
        assertNull(nulls.builtAt)
    }

    @Test
    fun `state mapping against the installed build`() {
        assertEquals(UpdateCheck.Disabled, UpdateLogic.stateFor(decode("""{"enabled":false,"available":false}"""), 15))
        assertEquals(UpdateCheck.UpToDate, UpdateLogic.stateFor(decode("""{"enabled":true,"available":false}"""), 15))
        val a = UpdateLogic.stateFor(decode(availableJson), 15) as UpdateCheck.Available
        assertEquals(16L, a.release.versionCode)
        assertEquals("abcdef", a.release.sha256)
        assertEquals(UpdateCheck.UpToDate, UpdateLogic.stateFor(decode(availableJson), 16))
        assertEquals(UpdateCheck.UpToDate, UpdateLogic.stateFor(decode(availableJson), 20))
        assertTrue(UpdateLogic.stateFor(decode("""{"enabled":true,"available":true}"""), 15) is UpdateCheck.Error)
        assertTrue(UpdateLogic.isNewer(16, 15))
        assertFalse(UpdateLogic.isNewer(15, 15))
        assertFalse(UpdateLogic.isNewer(null, 15))
    }

    @Test
    fun `push parser`() {
        val p = DroidUpdatePush.from(
            mapOf("type" to "droid_update", "versionCode" to "16", "versionName" to "0.13.0", "size" to "1", "ts" to "2"),
        )!!
        assertEquals(16L, p.versionCode)
        assertEquals("0.13.0", p.versionName)
        assertNull(DroidUpdatePush.from(mapOf("type" to "other", "versionCode" to "16")))
        assertNull(DroidUpdatePush.from(mapOf("type" to "droid_update", "versionCode" to "x")))
        assertNull(DroidUpdatePush.from(mapOf("type" to "droid_update")))
        assertEquals("16", DroidUpdatePush.from(mapOf("type" to "droid_update", "versionCode" to "16"))!!.versionName)
    }

    @Test
    fun `check throttle is 12 hours`() {
        val h = 3_600_000L
        val t0 = 1_000_000_000_000L
        assertTrue(UpdateLogic.shouldCheck(0, t0))
        assertFalse(UpdateLogic.shouldCheck(t0, t0 + 11 * h))
        assertTrue(UpdateLogic.shouldCheck(t0, t0 + 12 * h))
        assertTrue(UpdateLogic.shouldCheck(t0, t0 - h)) // clock went backwards
    }

    @Test
    fun `install failure messages`() {
        val key = "This build is signed with a different key. Uninstall the app and install it once by hand; updates work after that."
        assertEquals(key, UpdateLogic.installFailureMessage(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, null))
        assertEquals(key, UpdateLogic.installFailureMessage(PackageInstaller.STATUS_FAILURE_CONFLICT, "x"))
        assertEquals("Install cancelled.", UpdateLogic.installFailureMessage(PackageInstaller.STATUS_FAILURE_ABORTED, null))
        assertEquals("Install failed: boom", UpdateLogic.installFailureMessage(PackageInstaller.STATUS_FAILURE, "boom"))
    }

    @Test
    fun `sha256 over a temp file and while copying`() {
        val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val f = File.createTempFile("upd", ".apk")
        try {
            f.writeText("abc")
            assertEquals(abc, Sha256.ofFile(f))
            assertTrue(Sha256.matches(f, abc.uppercase()))
            assertFalse(Sha256.matches(f, "00"))
            val out = ByteArrayOutputStream()
            var seen = 0L
            assertEquals(abc, Sha256.copy(f.inputStream(), out) { seen = it })
            assertEquals("abc", out.toString())
            assertEquals(3L, seen)
        } finally {
            f.delete()
        }
    }

    @Test
    fun `apk name`() {
        assertEquals("sys-mon-droid-16.apk", UpdateLogic.apkName(16))
    }
}
