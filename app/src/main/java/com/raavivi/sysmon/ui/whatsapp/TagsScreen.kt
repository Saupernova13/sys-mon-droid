package com.raavivi.sysmon.ui.whatsapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raavivi.sysmon.core.model.WaTag
import com.raavivi.sysmon.core.model.WaTagResult
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The category chips under the mode switch: All first, then the eight categories; they narrow the type-ahead and the browse list. */
@Composable
internal fun TagsCategoryRow(t: TagsSearchState, c: ChatColors) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SearchPill("All", t.cat.isEmpty(), c, { t.chooseCat("") }) }
        items(TAG_CATS.size) { i ->
            val (id, label) = TAG_CATS[i]
            SearchPill(label, t.cat == id, c, { t.chooseCat(id) })
        }
    }
}

/**
 * The Tags mode's content: the how-to guide, the type-ahead, the "Tap adds" choice, the selected tags in their
 * three groups, the browse lists and the results, in one scrolling list; "N conversations" and the Filters
 * button sit in a bar pinned under it (thumb zone). The search field is the Search view's own.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagsResults(vm: WhatsAppViewModel, c: ChatColors, modifier: Modifier = Modifier) {
    val s = vm.search
    val t = s.tags
    val reduced = rememberReducedMotion()
    val hideKeyboard = rememberHideKeyboard()
    val nowSec = remember(t.results) { System.currentTimeMillis() / 1000.0 }
    var filtersOpen by remember { mutableStateOf(false) }
    // Browse is open while nothing is selected; the first pick folds it away so the results are in view.
    var browseOpen by remember(t.selection.isEmpty) { mutableStateOf(t.selection.isEmpty) }
    LaunchedEffect(Unit) { t.start() }
    // The guide goes away for good once the user has picked a tag.
    LaunchedEffect(t.selection.isEmpty) { if (!t.selection.isEmpty) s.showTagsGuide(false) }

    Column(modifier) {
        Box(Modifier.weight(1f)) {
            LazyColumn(
                Modifier.fillMaxSize().hideKeyboardOnScroll(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "guide") { TagsGuideCard(s, c) }
                if (t.input.isNotBlank()) item(key = "suggest") { Suggestions(t, c, hideKeyboard) }
                item(key = "tapadds") { TapAddsRow(t, c) }
                if (!t.selection.isEmpty) item(key = "selected") { SelectedTags(t, c) }
                item(key = "browse-head") { BrowseHeader(browseOpen, c) { browseOpen = !browseOpen } }
                if (browseOpen) {
                    for (cat in browseCats(t.cat)) {
                        val tags = t.facets[cat].orEmpty()
                        if (tags.isNotEmpty()) item(key = "browse:$cat") { BrowseCategory(t, cat, c, hideKeyboard) }
                    }
                    if (t.phase == TagsPhase.DONE && t.facets.values.all { it.isEmpty() }) {
                        item(key = "browse-empty") {
                            Text("No tags to browse yet", style = chatSecondaryStyle(c.meta), modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
                when (t.phase) {
                    TagsPhase.IDLE, TagsPhase.LOADING -> item(key = "loading") { SearchSkeleton(card = true, c = c, reduced = reduced) }
                    TagsPhase.ERROR -> item(key = "error") {
                        val text = t.error ?: SEARCH_FAILED
                        SearchError(text, c, onRetry = if (isRetryableSearchError(text)) t::retry else null)
                    }
                    TagsPhase.OFF -> item(key = "off") {
                        SearchEmpty("Tag search is off", "Chat search is not switched on on the server", c)
                    }
                    TagsPhase.DONE -> {
                        if (t.results.isEmpty()) {
                            item(key = "empty") {
                                if (t.selection.isEmpty && filterCount(t.filters) == 0) {
                                    SearchEmpty("No topic cards yet", "Cards are written in the background; tags appear once they are", c)
                                } else {
                                    SearchEmpty("No conversations match", "Try removing a tag or a filter", c)
                                }
                            }
                        } else {
                            item(key = "count") {
                                Text(tagsTotalText(t.total), style = chatMetaStyle(c.meta), modifier = Modifier.padding(horizontal = 16.dp))
                            }
                            itemsIndexed(t.results, key = { i, r -> "r:$i:${r.cardId}" }) { i, r ->
                                TagResultCard(
                                    vm, r, nowSec, c,
                                    onOpenCard = {
                                        hideKeyboard()
                                        t.openCard(i)
                                    },
                                    onTag = { tag ->
                                        hideKeyboard()
                                        t.add(tag.pick())
                                    },
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }
                            if (t.more || t.loadingMore) item(key = "more") { LoadMore(t, c) }
                        }
                    }
                }
            }
        }
        // Not while the keyboard is up: the bar would float above it and cover results.
        if (!WindowInsets.isImeVisible) FiltersBar(t, c) { filtersOpen = true }
    }
    if (filtersOpen) TagsFiltersSheet(s, c) { filtersOpen = false }
    t.cardSheet?.let { sheet ->
        val actions = CardSheetActions(
            q = "",
            onClose = t::closeCard,
            onRetry = t::retryCard,
            onOpenChat = {
                t.closeCard()
                vm.openChatAt(it.chat, vm.searchChatName(it.chat, it.chatName), it.startTs, range = it.startTs..it.endTs)
            },
            onOpenMoment = { jid, ts, msgId ->
                t.closeCard()
                vm.openChatAt(jid, vm.searchChatName(jid, ""), ts, focusId = msgId)
            },
            onSearchFor = {
                t.closeCard()
                s.openFor(it)
            },
        )
        ConvCardSheet(vm, sheet, t.results.getOrNull(sheet.index)?.asConv(), actions, c, reduced, nowSec)
    }
}

/** "How tag search works": expanded on first use, then folded; the header always opens it again. */
@Composable
private fun TagsGuideCard(s: WhatsAppSearch, c: ChatColors) {
    val open = s.tagsGuideOpen
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)).background(c.inBg)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { s.showTagsGuide(!open) }.padding(start = 16.dp, end = 8.dp)
                .semantics { role = Role.Button; contentDescription = if (open) "Hide how tag search works" else "Show how tag search works" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("How tag search works", style = chatSecondaryStyle(c.ink, FontWeight.SemiBold), modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = c.meta, modifier = Modifier.padding(12.dp).size(24.dp))
        }
        if (open) SearchGuide(SearchMode.TAGS, c, onExample = {}, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp))
    }
}

