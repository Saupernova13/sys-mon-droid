package com.raavivi.sysmon.ui.chatindex

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raavivi.sysmon.ui.whatsapp.kindLabel

// The card editor's rows: one composable per kind of thing in a card. Type: titleSmall (section headers),
// bodyLarge (fields), bodySmall (notes and counts). Every icon button names its row for TalkBack and is 48 dp.
// Card text is private: nothing here logs.

/** A secondary note: bodySmall at 70% of the text colour. */
@Composable
internal fun Note(text: String, modifier: Modifier = Modifier, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        modifier = modifier,
    )
}

/**
 * A section's header. [collapsed] null is an open section (no chevron); otherwise the row is a 48 dp toggle that
 * shows, while collapsed, the first item as a one-line [preview]. TalkBack hears the count and the state.
 */
@Composable
internal fun SectionHeader(title: String, count: Int?, collapsed: Boolean?, preview: String, onToggle: () -> Unit) {
    val spoken = if (count == null) title else "$title, $count"
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .heightIn(min = 48.dp)
            .then(
                if (collapsed == null) Modifier
                else Modifier
                    .clickable(role = Role.Button, onClick = onToggle)
                    .semantics { stateDescription = if (collapsed) "Collapsed" else "Expanded" },
            )
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (count != null) Note(count.toString())
            }
            if (collapsed == true && preview.isNotBlank()) {
                Text(
                    preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (collapsed != null) {
            Icon(if (collapsed) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp, contentDescription = null)
        }
    }
}

/** A remove button that names its row ("Remove said line 3"). */
@Composable
internal fun RemoveButton(description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Outlined.Delete, contentDescription = description)
    }
}

/** One text field; [hint] is a client-side note that never blocks Save. */
@Composable
internal fun EditField(
    value: String,
    label: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    minLines: Int = 1,
    hint: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else minLines,
        isError = hint != null,
        supportingText = hint?.let { { Text(it) } },
        trailingIcon = trailing,
        modifier = modifier.fillMaxWidth(),
    )
}

/** One line of said, meant or a recall query, with its remove icon inside the field. */
@Composable
internal fun LineRow(text: String, label: String, removeLabel: String, onChange: (String) -> Unit, onRemove: () -> Unit) {
    EditField(text, label, onChange, trailing = { RemoveButton(removeLabel, onRemove) })
}

/** A header line for an act, facet, detail or word: "Act 2" with its remove icon. */
@Composable
internal fun ItemHeader(title: String, removeLabel: String, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        RemoveButton(removeLabel, onRemove)
    }
}

/** A key moment: its kind, its date and one line; it can only be removed, and a removed one can be put back. */
@Composable
internal fun MomentRow(m: MomentDraft, n: Int, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).alpha(if (m.removed) 0.6f else 1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (m.kind.isNotBlank()) CiChip(kindLabel(m.kind))
                m.ts?.let { Note(momentText(it)) }
            }
            Text(
                m.oneLine,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (m.removed) TextDecoration.LineThrough else null,
            )
        }
        IconButton(onClick = onToggle, modifier = Modifier.size(48.dp)) {
            if (m.removed) Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo removing moment $n")
            else Icon(Icons.Outlined.Delete, contentDescription = "Remove moment $n")
        }
    }
}

