package com.raavivi.sysmon.ui.chatindex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// The card editor's content as lazy items: a card can hold hundreds of lines, so nothing here is one big
// Column. Title, Summary and Key moments are open; every other section is a collapsible header whose items
// only exist while it is open. Card text is private: nothing here logs.

/** Every item of the editor, top to bottom. */
internal fun LazyListScope.editorItems(e: CardEditLogic) {
    titleAndSummary(e, MAIN_ID, "m", indent = 0)
    momentsSection(e)
    lineSection(e, Loc.main(ListKey.SAID), "m:said", "Said", "said line")
    lineSection(e, Loc.main(ListKey.MEANT), "m:meant", "Meant", "meant line")
    recallSection(e)
    chipSection(e, Loc.main(ListKey.TOPICS), "m:topics", "Topics", "topic")
    chipSection(e, Loc.main(ListKey.ENTITIES), "m:entities", "Entities", "entity")
    actsSection(e, MAIN_ID, "m")
    facetsSection(e, MAIN_ID, "m")
    detailsSection(e, MAIN_ID, "m")
    wordsSection(e)
    subsSection(e)
}

/** A section: its header, then [body] (always, or only while open when [collapsible]). */
private fun LazyListScope.section(
    e: CardEditLogic,
    key: String,
    title: String,
    count: Int?,
    preview: String,
    collapsible: Boolean,
    body: LazyListScope.() -> Unit,
) {
    val open = !collapsible || key in e.expanded
    item(key = "h:$key") {
        SectionHeader(title, count, collapsed = if (collapsible) !open else null, preview = preview, onToggle = { e.toggleSection(key) })
    }
    if (open) body()
}

private fun LazyListScope.titleAndSummary(e: CardEditLogic, part: Long, key: String, indent: Int) {
    val p = if (part == MAIN_ID) e.draft.main else e.draft.subs.firstOrNull { it.id == part } ?: return
    item(key = "title:$key") {
        EditField(
            p.title, "Title", { v -> e.edit { setTitle(part, v) } },
            singleLine = true, hint = e.hints.titles[part], modifier = Modifier.padding(start = indent.dp),
        )
    }
    item(key = "summary:$key") {
        EditField(
            p.summary, "Summary", { v -> e.edit { setSummary(part, v) } },
            minLines = 3, hint = e.hints.summaries[part], modifier = Modifier.padding(start = indent.dp),
        )
    }
}

private fun LazyListScope.momentsSection(e: CardEditLogic) {
    val moments = e.draft.moments
    if (moments.isEmpty()) return
    section(e, "moments", "Key moments", moments.size, "", collapsible = false) {
        itemsIndexed(moments, key = { _, m -> "moment:${m.index}" }) { i, m ->
            MomentRow(m, i + 1) { e.edit { toggleMoment(m.index) } }
        }
        item(key = "moments:note") { Note("Moments can only be removed.") }
    }
}

/** Said, meant: one text field per line. */
private fun LazyListScope.lineSection(e: CardEditLogic, loc: Loc, key: String, title: String, noun: String) {
    val lines = e.draft.items(loc).filterIsInstance<Line>()
    section(e, key, title, lines.size, previewOf(lines.map { it.text }), collapsible = true) {
        itemsIndexed(lines, key = { _, l -> "$key:${l.id}" }) { i, l ->
            LineRow(
                l.text, "${title} ${i + 1}", "Remove $noun ${i + 1}",
                onChange = { v -> e.edit { updateItem(loc, l.id) { (it as Line).copy(text = v) } } },
                onRemove = { e.remove(loc, l.id) },
            )
        }
        item(key = "$key:add") { AddButton("Add line") { e.edit { addItem(loc) { Line(it, "") } } } }
    }
}

private fun LazyListScope.recallSection(e: CardEditLogic) {
    val loc = Loc.main(ListKey.RECALL)
    val lines = e.draft.recall
    val range = e.options.recallQueries
    section(e, "m:recall", "Recall queries", lines.size, previewOf(lines.map { it.text }), collapsible = true) {
        item(key = "m:recall:note") {
            Note(e.hints.recall ?: "${range.min} to ${range.max}", error = e.hints.recall != null)
        }
        itemsIndexed(lines, key = { _, l -> "m:recall:${l.id}" }) { i, l ->
            LineRow(
                l.text, "Query ${i + 1}", "Remove recall query ${i + 1}",
                onChange = { v -> e.edit { updateItem(loc, l.id) { (it as Line).copy(text = v) } } },
                onRemove = { e.remove(loc, l.id) },
            )
        }
        item(key = "m:recall:add") { AddButton("Add line") { e.edit { addItem(loc) { Line(it, "") } } } }
    }
}

/** Topics, entities: input chips with an Add chip. */
private fun LazyListScope.chipSection(e: CardEditLogic, loc: Loc, key: String, title: String, noun: String) {
    val chips = e.draft.items(loc).filterIsInstance<Line>()
    section(e, key, title, chips.size, previewOf(chips.map { it.text }), collapsible = true) {
        item(key = "$key:chips") {
            ChipEditor(
                chips, noun,
                onAdd = { text -> e.edit { addItem(loc) { Line(it, text) } } },
                onRemove = { e.remove(loc, it.id) },
            )
        }
    }
}

