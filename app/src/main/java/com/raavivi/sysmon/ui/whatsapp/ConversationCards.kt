package com.raavivi.sysmon.ui.whatsapp

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raavivi.sysmon.core.model.WaConvLine
import com.raavivi.sysmon.core.model.WaConvResult
import com.raavivi.sysmon.core.model.WaYourCard

/** What a pick button reads: "Saved" once saved, "Not saved" after a failed save, else its own [base] label. */
fun pickLabel(base: String, key: Int, pick: PickState): String = when {
    pick.failed == key -> "Not saved"
    pick.saved == key -> "Saved"
    else -> base
}

/**
 * The Conversations mode's results: one card per conversation (tap for its full card; "Show in chat" and "Found
 * it" at its bottom), and "Not here" with its "N marked" count in a bar pinned under the list once the query has
 * an answer. The index / topic-card status is the chip above, not part of this list.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConversationResults(vm: WhatsAppViewModel, c: ChatColors, modifier: Modifier = Modifier) {
    val s = vm.search
    val conv = s.conv
    val nowSec = remember(conv.results) { System.currentTimeMillis() / 1000.0 }
    val reduced = rememberReducedMotion()
    val hideKeyboard = rememberHideKeyboard()
    Column(modifier) {
        // Directly above the results: the theme chips (the list below stays while a filtered search runs).
        if (s.query.isNotBlank() && themeRowShown(conv)) ThemeChipsRow(conv, c)
        Box(Modifier.weight(1f)) {
            when {
                s.query.isBlank() -> SearchIdle(SearchMode.CONVERSATIONS, s.recent, s::openFor, s::clearRecent, c, onGuide = s::openGuide)
                conv.phase == SearchPhase.IDLE || conv.phase == SearchPhase.LOADING ->
                    ScrollingState { SearchSkeleton(card = true, c = c, reduced = reduced) }
                conv.phase == SearchPhase.ERROR -> {
                    val text = conv.error ?: SEARCH_FAILED
                    ScrollingState { SearchError(text, c, onRetry = if (isRetryableSearchError(text)) s::submit else null) }
                }
                conv.results.isEmpty() && conv.yourCards.isEmpty() && conv.selectedThemes.isNotEmpty() -> ScrollingState {
                    // The next step, not a dead end: with a filter on, an empty answer offers to take it off.
                    SearchEmpty("No conversations with these themes", "", c) {
                        TextButton(onClick = conv::clearThemes, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Clear themes", style = chatSecondaryStyle(c.accent, FontWeight.SemiBold))
                        }
                    }
                }
                conv.results.isEmpty() && conv.yourCards.isEmpty() -> ScrollingState { SearchEmpty("No conversations found", "Try describing it differently", c) }
                else -> ConvList(vm, nowSec, c, reduced, hideKeyboard)
            }
        }
        // Not while the keyboard is up: the bar would float above it and cover results.
        if (conv.phase == SearchPhase.DONE && !WindowInsets.isImeVisible) NotHereBar(conv, c, reduced)
    }
    conv.cardSheet?.let { sheet ->
        val actions = CardSheetActions(
            q = conv.resultsQ,
            onClose = conv::closeCard,
            onRetry = conv::retryCard,
            // The sheet stays open in the state: coming back from the chat shows it again.
            onOpenChat = { vm.openConversation(it) },
            onOpenMoment = { jid, ts, msgId ->
                s.noteQuery(s.query)
                vm.openChatAt(jid, vm.searchChatName(jid, conv.results.getOrNull(sheet.index)?.chatName.orEmpty()), ts, focusId = msgId)
            },
            onSearchFor = {
                conv.closeCard()
                s.openFor(it)
            },
            found = FoundAction(pickLabel("Found it", sheet.index, conv.pick), conv.pick.busy == null) { conv.pickFound(sheet.index) },
        )
        ConvCardSheet(vm, sheet, conv.results.getOrNull(sheet.index), actions, c, reduced, nowSec)
    }
}

/** The result cards, 12 dp apart; the scroll position is kept so coming back from a chat lands on the same card. */
@Composable
private fun ConvList(vm: WhatsAppViewModel, nowSec: Double, c: ChatColors, reduced: Boolean, hideKeyboard: () -> Unit) {
    val s = vm.search
    val conv = s.conv
    LazyColumn(
        Modifier.hideKeyboardOnScroll(),
        state = rememberSearchListState(s, SearchMode.CONVERSATIONS),
        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (conv.degraded) item(key = "degraded") { InfoBanner(DEGRADED_NOTE, c, Modifier.padding(horizontal = 16.dp)) }
        // The owner's own cards head the list, above the topic results and not part of them.
        if (conv.yourCards.isNotEmpty()) {
            item(key = "yours-head") {
                Text(
                    "Your cards",
                    style = chatMetaStyle(c.meta).copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
                )
            }
            itemsIndexed(conv.yourCards, key = { _, y -> "y:${y.cardId}" }) { _, y ->
                YourCardItem(
                    vm, y, nowSec, c,
                    onOpenCard = {
                        hideKeyboard()
                        vm.manual.openCard(y.toRef())
                    },
                    onShowInChat = { vm.showYourCard(y.toRef()) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        if (conv.results.isNotEmpty()) item(key = "count") {
            Text(resultsHeader(conv.results.size), style = chatMetaStyle(c.meta), modifier = Modifier.padding(horizontal = 16.dp))
        }
        itemsIndexed(conv.results, key = { _, r -> "c:${r.nodeId}:${r.startRowid}:${r.endRowid}" }) { i, r ->
            ConvCard(
                vm, r, conv.resultsQ, nowSec, c, reduced,
                foundLabel = pickLabel("Found it", i, conv.pick),
                foundEnabled = conv.pick.busy == null,
                onFound = { conv.pickFound(i) },
                onOpenCard = {
                    hideKeyboard()
                    conv.openCard(i)
                },
                onShowInChat = { vm.openConversation(r) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

/**
 * One of the owner's own cards that matched: its title, the "Your card" chip, the summary (3 lines), the chat,
 * range and message count; "Show in chat" and "Full card" under it. 16 dp inside, 8 dp between its parts.
 */
@Composable
private fun YourCardItem(
    vm: WhatsAppViewModel,
    y: WaYourCard,
    nowSec: Double,
    c: ChatColors,
    onOpenCard: () -> Unit,
    onShowInChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = y.chatName.ifBlank { vm.searchChatName(y.chat, "") }
    val title = y.title.trim().ifBlank { name }
    val summary = y.summary.trim().takeIf { it.isNotEmpty() }
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.inBg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Open the full card", onClick = onOpenCard)) {
            Avatar(name, y.chat, vm, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        title,
                        style = chatBodyStyle(c.ink).copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    CountBadge(y.nMsgs, c, Modifier.clearAndSetSemantics { contentDescription = messagesLabel(y.nMsgs) })
                }
                YourCardChip(c)
                Text("$name · ${convRange(y.start.ts, y.end.ts, nowSec)}", style = chatSecondaryStyle(c.meta), maxLines = 1, overflow = TextOverflow.Ellipsis)
                ThemesLine(y.facets, c)
                if (summary != null) Text(summary, style = chatSecondaryStyle(c.ink), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ShowInChatButton(c, onShowInChat)
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = onOpenCard,
                modifier = Modifier.heightIn(min = 48.dp),
                border = BorderStroke(1.dp, c.accent.copy(alpha = 0.5f)),
            ) {
                Text("Full card", style = chatSecondaryStyle(c.accent, FontWeight.SemiBold))
            }
        }
    }
}

/** "N conversations · tap one to see its full card". */
fun resultsHeader(n: Int): String =
    if (n == 1) "1 conversation · tap it to see its full card" else "$n conversations · tap one to see its full card"

/**
 * A conversation: the topic card's title, chat and range, then its summary (3 lines), or without a card the
 * lines that matched. 16 dp inside, 8 dp between its parts.
 */
@Composable
private fun ConvCard(
    vm: WhatsAppViewModel,
    r: WaConvResult,
    q: String,
    nowSec: Double,
    c: ChatColors,
    reduced: Boolean,
    foundLabel: String,
    foundEnabled: Boolean,
    onFound: () -> Unit,
    onOpenCard: () -> Unit,
    onShowInChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = r.chatName.ifBlank { vm.searchChatName(r.chat, "") }
    val card = r.card?.takeIf { it.title.isNotBlank() }
    val summary = card?.summary?.takeIf { it.isNotBlank() }
    val range = convRange(r.startTs, r.endTs, nowSec)
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.inBg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The buttons are the card's siblings below, never inside the clickable area: no button in a button.
        Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Open the full card", onClick = onOpenCard)) {
            Avatar(name, r.chat, vm, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        card?.title ?: name,
                        style = chatBodyStyle(c.ink).copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    CountBadge(r.nMsgs, c, Modifier.clearAndSetSemantics { contentDescription = messagesLabel(r.nMsgs) })
                }
                Text(if (card != null) "$name · $range" else range, style = chatSecondaryStyle(c.meta), maxLines = 1, overflow = TextOverflow.Ellipsis)
                ThemesLine(card?.facets.orEmpty(), c)
                r.breadcrumb?.let {
                    Text(convCrumb(it.startTs, it.endTs, it.nMsgs), style = chatMetaStyle(c.meta).copy(fontStyle = FontStyle.Italic))
                }
                if (summary != null) {
                    Text(queryMarked(summary, q, c.markBg()), style = chatSecondaryStyle(c.ink), maxLines = 3, overflow = TextOverflow.Ellipsis)
                } else {
                    for (line in r.preview.take(2)) PreviewLine(line, q, c)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ShowInChatButton(c, onShowInChat)
            Spacer(Modifier.weight(1f))
            PickButton(foundLabel, "Found it", foundEnabled, c, reduced, onFound)
        }
    }
}

/** A result's own themes (up to 3) as one muted line under its time range; nothing when it has none. */
@Composable
private fun ThemesLine(facets: List<String>, c: ChatColors) {
    val line = themesLine(facets) ?: return
    Text(line, style = chatSecondaryStyle(c.meta), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** A result's "Show in chat": opens the chat at that point; "Back to results" there returns to the Search view. */
@Composable
internal fun ShowInChatButton(c: ChatColors, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, tint = c.accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text("Show in chat", style = chatSecondaryStyle(c.accent, FontWeight.SemiBold))
    }
}

/** One preview line as a tiny bubble: mine on the right and tinted like my bubbles, theirs on the left; the query's words marked. */
@Composable
internal fun PreviewLine(line: WaConvLine, q: String, c: ChatColors) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (line.fromMe) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(
            queryMarked(line.text, q, c.markBg()),
            style = chatSecondaryStyle(if (line.fromMe) c.outInk else c.ink),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (line.fromMe) c.outBg else c.wallLift)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * "Not here" and how many queries are marked, pinned under the list (thumb zone), shown once the query has an
 * answer (also when that answer is no results).
 */
@Composable
private fun NotHereBar(conv: ConvSearchState, c: ChatColors, reduced: Boolean) {
    Row(
        Modifier.fillMaxWidth().background(c.inBg).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("None of these?", style = chatSecondaryStyle(c.meta))
        Spacer(Modifier.width(12.dp))
        PickButton(pickLabel("Not here", PICK_NOT_HERE, conv.pick), "Not here", conv.pick.busy == null, c, reduced, conv::pickNotHere)
        Spacer(Modifier.weight(1f))
        conv.pick.marked?.let { Text("$it marked", style = chatMetaStyle(c.meta)) }
    }
}

/**
 * An outlined 48 dp button that morphs to a check and "Saved" when its verdict is saved: a short scale and
 * fade (none under reduced motion) and a light haptic tick. "Not saved" shows on failure, and the button
 * stays usable.
 */
@Composable
internal fun PickButton(label: String, base: String, enabled: Boolean, c: ChatColors, reduced: Boolean, onClick: () -> Unit) {
    val saved = label == "Saved"
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(saved) { if (saved) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp),
        border = BorderStroke(1.dp, c.accent.copy(alpha = if (saved) 1f else 0.5f)),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(containerColor = if (saved) c.accent.copy(alpha = 0.3f) else Color.Transparent),
    ) {
        AnimatedContent(
            targetState = label,
            transitionSpec = {
                if (reduced) EnterTransition.None togetherWith ExitTransition.None
                else (fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.8f)) togetherWith fadeOut(tween(100))
            },
            label = "pick-$base",
        ) { text ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (text == "Saved") {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = c.ink, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(text, style = chatSecondaryStyle(if (text == "Saved") c.ink else c.accent, FontWeight.SemiBold))
            }
        }
    }
}
