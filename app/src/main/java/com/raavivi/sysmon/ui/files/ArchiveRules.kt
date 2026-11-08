package com.raavivi.sysmon.ui.files

import com.raavivi.sysmon.core.model.ArchiveEntry
import com.raavivi.sysmon.core.net.ApiResult

/** Pure archive logic; port of sys-mon `archive-state.js`. No Compose, no I/O. */

/** Path segments of an entry or folder path, either separator. */
fun splitPath(path: String): List<String> = path.split('\\', '/').filter { it.isNotEmpty() }

/** One node of the archive tree. [path] is `/`-joined; [entryPath] is what the server wants back. */
data class ArchiveNode(
    val name: String,
    val path: String,
    val entryPath: String,
    val dir: Boolean,
    val size: Long,
    val packed: Long,
    val modified: String?,
    val encrypted: Boolean,
)

/** Folder tree built from the flat listing, with implied parent folders. */
class ArchiveTree private constructor(private val dirs: Map<String, MutableDir>) {

    class MutableDir(
        val name: String,
        val path: String,
        var entryPath: String,
        var modified: String? = null,
    ) {
        val children = LinkedHashMap<String, Any>() // MutableDir or ArchiveNode
    }

    fun has(dirPath: String): Boolean = dirs.containsKey(splitPath(dirPath).joinToString("/"))

