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
    val label: String = "",
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

/** /api/fs/search -> `{items, backend, warning?}` (warning omitted when clean). */
@Serializable
data class SearchResponse(
    val items: List<SearchResult> = emptyList(),
    val backend: String = "",
    val warning: String? = null,
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

/** `DELETE /api/fs/recycle/clear`: [ok] is false when nothing could be removed. */
@Serializable
data class RecycleClearResponse(
    val ok: Boolean = true,
    val partial: Boolean = false,
    val deleted: Int = 0,
    val errors: List<String> = emptyList(),
)

// ── Transfer jobs ──────────────────────────────────────────────────────────────

/** `POST /api/fs/jobs`; null fields are omitted (explicitNulls=false). */
@Serializable
data class FsJobStartBody(
    val op: String,
    val sources: List<String>,
    val dest: String? = null,
    val password: String? = null,
    val format: String? = null,
    val entries: List<String>? = null,
)

@Serializable
data class FsJobStarted(val id: String = "")

@Serializable
data class FsJobError(val path: String = "", val message: String = "")

/**
 * One job snapshot (`transfer_jobs.rs snapshot()`). [state] is scanning, running,
 * done, failed or cancelled; the client adds `lost` when contact is gone.
 */
@Serializable
data class FsJob(
    val id: String = "",
    val op: String = "",
    val state: String = "",
    val sources: List<String> = emptyList(),
    val dest: String? = null,
    val format: String? = null,
    val filesTotal: Long? = null,
    val filesDone: Long? = null,
    val bytesTotal: Long? = null,
    val bytesDone: Long? = null,
    val current: String? = null,
    val startedAt: Double? = null,
    val finishedAt: Double? = null,
    val elapsed: Double? = null,
    val speed: Double? = null,
    val errors: List<FsJobError> = emptyList(),
    val partial: List<String> = emptyList(),
)

/** `GET /api/fs/jobs` -> `{jobs: [...]}`. */
@Serializable
data class FsJobList(val jobs: List<FsJob> = emptyList())
