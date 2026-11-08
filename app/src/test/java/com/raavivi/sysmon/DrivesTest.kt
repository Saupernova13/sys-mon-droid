package com.raavivi.sysmon

import com.raavivi.sysmon.core.model.DriveInfo
import com.raavivi.sysmon.ui.files.driveIndexForPath
import com.raavivi.sysmon.ui.files.driveLetter
import com.raavivi.sysmon.ui.files.driveName
import com.raavivi.sysmon.ui.files.driveUsedPct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DrivesTest {
    @Test fun names() {
        assertEquals("C:", driveLetter("C:\\"))
        assertEquals("/", driveLetter("/"))
        assertEquals("C: Windows", driveName(DriveInfo(mountpoint = "C:\\", label = "Windows")))
        assertEquals("D:", driveName(DriveInfo(mountpoint = "D:\\")))
        assertEquals("/", driveName(DriveInfo(mountpoint = "/")))
    }

    @Test fun usedPct() {
        assertEquals(75.0, driveUsedPct(DriveInfo(total = 100, free = 25))!!, 0.001)
        assertNull(driveUsedPct(DriveInfo(total = null)))
        assertNull(driveUsedPct(DriveInfo(total = 0, free = 0)))
    }

    @Test fun windowsPathMatchesCaseInsensitively() {
        val drives = listOf(DriveInfo(mountpoint = "C:\\"), DriveInfo(mountpoint = "D:\\"))
        assertEquals(1, driveIndexForPath(drives, "d:/Games/x", posix = false))
        assertEquals(0, driveIndexForPath(drives, "C:\\", posix = false))
        assertNull(driveIndexForPath(drives, "E:\\a", posix = false))
    }

    @Test fun posixLongestMountpointWins() {
        val drives = listOf(DriveInfo(mountpoint = "/"), DriveInfo(mountpoint = "/mnt/data"))
        assertEquals(1, driveIndexForPath(drives, "/mnt/data/x", posix = true))
        assertEquals(0, driveIndexForPath(drives, "/mnt/database", posix = true))
        assertEquals(0, driveIndexForPath(drives, "/home", posix = true))
    }
}