    /** Children of a folder: folders first, then files, each by name. */
    fun list(dirPath: String = ""): List<ArchiveNode> {
        val node = dirs[splitPath(dirPath).joinToString("/")] ?: return emptyList()
        return node.children.values
            .map { if (it is MutableDir) summarize(it) else it as ArchiveNode }
            .sortedWith(compareBy<ArchiveNode>({ !it.dir }).thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    /** A folder shows the totals of what is under it. */
    private fun summarize(d: MutableDir): ArchiveNode {
        var size = 0L
        var packed = 0L
        var enc = false
        fun walk(x: MutableDir) {
            for (c in x.children.values) {
                if (c is MutableDir) walk(c) else {
                    val f = c as ArchiveNode
                    size += f.size
                    packed += f.packed
                    if (f.encrypted) enc = true
                }
            }
        }
        walk(d)
        return ArchiveNode(d.name, d.path, d.entryPath, true, size, packed, d.modified, enc)
    }

    companion object {
        fun build(entries: List<ArchiveEntry>): ArchiveTree {
            val root = MutableDir("", "", "")
            val dirs = LinkedHashMap<String, MutableDir>()
            dirs[""] = root

            fun ensureDir(segments: List<String>): MutableDir {
                var node = root
                var path = ""
                for (seg in segments) {
                    path = if (path.isEmpty()) seg else "$path/$seg"
                    val existing = node.children[seg]
                    node = if (existing is MutableDir) existing else {
                        val d = MutableDir(seg, path, path)
                        node.children[seg] = d
                        dirs[path] = d
                        d
                    }
                }
                return node
            }

            for (e in entries) {
                val segs = splitPath(e.path)
                if (segs.isEmpty()) continue
                if (e.dir) {
                    val d = ensureDir(segs)
                    d.entryPath = e.path
                    d.modified = e.modified
                    continue
                }
                val parent = ensureDir(segs.dropLast(1))
                val name = segs.last()
                parent.children[name] = ArchiveNode(
                    name, segs.joinToString("/"), e.path, false, e.size, e.packed, e.modified, e.encrypted,
                )
            }
            return ArchiveTree(dirs)
        }
    }
}

/** `""` for the root, else the parent's path. */
fun parentPath(dirPath: String): String = splitPath(dirPath).dropLast(1).joinToString("/")

/** Breadcrumb segments (name, path) from the root down to [cwd]. */
fun crumbs(cwd: String): List<Pair<String, String>> {
    val out = mutableListOf("/" to "")
    var path = ""
    for (seg in splitPath(cwd)) {
        path = if (path.isEmpty()) seg else "$path/$seg"
        out += seg to path
    }
    return out
}

// ── Detection and naming ───────────────────────────────────────────────────────

private val COMPOUND = setOf("gz", "bz2", "xz", "zst", "lzma")

/** Whether [name] is something the host reads, by its last extension (`a.tar.gz` -> `gz`). */
fun isArchiveName(name: String, extensions: List<String>): Boolean {
    val ext = extOf(name)
    return ext.isNotEmpty() && extensions.any { it.equals(ext, ignoreCase = true) }
}

/** Name without its archive extension; `a.tar.gz` -> `a`. */
fun archiveStem(name: String): String {
    val ext = extOf(name)
    if (ext.isEmpty()) return name
    var stem = name.dropLast(ext.length + 1)
    if (ext in COMPOUND && stem.endsWith(".tar", ignoreCase = true)) stem = stem.dropLast(4)
    return stem.ifEmpty { name }
}

private val CREATE_EXTS = listOf(".zip", ".7z", ".tar", ".rar")

/** [name] with its extension swapped for the chosen format's. */
fun withArchiveExt(name: String, format: String): String {
    var base = name
    val lower = base.lowercase()
    CREATE_EXTS.firstOrNull { lower.endsWith(it) }?.let { base = base.dropLast(it.length) }
    return "$base.$format"
}

/** Default archive name for compressing [paths], living in [cwd]. */
fun defaultArchiveName(paths: List<String>, cwd: String, format: String): String {
    val base = if (paths.size == 1) {
        val n = splitPath(paths.first()).lastOrNull().orEmpty()
        val dot = n.lastIndexOf('.')
        if (dot > 0) n.substring(0, dot) else n
    } else {
        splitPath(cwd).lastOrNull().orEmpty().ifEmpty { "archive" }
    }
    return "${base.ifEmpty { "archive" }}.$format"
}

private val BAD_NAME_CHARS = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

/** Error text for the compress form, or null when it may be submitted. */
fun compressFormError(name: String, password: String, confirm: String): String? = when {
    name.isBlank() || name.indexOfAny(BAD_NAME_CHARS) >= 0 ->
        "Enter a file name without \\ / : * ? \" < > |"
    password != confirm -> "The passwords do not match"
    else -> null
}

/** [name] with the format's extension appended when it is missing. */
fun ensureArchiveExt(name: String, format: String): String {
    val n = name.trim()
    return if (n.lowercase().endsWith(".$format")) n else "$n.$format"
}

/** Joins with [sep], not doubling a separator the parent already ends with. */
fun joinPath(parent: String, name: String, sep: String): String = when {
    parent.isEmpty() -> name
    parent.endsWith('/') || parent.endsWith('\\') -> parent + name
    else -> parent + sep + name
}

// ── Passwords and errors ───────────────────────────────────────────────────────

const val CODE_PASSWORD_REQUIRED = "password_required"
const val CODE_WRONG_PASSWORD = "wrong_password"

sealed interface PasswordOutcome<out T> {
    /** [password] is the one that worked, null if none was needed. */
    data class Done<T>(val value: T, val password: String?) : PasswordOutcome<T>
    data class Failed(val error: ApiResult.Err) : PasswordOutcome<Nothing>
    /** The user dismissed the prompt; end quietly. */
    data object Cancelled : PasswordOutcome<Nothing>
}

/**
 * Runs [run] with [initial], asking [ask] for a password whenever the host answers
 * password_required or wrong_password, until it works, fails otherwise, or [ask]
 * returns null (cancel).
 */
suspend fun <T> withPassword(
    name: String,
    initial: String?,
    ask: suspend (message: String) -> String?,
    run: suspend (password: String?) -> ApiResult<T>,
): PasswordOutcome<T> {
    var password = initial
    while (true) {
        when (val r = run(password)) {
            is ApiResult.Ok -> return PasswordOutcome.Done(r.value, password)
            is ApiResult.Err -> {
                if (r.apiCode != CODE_PASSWORD_REQUIRED && r.apiCode != CODE_WRONG_PASSWORD) {
                    return PasswordOutcome.Failed(r)
                }
                val message = if (r.apiCode == CODE_WRONG_PASSWORD) "Wrong password, try again" else "Password for $name"
                password = ask(message) ?: return PasswordOutcome.Cancelled
            }
        }
    }
}

/** User-facing text for a failed archive call. */
fun archiveErrorMessage(e: ApiResult.Err): String = when {
    e.apiCode == "engine_missing" || e.code == 503 ->
        "7-Zip was not found on the server, so archives cannot be read or created."
    e.code == 409 -> "An archive with that name already exists."
    else -> e.message
}

/** Text-like entries the browser can show read-only. */
fun isTextLike(name: String): Boolean =
    fileKind(name, false) == FileKind.Code || extOf(name) in setOf("txt", "md", "log", "csv", "tsv", "nfo", "diz")