/** The type-ahead: label, category and count per tag, each a 48 dp row; a tap adds it and puts the keyboard away. */
@Composable
private fun Suggestions(t: TagsSearchState, c: ChatColors, hideKeyboard: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)).background(c.inBg)) {
        if (t.suggestions.isEmpty()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (t.suggestLoading) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = c.accent, strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Looking for tags", style = chatSecondaryStyle(c.meta))
                } else {
                    Text("No matching tags", style = chatSecondaryStyle(c.meta))
                }
            }
        }
        for (tag in t.suggestions) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable {
                        hideKeyboard()
                        t.pickSuggestion(tag)
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(tag.label.ifBlank { tag.value }, style = chatSecondaryStyle(c.ink, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(catLabel(tag.cat), style = chatMetaStyle(c.meta))
                }
                Spacer(Modifier.width(12.dp))
                Text("${tag.count}", style = chatMetaStyle(c.meta))
            }
        }
    }
}

/** "Tap adds: + Must have | ~ Any of | − Exclude": which group the next picked tag goes into (the answer to "how do I exclude"). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TapAddsRow(t: TagsSearchState, c: ChatColors) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text("Tap adds", style = chatMetaStyle(c.meta).copy(fontWeight = FontWeight.SemiBold))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (g in TagGroup.entries) SearchPill("${g.glyph} ${g.label}", t.addGroup == g, c, { t.chooseAddGroup(g) })
        }
    }
}

/** The selected tags: Must have, Any of and Exclude, each chip with its glyph, a tap to cycle its group and an x to drop it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectedTags(t: TagsSearchState, c: ChatColors) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Selected", style = chatBodyStyle(c.ink).copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
            TextButton(onClick = t::clearSelection, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear all", color = c.accent) }
        }
        for (g in TagGroup.entries) {
            val tags = t.selection.of(g)
            if (tags.isEmpty()) continue
            Text("${g.glyph} ${g.label}", style = chatMetaStyle(c.meta).copy(fontWeight = FontWeight.SemiBold))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (tag in tags) SelectedChip(tag, g, c, onCycle = { t.cycle(tag) }, onRemove = { t.remove(tag) })
            }
        }
    }
}

/** The pill tint of a group: decoration only, the glyph says which group it is. */
@Composable
private fun groupTint(g: TagGroup, c: ChatColors): Color = when (g) {
    TagGroup.ALL -> c.accent.copy(alpha = 0.3f)
    TagGroup.ANY -> c.accent.copy(alpha = 0.15f)
    TagGroup.NONE -> MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
}

