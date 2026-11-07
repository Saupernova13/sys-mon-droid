package com.raavivi.sysmon.ui.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.raavivi.sysmon.core.model.FsEntry
import com.raavivi.sysmon.core.model.RecycleItem
import com.raavivi.sysmon.ui.common.ErrorBox
import com.raavivi.sysmon.ui.common.LoadingBox
import com.raavivi.sysmon.ui.common.ScreenHeader
import com.raavivi.sysmon.ui.common.StatRow
import com.raavivi.sysmon.ui.common.formatBytes
import com.raavivi.sysmon.ui.common.formatDateTime
import com.raavivi.sysmon.ui.common.rememberContainerViewModel
import com.raavivi.sysmon.ui.theme.SysMon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "ico")

private fun isImage(name: String) = name.substringAfterLast('.', "").lowercase() in IMAGE_EXT

private fun isPdf(name: String) = name.substringAfterLast('.', "").equals("pdf", ignoreCase = true)

/** How an archive row's "Extract" menu items pick their destination. */
enum class ExtractMode { Here, ToStem, Pick }

/** An extract waiting on the folder picker; [entries] null means the whole archive. */
data class ExtractRequest(val archivePath: String, val name: String, val entries: List<String>?)

/** The compress form asking for a destination folder. */
class FolderPick(val current: String, val onPicked: (String) -> Unit)

/** Tapping opens the archive browser only when the host can read this file; else retry caps. */
private fun archiveTap(vm: FilesViewModel, name: String): Boolean {
    if (vm.isArchive(name)) return true
    vm.loadCaps()
    return false
}

