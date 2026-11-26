package com.raavivi.sysmon.ui.chatindex

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raavivi.sysmon.core.model.LexEntry
import com.raavivi.sysmon.core.model.LexRejected
import com.raavivi.sysmon.core.model.LexSuggestion
import com.raavivi.sysmon.ui.common.SectionCard

/**
 * Lexicon: the private-words glossary, edited live. An entry is one meaning with the words that share it. Add or
 * edit with words as chips (a word is added whole: a comma is just a character) and a one-line meaning; delete
 * asks first; suggestions can be accepted (prefilling the add form) or rejected; rejected words can be undone.
 * Words and meanings are private and never logged.
 */
@Composable
internal fun LexiconTab(lx: LexiconState) {
    when (lx.phase) {
        LoadPhase.IDLE, LoadPhase.LOADING -> CiLoading()
        LoadPhase.ERROR, LoadPhase.MISSING -> CiStateBlock(
            title = "Could not load the private words",
            hint = lx.loadError,
            actionLabel = "Retry",
            onAction = lx::load,
            isError = true,
        )
        LoadPhase.READY -> LexiconList(lx)
    }
}

@Composable
private fun LexiconList(lx: LexiconState) {
    val listState = rememberLazyListState()
    val meaningFocus = remember { FocusRequester() }
    LaunchedEffect(lx.acceptTick) {
        if (lx.acceptTick > 0) {
            listState.animateScrollToItem(0)
            runCatching { meaningFocus.requestFocus() }
        }
    }
    val data = lx.data
    val shown = lx.shown
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (lx.status.isNotBlank()) CiLiveLine(lx.status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SectionCard(title = "Add entry") {
                    LexFormView(lx.addForm, "Add entry", onSubmit = lx::submitAdd, meaningFocus = meaningFocus)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CiHeading("Entries (${data.entries.size})")
                OutlinedTextField(
                    value = lx.filter,
                    onValueChange = { lx.filter = it },
                    label = { Text("Filter words or meanings") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (shown.isEmpty()) {
            item {
                if (data.entries.isEmpty()) {
                    CiStateBlock("No private words yet", "Add one above, or accept a suggestion below.")
                } else {
                    CiStateBlock("No entry matches the filter", "Clear the filter to see every entry.", actionLabel = "Clear filter", onAction = { lx.filter = "" })
                }
            }
        }
        items(shown, key = { it.id }) { e ->
            RowCard {
                if (lx.editingId == e.id) {
                    Column(Modifier.padding(16.dp)) {
                        LexFormView(lx.editForm, "Save", onSubmit = { lx.submitEdit(e) }, onCancel = lx::cancelEdit, startOnMeaning = true)
                    }
                } else {
                    EntryView(e, onEdit = { lx.startEdit(e) }, onDelete = { lx.askDelete(e) })
                }
            }
        }
        item { CiHeading("Suggestions") }
        if (data.suggestions.isEmpty()) {
            item { CiStateBlock("No suggestions", "Words the card writer finds in your chats show up here.") }
        }
        items(data.suggestions, key = { "s:" + it.term }) { s ->
            RowCard { SuggestionView(s, onAccept = { lx.accept(s) }, onReject = { lx.reject(s) }) }
        }
        item { CiHeading("Rejected") }
        if (data.rejected.isEmpty()) {
            item { CiStateBlock("Nothing rejected", "A rejected suggestion is never offered again; Undo takes it back.") }
        }
        items(data.rejected, key = { "r:" + it.term }) { r ->
            RowCard { RejectedView(r, onUndo = { lx.undoReject(r) }) }
        }
        importLine(data.legacyImport).takeIf { it.isNotBlank() }?.let { line ->
            item { Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    lx.confirmDelete?.let {
        AlertDialog(
            onDismissRequest = lx::keepEntry,
            title = { Text("Delete this entry?") },
            text = { Text("The entry and all its words are removed. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = lx::confirmDeleteEntry, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = lx::keepEntry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep") } },
        )
    }
}

// -- rows ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryView(e: LexEntry, onEdit: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (t in e.terms) CiChip(t, emphasis = true)
        }
        Text(e.meaning, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onEdit, modifier = Modifier.heightIn(min = 48.dp)) { Text("Edit") }
            TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun SuggestionView(s: LexSuggestion, onAccept: () -> Unit, onReject: () -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CiChip(s.term, emphasis = true)
        Text(
            s.meaning?.takeIf { it.isNotBlank() } ?: "No meaning given",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(seenText(s.seen), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAccept, modifier = Modifier.heightIn(min = 48.dp)) { Text("Accept") }
            OutlinedButton(onClick = onReject, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reject") }
        }
    }
}

@Composable
private fun RejectedView(r: LexRejected, onUndo: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CiChip(r.term, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onUndo, modifier = Modifier.heightIn(min = 48.dp)) { Text("Undo") }
    }
}

// -- the entry form -----------------------------------------------------------------------------------

/**
 * Words as removable chips, a word field with its Add word button (the keyboard's action adds it too), a one-line
 * meaning and the submit button. The reason a field was refused shows under it; [meaningFocus] lets Accept move
 * the caret to the meaning, and [startOnMeaning] does the same when an edit opens.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LexFormView(
    form: LexForm,
    submitLabel: String,
    onSubmit: () -> Unit,
    onCancel: (() -> Unit)? = null,
    meaningFocus: FocusRequester? = null,
    startOnMeaning: Boolean = false,
) {
    val focus = meaningFocus ?: remember { FocusRequester() }
    if (startOnMeaning) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (form.terms.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                form.terms.forEachIndexed { i, t -> TermChip(t) { form.removeTerm(i) } }
            }
        }
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = form.termInput,
                onValueChange = form::onTermInput,
                label = { Text("Word") },
                singleLine = true,
                isError = form.termsError.isNotEmpty(),
                supportingText = { Text(form.termsError.ifEmpty { "One word or phrase at a time" }) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { form.commitTerm() }),
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = { form.commitTerm() },
                modifier = Modifier.heightIn(min = 56.dp),
            ) { Text("Add word") }
        }
        OutlinedTextField(
            value = form.meaning,
            onValueChange = form::onMeaning,
            label = { Text("Meaning") },
            singleLine = true,
            isError = form.meaningError.isNotEmpty(),
            supportingText = {
                Text(form.meaningError.ifEmpty { "${normText(form.meaning).let { it.codePointCount(0, it.length) }}/$LEX_MAX_MEANING" })
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        if (form.formError.isNotEmpty()) CiLiveLine(form.formError)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onSubmit, enabled = !form.busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                if (form.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(submitLabel)
            }
            if (onCancel != null) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
            }
        }
    }
}

/** One word of the form: a pill with a Remove button (a 48 dp target) that names the word to TalkBack. */
@Composable
private fun TermChip(term: String, onRemove: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(16.dp))
        Text(
            term,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove word $term",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
