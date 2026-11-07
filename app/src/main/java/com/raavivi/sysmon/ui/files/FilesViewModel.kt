package com.raavivi.sysmon.ui.files

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raavivi.sysmon.AppContainer
import com.raavivi.sysmon.core.model.ArchiveCaps
import com.raavivi.sysmon.core.model.DriveInfo
import com.raavivi.sysmon.core.model.Favorite
import com.raavivi.sysmon.core.model.FavoriteBody
import com.raavivi.sysmon.core.model.FileProperties
import com.raavivi.sysmon.core.model.FsCopyBody
import com.raavivi.sysmon.core.model.FsJob
import com.raavivi.sysmon.core.model.FsJobError
import com.raavivi.sysmon.core.model.FsJobStartBody
import com.raavivi.sysmon.core.model.FsEntry
import com.raavivi.sysmon.core.model.FsMkdirBody
import com.raavivi.sysmon.core.model.FsMoveBody
import com.raavivi.sysmon.core.model.FsRenameBody
import com.raavivi.sysmon.core.model.RecycleClearResponse
import com.raavivi.sysmon.core.model.RecycleItem
import com.raavivi.sysmon.core.model.RecycleRestoreBody
import com.raavivi.sysmon.core.model.SearchResult
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.safeCall
import coil.ImageLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
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

    // Destination picker state, independent of the explorer list above.
    var pickerPath by mutableStateOf("")
        private set
    var pickerParent by mutableStateOf<String?>(null)
        private set
    var pickerEntries by mutableStateOf<List<FsEntry>>(emptyList())
        private set
    var pickerLoading by mutableStateOf(false)
        private set
    var pickerError by mutableStateOf<String?>(null)
        private set

    var properties by mutableStateOf<FileProperties?>(null)
    var imagePreviewPath by mutableStateOf<String?>(null)

    val imageLoader: ImageLoader = container.imageLoader

    /** Selected rows: entry paths when browsing, recycle `iFile` ids in the bin. */
    var selected by mutableStateOf<Set<String>>(emptySet())
        private set
    val selecting: Boolean get() = selected.isNotEmpty()

    /** Every transfer started this session, oldest first. */
    var jobs by mutableStateOf<List<FsJob>>(emptyList())
        private set

    /** The job whose progress card is open; null when closed (jobs keep running). */
    var shownJobId by mutableStateOf<String?>(null)
        private set
    var cancelStates by mutableStateOf<Map<String, CancelState>>(emptyMap())
        private set

    /** Progress card view: compact (false) or details (true); persisted. */
    var transferDetails by mutableStateOf(false)
        private set

    val runningJobs: List<FsJob> get() = jobs.filter { !isTerminal(it.state) }
    val shownJob: FsJob? get() = shownJobId?.let { id -> jobs.firstOrNull { it.id == id } }

    private val isPosix: Boolean get() = container.session.host.value?.isWindows == false

    /** Index into [drives] of the drive the browsed path lives on. */
    val currentDriveIndex: Int? get() = driveIndexForPath(drives, path, isPosix)

    init {
        loadDrives()
        loadFavorites()
        open("")
        loadCaps()
        viewModelScope.launch { transferDetails = container.settings.transferDetailsNow() }
    }

    fun fileUrl(p: String): String = container.api.fileUrl(p)

    fun thumbnailUrl(p: String): String = container.api.thumbnailUrl(p)

    // ── Selection ──────────────────────────────────────────────────────────────

    fun toggleSelected(key: String) {
        selected = if (key in selected) selected - key else selected + key
    }

    fun selectAll(keys: Collection<String>) {
        selected = keys.toSet()
    }

    fun clearSelection() {
        selected = emptySet()
    }

    fun open(target: String) {
        mode = FilesMode.Browse
        selected = emptySet()
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

    private var pickerSeq = 0

    fun pickerOpen(target: String) {
        val seq = ++pickerSeq
        pickerLoading = true
        pickerError = null
        viewModelScope.launch {
            val r = safeCall { container.api.api.fsList(target) }
            // A newer tap superseded this listing; drop it so folders can't land out of order.
            if (seq != pickerSeq) return@launch
            when (r) {
                is ApiResult.Ok -> {
                    pickerPath = r.value.path
                    pickerParent = r.value.parent
                    pickerEntries = r.value.entries
                }
                is ApiResult.Err -> pickerError = r.message
            }
            pickerLoading = false
        }
    }

    fun refresh() {
        when (mode) {
            FilesMode.Browse -> open(path)
            FilesMode.Recycle -> openRecycle()
            FilesMode.Search -> {}
        }
        loadFavorites()
        loadDrives()
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
        selected = emptySet()
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
        selected = emptySet()
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

    // ── Transfer jobs ──────────────────────────────────────────────────────────

    fun deleteMany(sources: List<String>) = startJob("delete", sources, null)

    fun transfer(sources: List<String>, dest: String, move: Boolean) =
        startJob(if (move) "move" else "copy", sources, dest)

    /**
     * Start copy / move / delete as a server-side job and track it. A host that
     * predates `/api/fs/jobs` answers 404; then the per-item routes are used.
     */
    private fun startJob(op: String, sources: List<String>, dest: String?) {
        if (sources.isEmpty()) return
        viewModelScope.launch {
            when (val r = safeCall { container.api.api.fsJobStart(FsJobStartBody(op, sources, dest)) }) {
                is ApiResult.Ok -> track(FsJob(id = r.value.id, op = op, state = "scanning", sources = sources, dest = dest))
                is ApiResult.Err ->
                    if (r.code == 404) legacyTransfer(op, sources, dest) else message = "Failed: ${r.message}"
            }
        }
    }

    private suspend fun legacyTransfer(op: String, sources: List<String>, dest: String?) {
        val errors = mutableListOf<FsJobError>()
        for (src in sources) {
            val r = safeCall {
                when (op) {
                    "copy" -> container.api.api.fsCopy(FsCopyBody(src = src, dst = dest.orEmpty()))
                    "move" -> container.api.api.fsMove(FsMoveBody(src = src, dst = dest.orEmpty()))
                    else -> container.api.api.fsDelete(src)
                }
            }
            if (r is ApiResult.Err) errors += FsJobError(src, r.message)
        }
        val summary = FsJob(op = op, state = "done", sources = sources, errors = errors)
        message = if (errors.size == sources.size && errors.isNotEmpty()) {
            "Failed: ${errors.first().message}"
        } else {
            finishMessage(summary)
        }
        afterTransfer()
    }

    private fun track(job: FsJob) {
        jobs = jobs + job
        shownJobId = job.id
        selected = emptySet()
        viewModelScope.launch { pollJob(job.id) }
    }

    private fun updateJob(job: FsJob) {
        jobs = jobs.map { if (it.id == job.id) job else it }
    }

    private fun jobById(id: String): FsJob? = jobs.firstOrNull { it.id == id }

    private fun setCancel(id: String, state: CancelState) {
        cancelStates = cancelStates + (id to state)
    }

    /** Polls fast while the card is open, slowly otherwise so the header chip stays honest. */
    private suspend fun pollJob(id: String) {
        var failures = 0
        while (true) {
            val r = safeCall { container.api.api.fsJob(id) }
            val snap = (r as? ApiResult.Ok)?.value
            val outcome = classifyPoll(snap, (r as? ApiResult.Err)?.message, failures)
            failures = outcome.failures
            when (outcome.kind) {
                PollKind.Snapshot -> updateJob(snap!!)
                PollKind.Retry -> {}
                PollKind.Lost -> jobById(id)?.let { updateJob(lostJob(it)) }
            }
            val current = jobById(id) ?: return
            if (isTerminal(current.state)) {
                settle(current)
                return
            }
            delay(if (shownJobId == id) POLL_ATTACHED_MS else POLL_DETACHED_MS)
        }
    }

    private suspend fun settle(job: FsJob) {
        setCancel(job.id, nextCancel(cancelStates[job.id] ?: CancelState.Idle, CancelEvent.Settled))
        message = finishMessage(job)
        afterTransfer()
        if (shouldAutoClose(job, transferDetails)) {
            delay(AUTO_CLOSE_MS)
            val now = jobById(job.id)
            if (shownJobId == job.id && now != null && shouldAutoClose(now, transferDetails)) shownJobId = null
        }
    }

    /** Re-list whatever the user is looking at once a transfer finishes. */
    private fun afterTransfer() {
        when (mode) {
            FilesMode.Browse -> open(path)
            FilesMode.Recycle -> openRecycle()
            FilesMode.Search -> {}
        }
    }

    fun cancelJob(id: String) {
        val before = cancelStates[id] ?: CancelState.Idle
        val next = nextCancel(before, CancelEvent.Click)
        if (next == before) return
        setCancel(id, next)
        viewModelScope.launch {
            if (safeCall { container.api.api.fsJobCancel(id) } is ApiResult.Err) {
                setCancel(id, nextCancel(cancelStates[id] ?: next, CancelEvent.RequestFailed))
                return@launch
            }
            delay(CANCEL_RETRY_MS)
            val job = jobById(id)
            if (job != null && !isTerminal(job.state)) {
                setCancel(id, nextCancel(cancelStates[id] ?: next, CancelEvent.GraceElapsed))
            }
        }
    }

    /** Close the card; the job keeps running. */
    fun detachJob() {
        shownJobId = null
    }

    /** Reopen the card of the most recent running job (the header chip). */
    fun showLatestRunning() {
        runningJobs.lastOrNull()?.let { shownJobId = it.id }
    }

    fun toggleTransferDetails(on: Boolean) {
        transferDetails = on
        viewModelScope.launch { container.settings.setTransferDetails(on) }
    }

    // ── Archives ───────────────────────────────────────────────────────────────

    /** Host archive support; null until loaded (a failed load is retried on the next need). */
    var caps by mutableStateOf<ArchiveCaps?>(null)
        private set

    /** A pending password prompt; the dialog completes [PasswordRequest.answer]. */
    var passwordRequest by mutableStateOf<PasswordRequest?>(null)
        private set

    /** The archive open in the browser, if any. */
    var archive by mutableStateOf<ArchiveBrowser?>(null)
        private set

    /** Host path separator; backslash when the host is unknown. */
    val pathSep: String get() = container.session.host.value?.pathSep?.ifBlank { null } ?: "\\"

    fun loadCaps() {
        if (caps != null) return
        viewModelScope.launch { ensureCaps() }
    }

    suspend fun ensureCaps(): ArchiveCaps {
        caps?.let { return it }
        val r = safeCall { container.api.api.archiveCaps() }
        if (r is ApiResult.Ok) {
            caps = r.value
            return r.value
        }
        return ArchiveCaps()
    }

    /** Only true when the host can read archives and knows this extension. */
    fun isArchive(name: String): Boolean =
        caps?.let { it.available && isArchiveName(name, it.extensions) } == true

    suspend fun askPassword(message: String): String? {
        val req = PasswordRequest(message)
        passwordRequest = req
        try {
            return req.answer.await()
        } finally {
            if (passwordRequest === req) passwordRequest = null
        }
    }

    fun answerPassword(password: String?) {
        passwordRequest?.answer?.complete(password)
    }

    fun openArchive(path: String) {
        archive = ArchiveBrowser(container, viewModelScope, path, ::askPassword).also { it.load() }
    }

    fun closeArchive() {
        archive?.cleanup()
        archive = null
    }

    /** Extract [archivePath] into [dest]; [entries] limits it to those archive paths. */
    fun extract(archivePath: String, dest: String, entries: List<String>? = null) =
        startArchiveJob(
            FsJobStartBody(op = "extract", sources = listOf(archivePath), dest = dest, entries = entries),
            baseName(archivePath),
        )

    fun compress(sources: List<String>, dest: String, format: String, password: String?) =
        startArchiveJob(
            FsJobStartBody(op = "compress", sources = sources, dest = dest, format = format, password = password),
            baseName(dest),
        )

    /**
     * Start an extract / compress job. The host checks the password before the job
     * exists, so a 422 loops through the prompt and no progress card opens for a
     * job that cannot start.
     */
    private fun startArchiveJob(body: FsJobStartBody, name: String) {
        viewModelScope.launch {
            val out = withPassword(name, body.password, ::askPassword) { pw ->
                safeCall { container.api.api.fsJobStart(body.copy(password = pw)) }
            }
            when (out) {
                is PasswordOutcome.Done -> track(
                    FsJob(
                        id = out.value.id, op = body.op, state = "scanning", sources = body.sources,
                        dest = body.dest, format = body.format,
                    ),
                )
                is PasswordOutcome.Failed -> message = "Failed: ${archiveErrorMessage(out.error)}"
                PasswordOutcome.Cancelled -> {}
            }
        }
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

    fun recycleRestore(items: List<RecycleItem>) = recycleMany(items, "Restored") {
        container.api.api.recycleRestore(RecycleRestoreBody(it.iFile))
    }

    fun recycleDelete(items: List<RecycleItem>) = recycleMany(items, "Permanently deleted") {
        container.api.api.recycleDelete(it.iFile)
    }

    fun recycleClear() {
        viewModelScope.launch {
            message = when (val r = safeCall { container.api.api.recycleClear() }) {
                is ApiResult.Ok -> recycleClearMessage(r.value)
                is ApiResult.Err -> "Failed: ${r.message}"
            }
            openRecycle()
        }
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

    /** Runs [block] per item (the bin has no batch route) and reports one summary. */
    private fun recycleMany(items: List<RecycleItem>, verb: String, block: suspend (RecycleItem) -> Any) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            var failed = 0
            var firstError: String? = null
            for (item in items) {
                val r = safeCall { block(item) }
                if (r is ApiResult.Err) {
                    failed++
                    firstError = firstError ?: r.message
                }
            }
            message = when {
                failed == 0 -> "$verb ${itemsLabel(items.size)}"
                failed == items.size -> "Failed: $firstError"
                else -> "$verb ${items.size - failed} of ${items.size}; $failed failed"
            }
            openRecycle()
        }
    }
}

/** Status line for `DELETE /api/fs/recycle/clear`, which can partly succeed. */
internal fun recycleClearMessage(r: RecycleClearResponse): String = when {
    !r.ok -> "Could not empty the recycle bin: ${r.errors.firstOrNull() ?: "nothing could be removed"}"
    r.partial -> "Emptied ${r.deleted} items; ${r.errors.size} could not be removed"
    else -> "Recycle bin emptied (${r.deleted} items)"
}

/** A password prompt waiting on the user; [answer] yields the text, or null for Cancel. */
class PasswordRequest(val message: String) {
    val answer = CompletableDeferred<String?>()
}
