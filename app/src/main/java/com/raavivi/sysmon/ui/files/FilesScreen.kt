package com.raavivi.sysmon.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.raavivi.sysmon.core.model.FsEntry
import com.raavivi.sysmon.ui.common.ErrorBox
import com.raavivi.sysmon.ui.common.LoadingBox
import com.raavivi.sysmon.ui.common.ScreenHeader
import com.raavivi.sysmon.ui.common.StatRow
import com.raavivi.sysmon.ui.common.formatBytes
import com.raavivi.sysmon.ui.common.formatDateTime
import com.raavivi.sysmon.ui.common.rememberContainerViewModel
import kotlinx.coroutines.delay

private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "ico")

private fun isImage(name: String) = name.substringAfterLast('.', "").lowercase() in IMAGE_EXT

private fun isPdf(name: String) = name.substringAfterLast('.', "").equals("pdf", ignoreCase = true)

@Composable
fun FilesScreen(onEditFile: (String) -> Unit, onOpenPdf: (String) -> Unit = {}) {
    val vm = rememberContainerViewModel { FilesViewModel(it) }

    var showSearch by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var showNewFolder by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FsEntry?>(null) }
    var transfer by remember { mutableStateOf<Pair<FsEntry, Boolean>?>(null) }
    var deleteTarget by remember { mutableStateOf<FsEntry?>(null) }

    vm.message?.let { msg -> LaunchedEffect(msg) { delay(2500); vm.message = null } }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = "Files", actions = {
            IconButton(onClick = { showSearch = !showSearch }) {
                Icon(Icons.Filled.Search, contentDescription = "Search")
            }
            IconButton(onClick = { vm.openRecycle() }) {
                Icon(Icons.Filled.Delete, contentDescription = "Recycle bin")
            }
            IconButton(onClick = { showNewFolder = true }) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = "New folder")
            }
            IconButton(onClick = { vm.refresh() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
        })

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

        // Path / context bar
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (vm.mode) {
                FilesMode.Recycle -> {
                    Text("Recycle bin", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { vm.recycleClear() }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Empty recycle bin")
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
                    FilesMode.Browse -> BrowseList(vm, onEditFile, onOpenPdf, onRename = { renameTarget = it },
                        onTransfer = { e, move -> transfer = e to move }, onDelete = { deleteTarget = it })
                    FilesMode.Recycle -> RecycleList(vm)
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
    transfer?.let { (entry, isMove) ->
        TextPromptDialog(
            title = if (isMove) "Move to" else "Copy to",
            label = "Destination folder",
            initial = vm.path,
            onConfirm = { dst ->
                if (isMove) vm.moveTo(entry.path, dst) else vm.copyTo(entry.path, dst)
                transfer = null
            },
            onDismiss = { transfer = null },
        )
    }
    deleteTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete permanently?") },
            text = { Text("${t.name} will be deleted (not sent to recycle bin).") },
            confirmButton = {
                TextButton(onClick = { vm.delete(t.path); deleteTarget = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
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
}

@Composable
private fun BrowseList(
    vm: FilesViewModel,
    onEditFile: (String) -> Unit,
    onOpenPdf: (String) -> Unit,
    onRename: (FsEntry) -> Unit,
    onTransfer: (FsEntry, Boolean) -> Unit,
    onDelete: (FsEntry) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
        items(vm.entries, key = { it.path }) { entry ->
            EntryRow(
                entry = entry,
                onOpen = {
                    when {
                        entry.isDir -> vm.open(entry.path)
                        isImage(entry.name) -> vm.imagePreviewPath = entry.path
                        isPdf(entry.name) -> onOpenPdf(entry.path)
                        else -> onEditFile(entry.path)
                    }
                },
                onRename = { onRename(entry) },
                onCopy = { onTransfer(entry, false) },
                onMove = { onTransfer(entry, true) },
                onDelete = { onDelete(entry) },
                onProperties = { vm.loadProperties(entry.path) },
                onFavorite = { vm.addFavorite(entry) },
            )
        }
    }
}

@Composable
private fun EntryRow(
    entry: FsEntry,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onProperties: () -> Unit,
    onFavorite: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when {
                entry.isDir -> Icons.Filled.Folder
                isImage(entry.name) -> Icons.Filled.Image
                else -> Icons.AutoMirrored.Filled.InsertDriveFile
            },
            contentDescription = null,
            tint = if (entry.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Copy to…") }, onClick = { menu = false; onCopy() })
                DropdownMenuItem(text = { Text("Move to…") }, onClick = { menu = false; onMove() })
                DropdownMenuItem(text = { Text("Add to favorites") }, onClick = { menu = false; onFavorite() })
                DropdownMenuItem(text = { Text("Properties") }, onClick = { menu = false; onProperties() })
                DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun RecycleList(vm: FilesViewModel) {
    if (vm.recycleItems.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Recycle bin is empty", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
        items(vm.recycleItems, key = { it.iFile }) { item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                IconButton(onClick = { vm.recycleRestore(item) }) {
                    Icon(Icons.Filled.Restore, contentDescription = "Restore", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { vm.recycleDelete(item) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete forever", tint = MaterialTheme.colorScheme.error)
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
                Icon(
                    if (res.isDir) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
