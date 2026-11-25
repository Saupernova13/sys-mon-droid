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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

/**
 * The Tags mode's content in one scrolling list: the how-to guide, the type-ahead, the "Tap adds" choice (while
 * picking), the browse lists and the results. Browse is the empty state: it is open until the first tag is
 * picked. The selected tags and the filters live in the controls row above (the Filters sheet holds the rest).
 * The search field is the Search view's own.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagsResults(vm: WhatsAppViewModel, c: ChatColors, modifier: Modifier = Modifier) {
    val s = vm.search
    val t = s.tags
    val reduced = rememberReducedMotion()
    val hideKeyboard = rememberHideKeyboard()
    val nowSec = remember(t.results) { System.currentTimeMillis() / 1000.0 }
    val listState = rememberSearchListState(s, SearchMode.TAGS)
    // Browse is open while nothing is selected; the first pick folds it away so the results are in view.
    var browseOpen by remember(t.selection.isEmpty) { mutableStateOf(t.selection.isEmpty) }
    LaunchedEffect(Unit) { t.start() }
    // The guide goes away for good once the user has picked a tag.
    LaunchedEffect(t.selection.isEmpty) { if (!t.selection.isEmpty) s.showTagsGuide(false) }

    Column(modifier) {
        Box(Modifier.weight(1f)) {
            LazyColumn(
                Modifier.fillMaxSize().hideKeyboardOnScroll(),
                state = listState,
                contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "guide") { TagsGuideCard(s, c) }
                if (t.input.isNotBlank()) item(key = "suggest") { Suggestions(t, c, hideKeyboard) }
                if (browseOpen || t.input.isNotBlank()) {
                    item(key = "tapadds") {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            Text("Tap adds", style = chatMetaStyle(c.meta).copy(fontWeight = FontWeight.SemiBold))
                            TapAddsRow(t, c)
                        }
                    }
                }
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
                                    onShowInChat = { vm.openConversation(r.asConv()) },
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
    }
    t.cardSheet?.let { sheet ->
        val actions = CardSheetActions(
            q = "",
            onClose = t::closeCard,
            onRetry = t::retryCard,
            // The sheet stays open in the state: coming back from the chat shows it again.
            onOpenChat = {
                vm.openChatAt(it.chat, vm.searchChatName(it.chat, it.chatName), it.startTs, range = it.startTs..it.endTs)
            },
            onOpenMoment = { jid, ts, msgId ->
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
 * A result: the card's title (2 lines), chat and range, summary (3 lines) and the tags that matched
 * (highlighted) beside up to three more that add themselves. The header opens the full card and "Show in
 * chat" opens the chat; the chips are siblings of both, never inside a clickable area.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagResultCard(
    vm: WhatsAppViewModel,
    r: WaTagResult,
    nowSec: Double,
    c: ChatColors,
    onOpenCard: () -> Unit,
    onShowInChat: () -> Unit,
    onTag: (WaTag) -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = r.chatName.ifBlank { vm.searchChatName(r.chat, "") }
    val title = r.title.trim().ifBlank { name }
    val summary = r.summary.trim()
    val others = otherTags(r, OTHER_TAGS_ON_CARD)
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.inBg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Open the full card", onClick = onOpenCard)) {
            Avatar(name, r.chat, vm, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(title, style = chatBodyStyle(c.ink).copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    CountBadge(r.nMsgs, c, Modifier.clearAndSetSemantics { contentDescription = messagesLabel(r.nMsgs) })
                }
                Text("$name · ${convRange(r.startTs, r.endTs, nowSec)}", style = chatSecondaryStyle(c.meta), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (summary.isNotEmpty()) Text(summary, style = chatSecondaryStyle(c.ink), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (r.matched.isNotEmpty() || others.isNotEmpty()) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        ShowInChatButton(c, onShowInChat)
    }
}

/** Other tags a result card offers as chips beside the matched ones (the full card has the rest). */
private const val OTHER_TAGS_ON_CARD = 3

/** The list's footer: the next page loads as it scrolls into view; a failed one leaves a button to try again. */
@Composable
private fun LoadMore(t: TagsSearchState, c: ChatColors) {
    LaunchedEffect(t.results.size) { t.loadMore() }
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        if (t.loadingMore) CircularProgressIndicator(Modifier.size(24.dp), color = c.accent, strokeWidth = 2.dp)
        else TextButton(onClick = t::loadMore, modifier = Modifier.heightIn(min = 48.dp)) { Text("Load more", color = c.accent) }
    }
}