/** A selected tag: a 48 dp label zone that cycles its group and a 48 dp x zone that removes it, in one 32 dp pill. */
@Composable
private fun SelectedChip(tag: TagPick, g: TagGroup, c: ChatColors, onCycle: () -> Unit, onRemove: () -> Unit) {
    val tint = groupTint(g, c)
    val next = TagGroup.entries[(g.ordinal + 1) % TagGroup.entries.size]
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.heightIn(min = 48.dp)
                .semantics { role = Role.Button; contentDescription = "${g.label}: ${tag.label}. Tap to move to ${next.label}" }
                .clickable(onClick = onCycle),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${g.glyph} ${tag.label}",
                style = chatSecondaryStyle(c.ink, FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 240.dp).clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)).background(tint)
                    .heightIn(min = 32.dp).padding(start = 12.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
            )
        }
        Box(
            Modifier.size(width = 36.dp, height = 48.dp)
                .semantics { role = Role.Button; contentDescription = "Remove ${tag.label}" }
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier.size(width = 32.dp, height = 32.dp).clip(RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)).background(tint),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, contentDescription = null, tint = c.ink, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun BrowseHeader(open: Boolean, c: ChatColors, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onToggle).padding(start = 16.dp, end = 8.dp)
            .semantics { role = Role.Button; contentDescription = if (open) "Hide browse tags" else "Show browse tags" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Browse tags", style = chatBodyStyle(c.ink).copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
        Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = c.meta, modifier = Modifier.padding(12.dp).size(24.dp))
    }
}

/** One category's top tags with their counts (12, then "Show more" up to 30). A tap adds the tag to the "Tap adds" group. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BrowseCategory(t: TagsSearchState, cat: String, c: ChatColors, hideKeyboard: () -> Unit) {
    val w = facetWindow(t.facets[cat].orEmpty(), cat in t.expanded)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(catLabel(cat), style = chatMetaStyle(c.meta).copy(fontWeight = FontWeight.SemiBold))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (f in w.shown) {
                TagPill(f.label.ifBlank { f.value }, f.count, c, onClick = {
                    hideKeyboard()
                    t.add(f.pick(cat))
                })
            }
        }
        if (w.more > 0) {
            TextButton(onClick = { t.expand(cat) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Show ${w.more} more", color = c.accent) }
        }
    }
}

/** A tappable tag: a 48 dp touch target around a 32 dp pill, with its card count when it has one. */
@Composable
internal fun TagPill(label: String, count: Int?, c: ChatColors, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.heightIn(min = 48.dp).semantics { role = Role.Button }.clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.clip(CircleShape).background(c.inBg).heightIn(min = 32.dp).padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = chatSecondaryStyle(c.ink), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
            if (count != null) {
                Spacer(Modifier.width(8.dp))
                Text("$count", style = chatMetaStyle(c.meta))
            }
        }
    }
}

/**
 * A result: the card's title (2 lines) and summary (3), the chat, range and size, the tags that matched
 * (highlighted) and up to six more as chips that add themselves. The header opens the full card; the chips are
 * its siblings, never inside the clickable area.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagResultCard(
    vm: WhatsAppViewModel,
    r: WaTagResult,
    nowSec: Double,
    c: ChatColors,
    onOpenCard: () -> Unit,
    onTag: (WaTag) -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = r.chatName.ifBlank { vm.searchChatName(r.chat, "") }
    val title = r.title.trim().ifBlank { name }
    val summary = r.summary.trim()
    val others = otherTags(r)
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.inBg)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpenCard).padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp)) {
            Avatar(name, r.chat, vm, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = chatBodyStyle(c.ink).copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (summary.isNotEmpty()) Text(summary, style = chatSecondaryStyle(c.ink), maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(
                    "$name · ${convRange(r.startTs, r.endTs, nowSec)} · ${messagesLabel(r.nMsgs)}",
                    style = chatMetaStyle(c.meta),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (r.matched.isNotEmpty() || others.isNotEmpty()) {
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in r.matched) {
                    Text(
                        m.label.ifBlank { m.value },
                        style = chatSecondaryStyle(c.ink, FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 8.dp).widthIn(max = 220.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.3f))
                            .heightIn(min = 32.dp).padding(horizontal = 12.dp, vertical = 5.dp)
                            .semantics { contentDescription = "Matched tag: ${m.label.ifBlank { m.value }}" },
                    )
                }
                for (o in others) TagPill(o.label.ifBlank { o.value }, null, c, onClick = { onTag(o) }, modifier = Modifier.semantics { contentDescription = "Add tag ${o.label.ifBlank { o.value }}" })
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 24.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onOpenCard, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Filled.OpenInFull, contentDescription = null, tint = c.accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Full card", style = chatSecondaryStyle(c.accent, FontWeight.SemiBold))
            }
        }
    }
}

/** The list's footer: the next page loads as it scrolls into view; a failed one leaves a button to try again. */
@Composable
private fun LoadMore(t: TagsSearchState, c: ChatColors) {
    LaunchedEffect(t.results.size) { t.loadMore() }
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        if (t.loadingMore) CircularProgressIndicator(Modifier.size(24.dp), color = c.accent, strokeWidth = 2.dp)
        else TextButton(onClick = t::loadMore, modifier = Modifier.heightIn(min = 48.dp)) { Text("Load more", color = c.accent) }
    }
}

