package com.raavivi.sysmon.ui.files

/** Coarse file class that picks a row icon and its colour; port of sys-mon `icon.js`. */
enum class FileKind { Dir, Media, Archive, Exec, Code, Doc }

/** Sub-type of [FileKind.Media], for the glyph only. */
enum class MediaType { Image, Video, Audio }

private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "ico")
private val VIDEO_EXTS = setOf("mp4", "mkv", "avi", "mov", "webm")
private val AUDIO_EXTS = setOf("mp3", "wav", "flac", "ogg", "m4a")

/** Every extension the server reads (READ_EXTENSIONS in sysmon-core archive.rs). */
private val ARCHIVE_EXTS = (
    "zip zipx 7z rar tar gz tgz bz2 tbz tbz2 xz txz zst tzst lzma cab iso " +
        "wim swm lzh lha arj z cpio rpm deb jar war apk"
    ).split(' ').toSet()

private val EXEC_EXTS = setOf("exe", "msi", "dll", "bat", "cmd")

private val CODE_EXTS = setOf(
    "js", "mjs", "cjs", "ts", "tsx", "jsx", "py", "rs", "go", "java", "c", "h",
    "cpp", "hpp", "cs", "rb", "php", "lua", "gd", "sh", "sql", "vue", "svelte",
    "css", "scss", "html", "json", "xml", "yml", "yaml", "toml", "ini", "cfg", "conf",
)

/** Compressors that wrap a tar: `a.tar.gz` badges as TAR.GZ. */
private val COMPOUND_EXTS = setOf("gz", "bz2", "xz", "zst", "lzma")

/** What Windows ships thumbnail providers for (thumbnails.js THUMBABLE). */
private val THUMB_EXTS = setOf(
    "png", "jpg", "jpeg", "jfif", "gif", "bmp", "webp", "tif", "tiff", "ico",
    "heic", "heif", "avif", "dds", "tga", "svg", "psd", "ai", "cr2", "nef",
    "arw", "dng", "raf", "orf",
    "mp4", "mkv", "avi", "mov", "webm", "wmv", "flv", "m4v", "mpg", "mpeg",
    "3gp", "ts", "m2ts",
    "mp3", "flac", "m4a", "wma", "ogg", "opus", "wav",
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp",
    "rtf", "epub", "xps",
    "exe", "msi", "lnk", "url", "cpl", "scr",
)

/** Lower-case text after the last dot; empty when there is none. */
internal fun extOf(name: String): String {
    val i = name.lastIndexOf('.')
    return if (i < 0 || name.indexOfAny(charArrayOf('\\', '/'), i) >= 0) "" else name.substring(i + 1).lowercase()
}

fun fileKind(name: String, isDir: Boolean): FileKind {
    if (isDir) return FileKind.Dir
    val ext = extOf(name)
    return when {
        ext in IMAGE_EXTS || ext in VIDEO_EXTS || ext in AUDIO_EXTS -> FileKind.Media
        ext in ARCHIVE_EXTS -> FileKind.Archive
        ext in EXEC_EXTS -> FileKind.Exec
        ext in CODE_EXTS -> FileKind.Code
        else -> FileKind.Doc
    }
}

/** Glyph sub-type for a [FileKind.Media] file; null for anything else. */
fun mediaType(name: String): MediaType? {
    val ext = extOf(name)
    return when (ext) {
        in IMAGE_EXTS -> MediaType.Image
        in VIDEO_EXTS -> MediaType.Video
        in AUDIO_EXTS -> MediaType.Audio
        else -> null
    }
}

/** Corner label for an archive row: `ZIP`, `7Z`, `TAR.GZ`; null for a non-archive. */
fun archiveBadge(name: String): String? {
    val ext = extOf(name)
    if (ext !in ARCHIVE_EXTS) return null
    val stem = name.substring(0, name.length - ext.length - 1)
    val compound = ext in COMPOUND_EXTS && stem.endsWith(".tar", ignoreCase = true)
    return (if (compound) "TAR.$ext" else ext).uppercase()
}

/** Whether a row should ask the server for a thumbnail. Never true for folders. */
fun mayHaveThumbnail(name: String, isDir: Boolean): Boolean = !isDir && extOf(name) in THUMB_EXTS