private fun LazyListScope.actsSection(e: CardEditLogic, part: Long, key: String) {
    val loc = Loc(part, ListKey.ACTS)
    val acts = e.draft.items(loc).filterIsInstance<ActDraft>()
    section(e, "$key:acts", "Acts", acts.size, previewOf(acts.map { it.kind }), collapsible = true) {
        itemsIndexed(acts, key = { _, a -> "$key:acts:${a.id}" }) { i, a ->
            ActRow(a, i + 1, onChange = { n -> e.edit { updateItem(loc, a.id) { n } } }, onRemove = { e.remove(loc, a.id) })
        }
        item(key = "$key:acts:add") { AddButton("Add act") { e.edit { addItem(loc) { ActDraft(it, "", 3, false) } } } }
    }
}

private fun LazyListScope.facetsSection(e: CardEditLogic, part: Long, key: String) {
    val loc = Loc(part, ListKey.FACETS)
    val facets = e.draft.items(loc).filterIsInstance<FacetDraft>()
    section(e, "$key:facets", "Facets", facets.size, previewOf(facets.map { it.name }), collapsible = true) {
        itemsIndexed(facets, key = { _, f -> "$key:facets:${f.id}" }) { i, f ->
            FacetRow(
                f, i + 1, facetChoices(e.options, e.draft), e.hints.items[f.id],
                onChange = { n -> e.edit { updateItem(loc, f.id) { n } } },
                onRemove = { e.remove(loc, f.id) },
            )
        }
        item(key = "$key:facets:add") { AddButton("Add facet") { e.edit { addItem(loc) { FacetDraft(it, "", 0.5) } } } }
    }
}

private fun LazyListScope.detailsSection(e: CardEditLogic, part: Long, key: String) {
    val loc = Loc(part, ListKey.DETAILS)
    val details = e.draft.items(loc).filterIsInstance<DetailDraft>()
    section(e, "$key:details", "Details", details.size, previewOf(details.map { it.text }), collapsible = true) {
        itemsIndexed(details, key = { _, d -> "$key:details:${d.id}" }) { i, d ->
            DetailRow(
                d, i + 1, kindChoices(e.options, e.draft), e.options.detailKindsMax, e.hints.items[d.id],
                onChange = { n -> e.edit { updateItem(loc, d.id) { n } } },
                onRemove = { e.remove(loc, d.id) },
            )
        }
        item(key = "$key:details:add") { AddButton("Add detail") { e.edit { addItem(loc) { DetailDraft(it, emptyList(), "", "") } } } }
    }
}

private fun LazyListScope.wordsSection(e: CardEditLogic) {
    val loc = Loc.main(ListKey.WORDS)
    val words = e.draft.words
    section(e, "m:words", "Private words", words.size, previewOf(words.map { it.term }), collapsible = true) {
        itemsIndexed(words, key = { _, w -> "m:words:${w.id}" }) { i, w ->
            WordRow(
                w, i + 1, e.hints.items[w.id],
                onChange = { n -> e.edit { updateItem(loc, w.id) { n } } },
                onRemove = { e.remove(loc, w.id) },
            )
        }
        item(key = "m:words:add") { AddButton("Add private word") { e.edit { addItem(loc) { WordDraft(it, "", "") } } } }
    }
}

/** Sub-topic cards: each a collapsible card with its own fields; Remove sub-topic is in its header menu. */
private fun LazyListScope.subsSection(e: CardEditLogic) {
    val loc = Loc.main(ListKey.SUBS)
    val subs = e.draft.subs
    section(e, "m:subs", "Sub-topic cards", subs.size, "", collapsible = false) {
        subs.forEachIndexed { i, s ->
            val key = "s${s.id}"
            val open = key in e.expanded
            item(key = "$key:head") {
                SubHeader(
                    title = "Sub-topic ${i + 1}" + if (s.title.isNotBlank()) " · ${s.title}" else "",
                    open = open,
                    removeLabel = "Remove sub-topic ${i + 1}",
                    onToggle = { e.toggleSection(key) },
                    onRemove = { e.remove(loc, s.id) },
                )
            }
            if (open) {
                titleAndSummary(e, s.id, key, indent = 12)
                lineSection(e, Loc(s.id, ListKey.SAID), "$key:said", "Said", "said line")
                lineSection(e, Loc(s.id, ListKey.MEANT), "$key:meant", "Meant", "meant line")
                actsSection(e, s.id, key)
                facetsSection(e, s.id, key)
                detailsSection(e, s.id, key)
            }
        }
        item(key = "m:subs:add") { AddButton("Add sub-topic") { e.edit { addSub() } } }
    }
}

@androidx.compose.runtime.Composable
private fun SubHeader(title: String, open: Boolean, removeLabel: String, onToggle: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    RowCard {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onToggle,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    .semantics { contentDescription = "$title, ${if (open) "expanded" else "collapsed"}" },
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1)
                Text(if (open) "Hide" else "Show", style = MaterialTheme.typography.bodySmall)
            }
            Column {
                IconButton(onClick = { menu = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More for $title")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Remove sub-topic") },
                        onClick = { menu = false; onRemove() },
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = removeLabel },
                    )
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun AddButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
}