/** A chip row: each chip removes itself when tapped; Add opens a one-line field. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChipEditor(
    chips: List<Line>,
    noun: String,
    onAdd: (String) -> Unit,
    onRemove: (Line) -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    fun commit() {
        if (typed.isNotBlank()) onAdd(typed.trim())
        typed = ""
        adding = false
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in chips) {
                InputChip(
                    selected = false,
                    onClick = { onRemove(c) },
                    label = { Text(c.text.ifBlank { "(empty)" }) },
                    trailingIcon = { Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.semantics { contentDescription = "Remove $noun ${c.text}" },
                )
            }
            if (!adding) {
                AssistChip(onClick = { adding = true }, label = { Text("Add") })
            }
        }
        if (adding) {
            EditField(
                typed, "New $noun", { typed = it }, singleLine = true,
                trailing = { TextButton(onClick = ::commit, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add") } },
            )
        }
    }
}

/** An act: its kind, its intensity (1 to 5, one choice) and whether it was settled. */
@Composable
internal fun ActRow(a: ActDraft, n: Int, onChange: (ActDraft) -> Unit, onRemove: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ItemHeader("Act $n", "Remove act $n", onRemove)
        EditField(a.kind, "Kind", { onChange(a.copy(kind = it)) }, singleLine = true)
        Note("Intensity")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            INTENSITIES.forEachIndexed { i, v ->
                SegmentedButton(
                    selected = a.intensity == v,
                    onClick = { onChange(a.copy(intensity = v)) },
                    shape = SegmentedButtonDefaults.itemShape(i, INTENSITIES.count()),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.primary,
                        activeContentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    icon = {},
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Intensity $v of 5, act $n" },
                ) { Text(v.toString()) }
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Settled", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(
                checked = a.settled,
                onCheckedChange = { onChange(a.copy(settled = it)) },
                modifier = Modifier.semantics { contentDescription = "Act $n settled" },
            )
        }
    }
}

/** A facet: its name from a list and its confidence on a 0 to 1 slider in 0.05 steps. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FacetRow(f: FacetDraft, n: Int, choices: List<String>, hint: String?, onChange: (FacetDraft) -> Unit, onRemove: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ItemHeader("Facet $n", "Remove facet $n", onRemove)
        ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
            OutlinedTextField(
                value = f.name,
                onValueChange = {},
                readOnly = true,
                label = { Text("Facet") },
                isError = hint != null && f.name.isBlank(),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (c in choices) {
                    DropdownMenuItem(
                        text = { Text(kindLabel(c)) },
                        onClick = { onChange(f.copy(name = c)); open = false },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Slider(
                value = f.confidence.toFloat().coerceIn(0f, 1f),
                onValueChange = { onChange(f.copy(confidence = snapConfidence(it.toDouble()))) },
                valueRange = 0f..1f,
                steps = 19,
                modifier = Modifier.weight(1f).semantics { contentDescription = "Confidence of facet $n" },
            )
            Text(confidenceText(f.confidence), style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
        }
        hint?.let { Note(it, error = true) }
    }
}

/** A detail: up to [max] kinds as filter chips, who, and the text. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DetailRow(
    d: DetailDraft,
    n: Int,
    choices: List<String>,
    max: Int,
    hint: String?,
    onChange: (DetailDraft) -> Unit,
    onRemove: () -> Unit,
) {
    val full = kindsFull(d.kinds, max)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ItemHeader("Detail $n", "Remove detail $n", onRemove)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (k in choices) {
                val picked = k in d.kinds
                FilterChip(
                    selected = picked,
                    onClick = { onChange(d.copy(kinds = toggleKind(d.kinds, k, max))) },
                    enabled = picked || !full,
                    label = { Text(kindLabel(k)) },
                )
            }
        }
        Note(kindsLimitText(max))
        EditField(d.who, "Who", { onChange(d.copy(who = it)) }, singleLine = true)
        EditField(d.text, "Text", { onChange(d.copy(text = it)) }, minLines = 2, hint = hint)
    }
}

/** A private word and what it meant here. */
@Composable
internal fun WordRow(w: WordDraft, n: Int, hint: String?, onChange: (WordDraft) -> Unit, onRemove: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ItemHeader("Word $n", "Remove private word $n", onRemove)
        EditField(w.term, "Word", { onChange(w.copy(term = it)) }, singleLine = true, hint = hint?.takeIf { w.term.isBlank() })
        EditField(w.meaning, "Meaning", { onChange(w.copy(meaning = it)) }, hint = hint?.takeIf { w.term.isNotBlank() })
    }
}