@Composable
fun FilesScreen(onEditFile: (String) -> Unit, onOpenPdf: (String) -> Unit = {}) {
    val vm = rememberContainerViewModel { FilesViewModel(it) }

    var showSearch by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var showNewFolder by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FsEntry?>(null) }
    var transfer by remember { mutableStateOf<Pair<List<FsEntry>, Boolean>?>(null) }
    var deleteTargets by remember { mutableStateOf<List<FsEntry>?>(null) }
    var recycleDeleteTargets by remember { mutableStateOf<List<RecycleItem>?>(null) }
    var placesExpanded by rememberSaveable { mutableStateOf(true) }

    vm.message?.let { msg -> LaunchedEffect(msg) { delay(2500); vm.message = null } }
    BackHandler(enabled = vm.selecting) { vm.clearSelection() }
    var extractPick by remember { mutableStateOf<ExtractRequest?>(null) }
    var compressPaths by remember { mutableStateOf<List<String>?>(null) }
    var folderPick by remember { mutableStateOf<FolderPick?>(null) }
    val scope = rememberCoroutineScope()

    fun runExtract(entry: FsEntry, mode: ExtractMode) {
        when (mode) {
            ExtractMode.Here -> vm.extract(entry.path, vm.path)
            ExtractMode.ToStem -> vm.extract(entry.path, joinPath(vm.path, archiveStem(entry.name), vm.pathSep))
            ExtractMode.Pick -> extractPick = ExtractRequest(entry.path, entry.name, null)
        }
    }

    fun startCompress(group: List<FsEntry>) {
        scope.launch {
            val caps = vm.ensureCaps()
            if (!caps.available || caps.createFormats.isEmpty()) {
                vm.message = "7-Zip was not found on the server, so archives cannot be created."
            } else {
                compressPaths = group.map { it.path }
                vm.clearSelection()
            }
        }
    }

    val browser = vm.archive

    if (browser != null) {
        ArchiveBrowserScreen(
            browser = browser,
            vm = vm,
            canExtract = vm.isAdmin,
            onExtract = { entries -> extractPick = ExtractRequest(browser.path, browser.name, entries) },
            onClose = vm::closeArchive,
        )
    } else Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = "Files", actions = {
            IconButton(onClick = { showSearch = !showSearch }) {
                Icon(Icons.Filled.Search, contentDescription = "Search")
            }
            IconButton(onClick = { vm.openRecycle() }) {
                Icon(Icons.Filled.Delete, contentDescription = "Recycle bin")
            }
            if (vm.isAdmin) {
                IconButton(onClick = { showNewFolder = true }) {
                    Icon(Icons.Filled.CreateNewFolder, contentDescription = "New folder")
                }
            }
            IconButton(onClick = { vm.refresh() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
        })

        val running = vm.runningJobs.size
        if (running > 0) {
            AssistChip(
                onClick = { vm.showLatestRunning() },
                label = { Text(if (running == 1) "1 transfer running" else "$running transfers running") },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        if (showSearch) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    placeholder = { Text("Search this PC…") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { vm.search(searchText, root = vm.path.ifBlank { null }) }) {
                    Text("Go")
                }
            }
            vm.searchBackend?.let {
                Text(
                    "backend: $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        // Path / context bar; a selection replaces it with the batch bar.
        if (vm.selecting && vm.mode == FilesMode.Browse) {
            SelectionBar(
                count = vm.selected.size,
                onSelectAll = { vm.selectAll(vm.entries.map { it.path }) },
                onClear = { vm.clearSelection() },
            ) {
                if (vm.isAdmin) {
                    val picked = vm.entries.filter { it.path in vm.selected }
                    val n = picked.size
                    TextButton(onClick = { transfer = picked to false }) { Text("Copy to…") }
                    TextButton(onClick = { transfer = picked to true }) { Text("Move to…") }
                    TextButton(onClick = { startCompress(picked) }) { Text("Compress ($n)") }
                    TextButton(onClick = { deleteTargets = picked }) {
                        Text("Delete ($n)", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        } else if (vm.selecting && vm.mode == FilesMode.Recycle) {
            SelectionBar(
                count = vm.selected.size,
                onSelectAll = { vm.selectAll(vm.recycleItems.map { it.iFile }) },
                onClear = { vm.clearSelection() },
            ) {
                if (vm.isAdmin) {
                    val picked = vm.recycleItems.filter { it.iFile in vm.selected }
                    TextButton(onClick = { vm.recycleRestore(picked) }) { Text("Restore (${picked.size})") }
                    TextButton(onClick = { recycleDeleteTargets = picked }) {
                        Text("Delete permanently (${picked.size})", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (vm.mode) {
                    FilesMode.Recycle -> {
                        Text("Recycle bin", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (vm.isAdmin) {
                            IconButton(onClick = { vm.recycleClear() }) {
                                Icon(Icons.Filled.DeleteSweep, contentDescription = "Empty recycle bin")
                            }
                        }
                        TextButton(onClick = { vm.open(vm.path.ifBlank { "" }) }) { Text("Files") }
                    }
                    FilesMode.Search -> {
                        Text("Results", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.open(vm.path.ifBlank { "" }) }) { Text("Back") }
                    }
                    FilesMode.Browse -> {
                        IconButton(onClick = { vm.navigateUp() }, enabled = vm.parent != null) {
                            Icon(Icons.Filled.ArrowUpward, contentDescription = "Up")
                        }
                        vm.currentDriveIndex?.let { i ->
                            val hues = SysMon.palette.driveHues
                            Icon(
                                Icons.Filled.Storage,
                                contentDescription = null,
                                tint = hues[i % hues.size],
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .size(18.dp),
                            )
                        }
                        Text(
                            vm.path.ifBlank { "…" },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        vm.message?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        Box(Modifier.fillMaxSize()) {
            when {
                vm.loading -> LoadingBox()
                vm.error != null -> ErrorBox(vm.error!!, onRetry = { vm.refresh() })
                else -> when (vm.mode) {
                    FilesMode.Browse -> BrowseList(
                        vm, onEditFile, onOpenPdf,
                        placesExpanded = placesExpanded,
                        onTogglePlaces = { placesExpanded = !placesExpanded },
                        onRename = { renameTarget = it },
                        onTransfer = { group, move -> transfer = group to move },
                        onDelete = { deleteTargets = it },
                        onExtract = ::runExtract,
                        onCompress = ::startCompress,
                    )
                    FilesMode.Recycle -> RecycleList(vm, onDeleteForever = { recycleDeleteTargets = it })
                    FilesMode.Search -> SearchList(vm)
                }
            }
        }
    }

    // ── Dialogs ────────────────────────────────────────────────────────────────
    if (showNewFolder) {
        TextPromptDialog("New folder", "Folder name", "", onConfirm = {
            vm.mkdir(it); showNewFolder = false
        }, onDismiss = { showNewFolder = false })
    }
    renameTarget?.let { t ->
        TextPromptDialog("Rename", "New name", t.name, onConfirm = {
            vm.rename(t.path, it); renameTarget = null
        }, onDismiss = { renameTarget = null })
    }
    transfer?.let { (group, isMove) ->
        FolderPickerDialog(
            vm = vm,
            sources = group,
            isMove = isMove,
            onConfirm = { dst, _ ->
                vm.transfer(group.map { it.path }, dst, isMove)
                transfer = null
            },
            onDismiss = { transfer = null },
        )
    }
    deleteTargets?.let { targets ->
        AlertDialog(
            onDismissRequest = { deleteTargets = null },
            title = { Text("Delete permanently?") },
            text = {
                Text(
                    if (targets.size == 1) "Delete ${targets.first().name}? This cannot be undone."
                    else "Delete ${targets.size} items? This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.deleteMany(targets.map { it.path }); deleteTargets = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTargets = null }) { Text("Cancel") } },
        )
    }
    recycleDeleteTargets?.let { targets ->
        AlertDialog(
            onDismissRequest = { recycleDeleteTargets = null },
            title = { Text("Delete permanently?") },
            text = {
                Text(
                    if (targets.size == 1) "Delete ${targets.first().name}? This cannot be undone."
                    else "Delete ${targets.size} items? This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.recycleDelete(targets); recycleDeleteTargets = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { recycleDeleteTargets = null }) { Text("Cancel") } },
        )
    }
    vm.properties?.let { props ->
        AlertDialog(
            onDismissRequest = { vm.properties = null },
            confirmButton = { TextButton(onClick = { vm.properties = null }) { Text("Close") } },
            title = { Text(props.name, maxLines = 1) },
            text = {
                Column {
                    StatRow("Path", props.path)
                    StatRow("Type", if (props.isDir) "Folder" else (props.mime ?: "File"))
                    StatRow("Size", formatBytes(props.size))
                    StatRow("Modified", formatDateTime(props.modified))
                    StatRow("Created", formatDateTime(props.created))
                    props.attributes?.takeIf { it.isNotBlank() }?.let { StatRow("Attributes", it) }
                }
            },
        )
    }
    vm.imagePreviewPath?.let { p ->
        AlertDialog(
            onDismissRequest = { vm.imagePreviewPath = null },
            confirmButton = { TextButton(onClick = { vm.imagePreviewPath = null }) { Text("Close") } },
            text = {
                AsyncImage(
                    model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                        .data(vm.fileUrl(p))
                        .build(),
                    imageLoader = vm.imageLoader,
                    contentDescription = p,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
    }
    extractPick?.let { req ->
        FolderPickerDialog(
            vm = vm,
            sources = emptyList(),
            isMove = false,
            title = if (req.entries == null) "Extract ${req.name} to" else "Extract ${req.entries.size} selected to",
            confirmLabel = "Extract",
            subfolderName = archiveStem(req.name),
            onConfirm = { dst, sub ->
                vm.extract(req.archivePath, if (sub) joinPath(dst, archiveStem(req.name), vm.pathSep) else dst, req.entries)
                extractPick = null
            },
            onDismiss = { extractPick = null },
        )
    }
    compressPaths?.let { paths ->
        CompressDialog(
            paths = paths,
            cwd = vm.path,
            sep = vm.pathSep,
            formats = vm.caps?.createFormats.orEmpty(),
            pickFolder = { current, onPicked -> folderPick = FolderPick(current, onPicked) },
            onConfirm = { req ->
                vm.compress(paths, req.dest, req.format, req.password)
                compressPaths = null
            },
            onDismiss = { compressPaths = null },
        )
    }
    folderPick?.let { pick ->
        FolderPickerDialog(
            vm = vm,
            sources = emptyList(),
            isMove = false,
            title = "Save archive in",
            confirmLabel = "Select",
            startPath = pick.current,
            onConfirm = { dst, _ -> pick.onPicked(dst); folderPick = null },
            onDismiss = { folderPick = null },
        )
    }
    vm.passwordRequest?.let { req ->
        PasswordDialog(
            message = req.message,
            onSubmit = { vm.answerPassword(it) },
            onCancel = { vm.answerPassword(null) },
        )
    }
    TransferProgressCard(vm)
}

/**
 * Batch bar shown while rows are selected. [extraActions] sits after the built-in
 * buttons so later features can add entries without touching the layout.
 */
@Composable
internal fun SelectionBar(
    count: Int,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    extraActions: @Composable RowScope.() -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(SysMon.palette.track)
            .padding(horizontal = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$count selected",
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
            TextButton(onClick = onSelectAll) { Text("Select all") }
            TextButton(onClick = onClear) { Text("Clear") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) { extraActions() }
    }
}

@Composable
private fun BrowseList(
    vm: FilesViewModel,
    onEditFile: (String) -> Unit,
    onOpenPdf: (String) -> Unit,
    placesExpanded: Boolean,
    onTogglePlaces: () -> Unit,
    onRename: (FsEntry) -> Unit,
    onTransfer: (List<FsEntry>, Boolean) -> Unit,
    onDelete: (List<FsEntry>) -> Unit,
    onExtract: (FsEntry, ExtractMode) -> Unit,
    onCompress: (List<FsEntry>) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
        item(key = "places") { PlacesSection(vm, placesExpanded, onTogglePlaces) }
        items(vm.entries, key = { it.path }) { entry ->
            val isSelected = entry.path in vm.selected
            EntryRow(
                entry = entry,
                vm = vm,
                selected = isSelected,
                onOpen = {
                    when {
                        entry.isDir -> vm.open(entry.path)
                        isImage(entry.name) -> vm.imagePreviewPath = entry.path
                        isPdf(entry.name) -> onOpenPdf(entry.path)
                        archiveTap(vm, entry.name) -> vm.openArchive(entry.path)
                        else -> onEditFile(entry.path)
                    }
                },
                onRename = { onRename(entry) },
                onTransfer = { move ->
                    onTransfer(menuGroup(vm, entry), move)
                },
                onDelete = { onDelete(menuGroup(vm, entry)) },
                onProperties = { vm.loadProperties(entry.path) },
                onFavorite = { vm.addFavorite(entry) },
                isArchive = vm.isArchive(entry.name),
                onExtract = { mode -> onExtract(entry, mode) },
                onCompress = { onCompress(menuGroup(vm, entry)) },
            )
        }
    }
}

/** Rows a menu action applies to: the whole selection if [entry] is part of one, else just it. */
private fun menuGroup(vm: FilesViewModel, entry: FsEntry): List<FsEntry> =
    if (entry.path in vm.selected && vm.selected.size > 1) vm.entries.filter { it.path in vm.selected }
    else listOf(entry)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    entry: FsEntry,
    vm: FilesViewModel,
    selected: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onTransfer: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onProperties: () -> Unit,
    onFavorite: () -> Unit,
    isArchive: Boolean,
    onExtract: (ExtractMode) -> Unit,
    onCompress: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val canMutate = vm.isAdmin
    val n = if (selected && vm.selected.size > 1) vm.selected.size else 1
    val countSuffix = if (n > 1) " ($n)" else ""
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickable(
                onClick = { if (vm.selecting) vm.toggleSelected(entry.path) else onOpen() },
                onLongClick = { vm.toggleSelected(entry.path) },
            )
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (vm.selecting) {
            Checkbox(checked = selected, onCheckedChange = { vm.toggleSelected(entry.path) })
        }
        EntryLeading(entry, vm)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, maxLines = 1, fontWeight = FontWeight.Medium)
            Text(
                if (entry.isDir) formatDateTime(entry.modified)
                else "${formatBytes(entry.size)} · ${formatDateTime(entry.modified)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Actions")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; onOpen() })
                if (canMutate && n == 1) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                }
                if (canMutate) {
                    DropdownMenuItem(
                        text = { Text("Copy$countSuffix to…") },
                        onClick = { menu = false; onTransfer(false) },
                    )
                    DropdownMenuItem(
                        text = { Text("Move$countSuffix to…") },
                        onClick = { menu = false; onTransfer(true) },
                    )
                    DropdownMenuItem(text = { Text("Add to favorites") }, onClick = { menu = false; onFavorite() })
                    if (isArchive && n == 1) {
                        DropdownMenuItem(text = { Text("Extract here") }, onClick = { menu = false; onExtract(ExtractMode.Here) })
                        DropdownMenuItem(
                            text = { Text("Extract to “${archiveStem(entry.name)}/”") },
                            onClick = { menu = false; onExtract(ExtractMode.ToStem) },
                        )
                        DropdownMenuItem(text = { Text("Extract to…") }, onClick = { menu = false; onExtract(ExtractMode.Pick) })
                    }
                    DropdownMenuItem(
                        text = { Text(if (n > 1) "Compress ($n)…" else "Compress…") },
                        onClick = { menu = false; onCompress() },
                    )
                }
                DropdownMenuItem(text = { Text("Properties") }, onClick = { menu = false; onProperties() })
                if (canMutate) {
                    DropdownMenuItem(
                        text = { Text("Delete$countSuffix", color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecycleList(vm: FilesViewModel, onDeleteForever: (List<RecycleItem>) -> Unit) {
    if (vm.recycleItems.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Recycle bin is empty", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
        items(vm.recycleItems, key = { it.iFile }) { item ->
            val isSelected = item.iFile in vm.selected
            // A button on a row in a multi-selection acts on the whole selection.
            val group = if (isSelected && vm.selected.size > 1) vm.recycleItems.filter { it.iFile in vm.selected }
            else listOf(item)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        else androidx.compose.ui.graphics.Color.Transparent,
                    )
                    .combinedClickable(
                        onClick = { if (vm.selecting) vm.toggleSelected(item.iFile) },
                        onLongClick = { vm.toggleSelected(item.iFile) },
                    )
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (vm.selecting) {
                    Checkbox(checked = isSelected, onCheckedChange = { vm.toggleSelected(item.iFile) })
                }
                KindIcon(item.name, item.isDir)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, maxLines = 1, fontWeight = FontWeight.Medium)
                    Text(
                        item.originalPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (vm.isAdmin && !vm.selecting) {
                    IconButton(onClick = { vm.recycleRestore(group) }) {
                        Icon(Icons.Filled.Restore, contentDescription = "Restore", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { vm.recycleDelete(group) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete forever", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchList(vm: FilesViewModel) {
    if (vm.searchResults.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No results", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
        items(vm.searchResults, key = { it.path }) { res ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        val parent = res.path.substringBeforeLast('\\', res.path.substringBeforeLast('/', ""))
                        if (parent.isNotBlank()) vm.open(parent)
                    }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KindIcon(res.name, res.isDir)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(res.name, maxLines = 1, fontWeight = FontWeight.Medium)
                    Text(
                        res.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun TextPromptDialog(
    title: String,
    label: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
