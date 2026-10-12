package com.raavivi.sysmon.ui.files

/** Pure path rules for the copy/move destination picker (Windows `\` and POSIX `/`). */

private fun norm(p: String): String = p.trimEnd('\\', '/').replace('/', '\\').lowercase()

/** True when [dst] is [src] itself or lives somewhere inside it. */
fun isSelfOrInside(src: String, dst: String): Boolean {
    val s = norm(src)
    val d = norm(dst)
    if (s.isEmpty()) return false
    return d == s || d.startsWith("$s\\")
}

/** True when [dst] is the folder that already directly contains [src]. */
fun isCurrentParent(src: String, dst: String): Boolean {
    val s = src.trimEnd('\\', '/')
    val cut = maxOf(s.lastIndexOf('\\'), s.lastIndexOf('/'))
    if (cut < 0) return false
    // Keep the separator for a drive root such as `C:\`.
    val parent = if (cut == 2 && s[1] == ':') s.substring(0, 3) else s.substring(0, cut).ifEmpty { s.substring(0, 1) }
    return norm(parent) == norm(dst)
}

/** Whether "Copy here" / "Move here" may be pressed for the browsed [dst]. */
fun canTransferTo(src: String, dst: String, isMove: Boolean): Boolean = when {
    dst.isBlank() -> false
    isSelfOrInside(src, dst) -> false
    isMove && isCurrentParent(src, dst) -> false
    else -> true
}
