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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.OpenInFull
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raavivi.sysmon.core.model.WaConvLine
import com.raavivi.sysmon.core.model.WaConvResult

/** What a pick button reads: "Saved" once saved, "Not saved" after a failed save, else its own [base] label. */
fun pickLabel(base: String, key: Int, pick: PickState): String = when {
    pick.failed == key -> "Not saved"
    pick.saved == key -> "Saved"
    else -> base
}

/**
 * The Conversations mode's results: one card per conversation (tap for its full card; "Found it" at its bottom
 * right), and "Not here" with its "N marked" count in a bar pinned under the list once the query has an answer.
 * The index / topic-card status scrolls with the content, as its first item.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConversationResults(vm: WhatsAppViewModel, c: ChatColors, modifier: Modifier = Modifier) {
    val s = vm.search
    val conv = s.conv
    val nowSec = remember(conv.results) { System.currentTimeMillis() / 1000.0 }
    val reduced = rememberReducedMotion()
    val hideKeyboard = rememberHideKeyboard()
    val status: @Composable () -> Unit = { StatusCard(s, c) }
    Column(modifier) {
        Box(Modifier.weight(1f)) {
            when {
                s.query.isBlank() -> SearchIdle(SearchMode.CONVERSATIONS, s.recent, s::openFor, s::clearRecent, c, header = status)
                conv.phase == SearchPhase.IDLE || conv.phase == SearchPhase.LOADING ->
                    ScrollingState(header = status) { SearchSkeleton(card = true, c = c, reduced = reduced) }
                conv.phase == SearchPhase.ERROR -> {
                    val text = conv.error ?: SEARCH_FAILED
                    ScrollingState(header = status) { SearchError(text, c, onRetry = if (isRetryableSearchError(text)) s::submit else null) }
                }
                conv.results.isEmpty() -> ScrollingState(header = status) { SearchEmpty("No conversations found", "Try describing it differently", c) }
                else -> LazyColumn(
                    Modifier.hideKeyboardOnScroll(),
                    contentPadding = PaddingValues(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "status") { status() }
                    if (conv.degraded) item(key = "degraded") { InfoBanner(DEGRADED_NOTE, c, Modifier.padding(horizontal = 16.dp)) }
                    item(key = "count") {
                        Text(
                            resultsHeader(conv.results.size),
                            style = chatMetaStyle(c.meta),
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
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
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
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
            onOpenChat = {
                conv.closeCard()
                vm.openConversation(it)
            },
            onOpenMoment = { jid, ts, msgId ->
                s.noteQuery(s.query)
                conv.closeCard()
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

/** "N conversations · tap one to see its full card". */
fun resultsHeader(n: Int): String =
    if (n == 1) "1 conversation · tap it to see its full card" else "$n conversations · tap one to see its full card"

/** A conversation: the topic card's title and summary when it has one, the range, the parent topic, preview lines. */
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
    modifier: Modifier = Modifier,
) {
    val name = r.chatName.ifBlank { vm.searchChatName(r.chat, "") }
    val card = r.card?.takeIf { it.title.isNotBlank() }
    val range = convRange(r.startTs, r.endTs, nowSec)
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.inBg)) {
        // The buttons are the card's siblings below, never inside the clickable area: no button in a button.
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpenCard).padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp)) {
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
                Text(if (card != null) "$name · $range" else range, style = chatMetaStyle(c.meta), maxLines = 1, overflow = TextOverflow.Ellipsis)
                r.breadcrumb?.let {
                    Text(convCrumb(it.startTs, it.endTs, it.nMsgs), style = chatMetaStyle(c.meta).copy(fontStyle = FontStyle.Italic))
                }
                card?.summary?.takeIf { it.isNotBlank() }?.let {
                    Text(queryMarked(it, q, c.markBg()), style = chatSecondaryStyle(c.ink), maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                for (line in r.preview.take(3)) PreviewLine(line, q, c)
            }
        }
        // 12 dp start (the button pads 12 itself) = 24 to the text; 20 dp bottom under a 48 dp target reads as 24.
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 24.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onOpenCard, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Filled.OpenInFull, contentDescription = null, tint = c.accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Full card", style = chatSecondaryStyle(c.accent, FontWeight.SemiBold))
            }
            Spacer(Modifier.weight(1f))
            PickButton(foundLabel, "Found it", foundEnabled, c, reduced, onFound)
        }
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
