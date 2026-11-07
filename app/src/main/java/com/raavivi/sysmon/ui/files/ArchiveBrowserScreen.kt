package com.raavivi.sysmon.ui.files

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.raavivi.sysmon.AppContainer
import com.raavivi.sysmon.core.model.ArchiveEntryBody
import com.raavivi.sysmon.core.model.ArchiveListBody
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.safeCall
import com.raavivi.sysmon.ui.common.ErrorBox
import com.raavivi.sysmon.ui.common.LoadingBox
import com.raavivi.sysmon.ui.common.ScreenHeader
import com.raavivi.sysmon.ui.common.formatBytes
import com.raavivi.sysmon.ui.theme.SysMon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the browser is showing for a tapped file. */
sealed interface ArchivePreview {
    val name: String

    data class Image(override val name: String, val file: File) : ArchivePreview
    data class Pdf(override val name: String, val file: File) : ArchivePreview
    data class Text(override val name: String, val text: String) : ArchivePreview
    /** No built-in viewer: offer to save it. */
    data class Other(override val name: String, val file: File) : ArchivePreview
}

private const val MAX_TEXT_BYTES = 1L shl 20

/**
 * State of one open archive: the listing tree, where the user is inside it, and
 * entry previews. The password lives here only, never persisted.
 */
