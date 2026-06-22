package com.raavivi.sysmon.core.model

import kotlinx.serialization.Serializable

@Serializable
data class FsEntry(
    val name: String = "",
    val path: String = "",
    val isDir: Boolean = false,
    val size: Long? = null,
    val modified: Double? = null,
)

@Serializable
data class FsListResponse(
    val path: String = "",
    val parent: String? = null,
    val entries: List<FsEntry> = emptyList(),
)

@Serializable
data class FsReadResponse(val path: String = "", val content: String = "")

@Serializable
data class FsWriteBody(val path: String, val content: String)

@Serializable
data class FsCopyBody(val src: String, val dst: String)

@Serializable
data class FsMoveBody(val src: String, val dst: String)

@Serializable
data class FsRenameBody(val path: String, val newName: String)

@Serializable
data class FsMkdirBody(val parent: String, val name: String)

@Serializable
data class DriveInfo(
    val mountpoint: String = "",
    val fstype: String = "",
    val total: Long? = null,
    val free: Long? = null,
)

@Serializable
data class DrivesResponse(val drives: List<DriveInfo> = emptyList())

@Serializable
data class FileProperties(
    val name: String = "",
    val path: String = "",
    val isDir: Boolean = false,
    val size: Long? = null,
    val created: Double? = null,
    val modified: Double? = null,
    val accessed: Double? = null,
    val mode: String? = null,
    val readable: Boolean? = null,
    val writable: Boolean? = null,
    val executable: Boolean? = null,
    val attributes: String? = null,
    val extension: String? = null,
    val mime: String? = null,
)

@Serializable
data class FolderSize(
    val path: String = "",
    val size: Long = 0,
    val fileCount: Int = 0,
    val cached: Boolean = false,
    val partial: Boolean = false,
    val skipped: String? = null,
)

// ── Recycle bin ────────────────────────────────────────────────────────────────

@Serializable
data class RecycleItem(
    val iFile: String = "",
    val rFile: String? = null,
    val originalPath: String = "",
    val name: String = "",
    val size: Long? = null,
    val deletedAt: Double? = null,
    val isDir: Boolean = false,
)

@Serializable
data class RecycleListResponse(val items: List<RecycleItem> = emptyList())

@Serializable
data class RecycleRestoreBody(val iFile: String)

// ── Search ───────────────────────────────────────────────────────────────────

@Serializable
data class SearchResult(
    val name: String = "",
    val path: String = "",
    val isDir: Boolean = false,
    val size: Long? = null,
    val modified: Double? = null,
)

@Serializable
data class SearchBackend(val backend: String = "", val description: String = "")

// ── Favorites ──────────────────────────────────────────────────────────────────

@Serializable
data class Favorite(
    val path: String = "",
    val name: String = "",
    val addedAt: Double? = null,
)

@Serializable
data class FavoriteBody(val name: String, val path: String)
