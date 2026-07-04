package com.raavivi.sysmon.ui.files

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raavivi.sysmon.AppContainer
import com.raavivi.sysmon.core.model.DriveInfo
import com.raavivi.sysmon.core.model.Favorite
import com.raavivi.sysmon.core.model.FavoriteBody
import com.raavivi.sysmon.core.model.FileProperties
import com.raavivi.sysmon.core.model.FsCopyBody
import com.raavivi.sysmon.core.model.FsEntry
import com.raavivi.sysmon.core.model.FsMkdirBody
import com.raavivi.sysmon.core.model.FsMoveBody
import com.raavivi.sysmon.core.model.FsRenameBody
import com.raavivi.sysmon.core.model.RecycleItem
import com.raavivi.sysmon.core.model.RecycleRestoreBody
import com.raavivi.sysmon.core.model.SearchResult
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.safeCall
import coil.ImageLoader
import kotlinx.coroutines.launch

enum class FilesMode { Browse, Recycle, Search }

class FilesViewModel(private val container: AppContainer) : ViewModel() {

    /** Viewers get a read-only explorer; the server 403s writes anyway. */
    val isAdmin: Boolean get() = container.session.isAdmin

    var mode by mutableStateOf(FilesMode.Browse)
        private set
    var path by mutableStateOf("")
        private set
    var parent by mutableStateOf<String?>(null)
        private set
    var entries by mutableStateOf<List<FsEntry>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)

    var drives by mutableStateOf<List<DriveInfo>>(emptyList())
        private set
    var favorites by mutableStateOf<List<Favorite>>(emptyList())
        private set
    var recycleItems by mutableStateOf<List<RecycleItem>>(emptyList())
        private set
    var searchResults by mutableStateOf<List<SearchResult>>(emptyList())
        private set
    var searchBackend by mutableStateOf<String?>(null)
        private set

    var properties by mutableStateOf<FileProperties?>(null)
    var imagePreviewPath by mutableStateOf<String?>(null)

    val imageLoader: ImageLoader = container.imageLoader

    init {
        loadDrives()
        loadFavorites()
        open("")
    }

    fun fileUrl(p: String): String = container.api.fileUrl(p)

    fun open(target: String) {
        mode = FilesMode.Browse
        loading = true
        error = null
        viewModelScope.launch {
            when (val r = safeCall { container.api.api.fsList(target) }) {
                is ApiResult.Ok -> {
                    path = r.value.path
                    parent = r.value.parent
                    entries = r.value.entries
                }
                is ApiResult.Err -> error = r.message
            }
            loading = false
        }
    }

    fun refresh() {
        when (mode) {
            FilesMode.Browse -> open(path)
            FilesMode.Recycle -> openRecycle()
            FilesMode.Search -> {}
        }
        loadFavorites()
    }

    fun navigateUp() {
        parent?.let { open(it) }
    }

    fun loadDrives() {
        viewModelScope.launch {
            (safeCall { container.api.api.fsDrives() } as? ApiResult.Ok)?.let { drives = it.value.drives }
        }
    }

    fun loadFavorites() {
        viewModelScope.launch {
            (safeCall { container.api.api.favorites() } as? ApiResult.Ok)?.let { favorites = it.value }
        }
    }

    fun openRecycle() {
        mode = FilesMode.Recycle
        loading = true
        error = null
        viewModelScope.launch {
            when (val r = safeCall { container.api.api.recycleList() }) {
                is ApiResult.Ok -> recycleItems = r.value.items
                is ApiResult.Err -> error = r.message
            }
            loading = false
        }
    }

    fun search(query: String, root: String?) {
        if (query.isBlank()) return
        mode = FilesMode.Search
        loading = true
        error = null
        viewModelScope.launch {
            when (val r = safeCall { container.api.api.fsSearch(q = query, root = root) }) {
                is ApiResult.Ok -> {
                    searchResults = r.value.items
                    searchBackend = r.value.backend
                    r.value.warning?.let { message = it }
                }
                is ApiResult.Err -> error = r.message
            }
            loading = false
        }
    }

    // ── Mutations ──────────────────────────────────────────────────────────────

    fun mkdir(name: String) = mutate("Folder created") {
        container.api.api.fsMkdir(FsMkdirBody(parent = path, name = name))
    }

    fun rename(target: String, newName: String) = mutate("Renamed") {
        container.api.api.fsRename(FsRenameBody(path = target, newName = newName))
    }

    fun delete(target: String) = mutate("Deleted") {
        container.api.api.fsDelete(target)
    }

    fun copyTo(src: String, dst: String) = mutate("Copied") {
        container.api.api.fsCopy(FsCopyBody(src = src, dst = dst))
    }

    fun moveTo(src: String, dst: String) = mutate("Moved") {
        container.api.api.fsMove(FsMoveBody(src = src, dst = dst))
    }

    fun addFavorite(entry: FsEntry) {
        viewModelScope.launch {
            safeCall { container.api.api.addFavorite(FavoriteBody(name = entry.name, path = entry.path)) }
            loadFavorites()
            message = "Added to favorites"
        }
    }

    fun removeFavorite(p: String) {
        viewModelScope.launch {
            safeCall { container.api.api.removeFavorite(p) }
            loadFavorites()
        }
    }

    fun loadProperties(target: String) {
        viewModelScope.launch {
            when (val r = safeCall { container.api.api.fsProperties(target) }) {
                is ApiResult.Ok -> properties = r.value
                is ApiResult.Err -> message = r.message
            }
        }
    }

    fun recycleRestore(item: RecycleItem) = recycleOp("Restored") {
        container.api.api.recycleRestore(RecycleRestoreBody(item.iFile))
    }

    fun recycleDelete(item: RecycleItem) = recycleOp("Permanently deleted") {
        container.api.api.recycleDelete(item.iFile)
    }

    fun recycleClear() = recycleOp("Recycle bin emptied") {
        container.api.api.recycleClear()
    }

    private fun mutate(okMsg: String, block: suspend () -> Any) {
        viewModelScope.launch {
            message = when (val r = safeCall { block() }) {
                is ApiResult.Ok -> okMsg
                is ApiResult.Err -> "Failed: ${r.message}"
            }
            if (mode == FilesMode.Browse) open(path)
        }
    }

    private fun recycleOp(okMsg: String, block: suspend () -> Any) {
        viewModelScope.launch {
            message = when (val r = safeCall { block() }) {
                is ApiResult.Ok -> okMsg
                is ApiResult.Err -> "Failed: ${r.message}"
            }
            openRecycle()
        }
    }
}
