package com.raavivi.sysmon.ui.files

import com.raavivi.sysmon.core.model.DriveInfo

/** Pure drive helpers; port of sys-mon `drives.js`. */

/** Hues cycle through five slots by list position, not drive letter. */
const val DRIVE_HUES = 5

/** `C:\` -> `C:`; the POSIX root keeps its `/`. */
fun driveLetter(mountpoint: String): String =
    mountpoint.trimEnd('\\', '/').ifEmpty { mountpoint }

/** `C: Windows`, or just `C:` when the volume has no label. */
fun driveName(d: DriveInfo): String = "${driveLetter(d.mountpoint)} ${d.label}".trim()

/** Used percent, or null when the drive reports no capacity (unreadable, not full). */
fun driveUsedPct(d: DriveInfo): Double? {
    val total = d.total ?: return null
    if (total <= 0) return null
    val used = (total - (d.free ?: 0)).coerceAtLeast(0)
    return used * 100.0 / total
}

/**
 * Index of the drive [path] lives on, or null. The longest mountpoint wins so a
 * volume mounted into a folder beats its root. Windows compares case-insensitively
 * with either slash; POSIX is case-sensitive and `/` only.
 */
fun driveIndexForPath(drives: List<DriveInfo>, path: String, posix: Boolean): Int? {
    val sep = if (posix) '/' else '\\'
    fun fold(s: String) = if (posix) s else s.replace('/', '\\').lowercase()
    val p = fold(path)
    var best: Int? = null
    var bestLen = -1
    drives.forEachIndexed { i, d ->
        val m = fold(d.mountpoint).trimEnd(sep)
        if (d.mountpoint.isBlank()) return@forEachIndexed
        if ((p == m || p.startsWith(m + sep)) && m.length > bestLen) {
            best = i
            bestLen = m.length
        }
    }
    return best
}
