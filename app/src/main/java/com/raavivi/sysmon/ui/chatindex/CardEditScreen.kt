package com.raavivi.sysmon.ui.chatindex

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull

/** How long "Removed · Undo" waits. */
private const val UNDO_MS = 5_000L

/**
 * The card editor, a full-screen view over the card: Close in the top bar, the fields in one lazy list, and a
 * bottom bar (above the keyboard) with the warning that Saving replaces the card, Cancel and Save. Errors sit
 * right above the bottom bar. Leaving with changes asks once. Card text is private and never logged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardEditScreen(e: CardEditLogic) {
    val snackbar = remember { SnackbarHostState() }
    BackHandler { e.cancel() }

    LaunchedEffect(e.removalSeq) {
        if (e.removalSeq == 0) return@LaunchedEffect
        val r = withTimeoutOrNull(UNDO_MS) {
            snackbar.showSnackbar("Removed", actionLabel = "Undo", duration = SnackbarDuration.Indefinite)
        }
        if (r == SnackbarResult.ActionPerformed) e.undoRemoval()
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text("Edit card", style = MaterialTheme.typography.titleLarge) },
            navigationIcon = {
                IconButton(onClick = e::cancel) { Icon(Icons.Filled.Close, contentDescription = "Cancel editing") }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { editorItems(e) }
        SnackbarHost(snackbar, Modifier.align(Alignment.CenterHorizontally))
        e.banner?.let { ErrorBanner(it, dirty = e.dirty, reloading = e.reloading, onReload = e::reload) }
        EditBottomBar(e)
    }

    if (e.confirmingDiscard) {
        AlertDialog(
            onDismissRequest = e::keepEditing,
            title = { Text("Discard your changes?") },
            confirmButton = {
                TextButton(onClick = e::discard, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = e::keepEditing, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep editing") }
            },
        )
    }
}

/** The reason a save failed, in an error container above the bottom bar; a stale card offers Reload card. */
@Composable
private fun ErrorBanner(b: EditBanner, dirty: Boolean, reloading: Boolean, onReload: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(b.text, style = MaterialTheme.typography.bodyLarge)
            if (b.canReload) {
                if (dirty) Text("Reload discards your edits.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onReload, enabled = !reloading, modifier = Modifier.heightIn(min = 48.dp)) {
                    if (reloading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Text("Reload card")
                }
            }
        }
    }
}

/** The note that Save cannot be undone, then Cancel and Save (progress in place of the label while saving). */
@Composable
private fun EditBottomBar(e: CardEditLogic) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Note("Saving replaces this card. There is no undo.")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = e::cancel, enabled = !e.saving, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                Spacer(Modifier.weight(1f))
                Button(onClick = e::save, enabled = !e.saving && !e.reloading, modifier = Modifier.heightIn(min = 48.dp)) {
                    if (e.saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                    else Text("Save")
                }
            }
        }
    }
}