/** "N conversations" and the Filters button (with how many are set), pinned under the list. */
@Composable
private fun FiltersBar(t: TagsSearchState, c: ChatColors, onFilters: () -> Unit) {
    val n = filterCount(t.filters)
    Row(
        Modifier.fillMaxWidth().background(c.inBg).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (t.phase == TagsPhase.DONE) tagsTotalText(t.total) else "",
            style = chatSecondaryStyle(c.meta),
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(
            onClick = onFilters,
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = if (n == 0) "Filters" else "Filters, $n set" },
        ) {
            Icon(Icons.Filled.Tune, contentDescription = null, tint = c.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Filters", style = chatSecondaryStyle(c.accent, FontWeight.SemiBold))
            if (n > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "$n",
                    style = chatMetaStyle(c.onAccent).copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.clip(CircleShape).background(c.accent).widthIn(min = 20.dp).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

private val DayFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

/** The filters in a bottom sheet: chat, dates, minimum intensity, only unsettled and the sort. Each change re-runs the query. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TagsFiltersSheet(s: WhatsAppSearch, c: ChatColors, onDismiss: () -> Unit) {
    val t = s.tags
    val f = t.filters
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.wall,
        contentColor = c.ink,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                FilterSection("Chat", c) {
                    if (t.knownChats.isEmpty()) {
                        Text("Chats show up here once there are results", style = chatSecondaryStyle(c.meta))
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SearchPill("Any chat", f.chat.isEmpty(), c, { t.setChat("") })
                            for ((jid, name) in t.knownChats) SearchPill(name, f.chat == jid, c, { t.setChat(jid) })
                        }
                    }
                }
                FilterSection("Date", c) {
                    DateRow("From", f.since, c, t::setSince)
                    DateRow("To", f.until, c, t::setUntil)
                }
                FilterSection("Minimum intensity", c) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SearchPill("Any", f.minIntensity == 0, c, { t.setMinIntensity(0) })
                        for (n in TAG_INTENSITIES) SearchPill("$n+", f.minIntensity == n, c, { t.setMinIntensity(n) })
                    }
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = f.unresolved, role = Role.Switch, onValueChange = t::setUnresolved),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Only unsettled", style = chatSecondaryStyle(c.ink, FontWeight.SemiBold))
                        Text("Conversations with something left open", style = chatMetaStyle(c.meta))
                    }
                    Switch(checked = f.unresolved, onCheckedChange = null)
                }
                FilterSection("Sort", c) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (sort in TagSort.entries) {
                            // Most matched ranks by the Any of tags: without any it would mean nothing.
                            if (sort == TagSort.MATCHES && t.selection.any.isEmpty()) continue
                            SearchPill(sort.label, effectiveSort(t.selection, f) == sort, c, { t.setSort(sort) })
                        }
                    }
                    if (t.selection.any.isEmpty()) Text("Most matched appears once you add an Any of tag", style = chatMetaStyle(c.meta))
                }
                Spacer(Modifier.size(8.dp))
            }
            Row(
                Modifier.fillMaxWidth().background(c.inBg).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = t::resetFilters, enabled = filterCount(f) > 0, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reset", color = c.accent) }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
                ) { Text("Show results", style = chatSecondaryStyle(c.onAccent, FontWeight.SemiBold)) }
            }
        }
    }
}

@Composable
private fun FilterSection(title: String, c: ChatColors, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = chatMetaStyle(c.meta).copy(fontWeight = FontWeight.SemiBold))
        content()
    }
}

/** A date row: its label, the day (or "Any date") and a clear button; a tap opens the date picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRow(label: String, day: LocalDate?, c: ChatColors, onPick: (LocalDate?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).heightIn(min = 48.dp).clickable { open = true }.semantics { role = Role.Button },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = chatSecondaryStyle(c.ink), modifier = Modifier.width(56.dp))
            Text(
                day?.format(DayFormat) ?: "Any date",
                style = chatSecondaryStyle(if (day == null) c.meta else c.accent, FontWeight.SemiBold),
            )
        }
        if (day != null) {
            IconButton(onClick = { onPick(null) }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Clear the $label date", tint = c.meta)
            }
        }
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = day?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}