class ArchiveBrowser(
    private val container: AppContainer,
    private val scope: CoroutineScope,
    val path: String,
    private val ask: suspend (String) -> String?,
) {
    val name: String = baseName(path)

    var tree by mutableStateOf<ArchiveTree?>(null)
        private set
    var cwd by mutableStateOf("")
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var fetching by mutableStateOf(false)
        private set
    var selected by mutableStateOf<Set<String>>(emptySet())
        private set
    var preview by mutableStateOf<ArchivePreview?>(null)
    var notice by mutableStateOf<String?>(null)

    private var password: String? = null
    private val cacheDir = File(container.appContext.cacheDir, "archive_preview")

    val selecting: Boolean get() = selected.isNotEmpty()
    val nodes: List<ArchiveNode> get() = tree?.list(cwd).orEmpty()

    fun load() {
        loading = true
        error = null
        scope.launch {
            val out = withPassword(name, password, ask) { pw ->
                safeCall { container.api.api.archiveList(ArchiveListBody(path, pw)) }
            }
            when (out) {
                is PasswordOutcome.Done -> {
                    password = out.password
                    tree = ArchiveTree.build(out.value.entries)
                    cwd = ""
                }
                is PasswordOutcome.Failed -> error = archiveErrorMessage(out.error)
                PasswordOutcome.Cancelled -> error = "A password is needed to open this archive"
            }
            loading = false
        }
    }

    fun enter(dir: ArchiveNode) {
        cwd = dir.path
        selected = emptySet()
    }

    fun up() {
        cwd = parentPath(cwd)
        selected = emptySet()
    }

    fun toggle(nodePath: String) {
        selected = if (nodePath in selected) selected - nodePath else selected + nodePath
    }

    fun selectAll() {
        selected = nodes.map { it.path }.toSet()
    }

    fun clearSelection() {
        selected = emptySet()
    }

    /** Archive paths of the selected nodes, in the spelling the host expects. */
    fun selectedEntryPaths(): List<String> = nodes.filter { it.path in selected }.map { it.entryPath }

    fun open(node: ArchiveNode) {
        if (node.dir) return enter(node)
        if (fetching) return
        fetching = true
        scope.launch {
            val out = withPassword(name, password, ask) { pw ->
                safeCall { fetchEntry(node, pw) }
            }
            when (out) {
                is PasswordOutcome.Done -> {
                    password = out.password
                    preview = classify(node, out.value)
                }
                is PasswordOutcome.Failed -> notice = archiveErrorMessage(out.error)
                PasswordOutcome.Cancelled -> {}
            }
            fetching = false
        }
    }

    private suspend fun fetchEntry(node: ArchiveNode, pw: String?): File = withContext(Dispatchers.IO) {
        cacheDir.mkdirs()
        val file = File(cacheDir, "${node.entryPath.hashCode()}_${node.name}")
        container.api.api.archiveEntry(ArchiveEntryBody(path, node.entryPath, pw)).use { body ->
            file.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
        file
    }

    private suspend fun classify(node: ArchiveNode, file: File): ArchivePreview {
        val kind = fileKind(node.name, false)
        return when {
            kind == FileKind.Media && mediaType(node.name) == MediaType.Image ->
                ArchivePreview.Image(node.name, file)
            extOf(node.name) == "pdf" -> ArchivePreview.Pdf(node.name, file)
            isTextLike(node.name) && file.length() <= MAX_TEXT_BYTES ->
                ArchivePreview.Text(node.name, withContext(Dispatchers.IO) { file.readText() })
            else -> ArchivePreview.Other(node.name, file)
        }
    }

    fun cleanup() {
        cacheDir.deleteRecursively()
    }
}

/**
 * Full-screen archive browser shown over the explorer. [onExtract] gets the chosen
 * archive paths, or null for everything; it is only offered when [canExtract].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArchiveBrowserScreen(
    browser: ArchiveBrowser,
    vm: FilesViewModel,
    canExtract: Boolean,
    onExtract: (List<String>?) -> Unit,
    onClose: () -> Unit,
) {
    val preview = browser.preview
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf<ArchivePreview.Other?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val src = saving?.file
        if (uri != null && src != null) {
            scope.launch {
                val ok = runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                    }
                }.isSuccess
                browser.notice = if (ok) "Saved" else "Could not save the file"
            }
        }
        saving = null
    }

    BackHandler {
        when {
            browser.preview != null -> browser.preview = null
            browser.selecting -> browser.clearSelection()
            browser.cwd.isNotEmpty() -> browser.up()
            else -> onClose()
        }
    }

    if (preview is ArchivePreview.Pdf) {
        PdfViewerScreen(file = preview.file, title = preview.name, onBack = { browser.preview = null })
        return
    }
    if (preview is ArchivePreview.Text) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(title = preview.name, actions = {
                IconButton(onClick = { browser.preview = null }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            })
            Text(
                preview.text,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            )
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = browser.name, actions = {
            if (canExtract) {
                IconButton(onClick = { onExtract(null) }) {
                    Icon(Icons.Filled.Unarchive, contentDescription = "Extract all")
                }
            }
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close archive")
            }
        })

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val parts = crumbs(browser.cwd)
            parts.forEachIndexed { i, (label, p) ->
                TextButton(onClick = { while (browser.cwd != p) browser.up() }, enabled = i != parts.lastIndex) {
                    Text(label, fontFamily = FontFamily.Monospace)
                }
            }
        }

        browser.notice?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        if (browser.selecting) {
            SelectionBar(
                count = browser.selected.size,
                onSelectAll = browser::selectAll,
                onClear = browser::clearSelection,
            ) {
                if (canExtract) {
                    TextButton(onClick = { onExtract(browser.selectedEntryPaths()) }) { Text("Extract selected") }
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            when {
                browser.loading -> LoadingBox()
                browser.error != null -> ErrorBox(browser.error!!, onRetry = browser::load)
                else -> {
                    val nodes = browser.nodes
                    if (nodes.isEmpty()) {
                        Text(
                            "This folder is empty",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(16.dp),
                        )
                    } else {
                        LazyColumn(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            items(nodes, key = { it.path }) { node ->
                                ArchiveRow(
                                    node,
                                    selected = node.path in browser.selected,
                                    selecting = browser.selecting,
                                    onClick = {
                                        if (browser.selecting) browser.toggle(node.path) else browser.open(node)
                                    },
                                    onLongClick = { browser.toggle(node.path) },
                                )
                            }
                        }
                    }
                    if (browser.fetching) LoadingBox()
                }
            }
        }
    }

    if (preview is ArchivePreview.Image) {
        AlertDialog(
            onDismissRequest = { browser.preview = null },
            confirmButton = { TextButton(onClick = { browser.preview = null }) { Text("Close") } },
            text = {
                AsyncImage(
                    model = preview.file,
                    imageLoader = vm.imageLoader,
                    contentDescription = preview.name,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
    }
    if (preview is ArchivePreview.Other) {
        AlertDialog(
            onDismissRequest = { browser.preview = null },
            title = { Text(preview.name, maxLines = 1) },
            text = { Text("No built-in viewer for this file (${formatBytes(preview.file.length())}).") },
            confirmButton = {
                TextButton(onClick = {
                    saving = preview
                    browser.preview = null
                    saver.launch(preview.name)
                }) { Text("Save as…") }
            },
            dismissButton = { TextButton(onClick = { browser.preview = null }) { Text("Close") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArchiveRow(
    node: ArchiveNode,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val palette = SysMon.palette
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else androidx.compose.ui.graphics.Color.Transparent,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) Checkbox(checked = selected, onCheckedChange = { onLongClick() })
        KindIcon(node.name, node.dir)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(node.name, maxLines = 1, fontWeight = FontWeight.Medium)
            val parts = buildList {
                add(formatBytes(node.size))
                if (node.packed > 0) add("packed ${formatBytes(node.packed)}")
                node.modified?.let { add(it) }
            }
            Text(
                parts.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = if (palette.monoNumbers) FontFamily.Monospace else null,
            )
        }
        if (node.encrypted) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = "Encrypted",
                tint = palette.quietInk,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
