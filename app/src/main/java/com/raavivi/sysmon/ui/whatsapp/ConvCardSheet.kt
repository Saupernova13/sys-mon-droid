package com.raavivi.sysmon.ui.whatsapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.raavivi.sysmon.core.model.WaCardMoment
import com.raavivi.sysmon.core.model.WaConvResult

/** What the card sheet needs from the screen that opened it, so Conversations and Tags share one sheet. */
internal class CardSheetActions(
    /** The query the result answers (marks its matching lines); blank for a tag result. */
    val q: String,
    val onClose: () -> Unit,
    val onRetry: () -> Unit,
    /** Open the chat at the whole conversation (the primary action). */
    val onOpenChat: (WaConvResult) -> Unit,
    /** Open the chat at one key moment. */
    val onOpenMoment: (jid: String, ts: Long, msgId: String?) -> Unit,
    /** A "you might remember it as" chip: search for it. */
    val onSearchFor: (String) -> Unit,
    /** The "Found it" verdict, or null when the result has none (tag results). */
    val found: FoundAction? = null,
)

/**
 * What the card sheet adds for one of the owner's own cards (#392): the description it was asked for, and the
 * Delete card flow (a confirm, then deleting, or the reason it failed).
 */
internal class MineCard(
    val instruction: String,
    val confirming: Boolean,
    val deleting: Boolean,
    val error: String?,
    val onDelete: () -> Unit,
    val onKeep: () -> Unit,
    val onConfirm: () -> Unit,
)

/** The "Found it" button's label, whether it can be pressed, and what it does. */
internal class FoundAction(val label: String, val enabled: Boolean, val onFound: () -> Unit)

/**
 * The full card of one conversation result in a bottom sheet: everything the topic card says, so the user can
 * tell it is the right conversation before confirming. A scrolling body above a sticky action bar in the
 * thumb zone ("Show in chat" and "Found it"). The result's own title and summary show at once; the rest
 * loads (skeleton), may be missing (404: no card yet) or fail (Retry). Card text is private and never logged.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ConvCardSheet(
    vm: WhatsAppViewModel,
    sheet: CardSheet,
    r: WaConvResult?,
    a: CardSheetActions,
    c: ChatColors,
    reduced: Boolean,
    nowSec: Double,
    mine: MineCard? = null,
) {
    // The results this sheet belongs to are gone: nothing to show.
    if (r == null) {
        LaunchedEffect(Unit) { a.onClose() }
        return
    }
    ModalBottomSheet(
        onDismissRequest = a.onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.wall,
        contentColor = c.ink,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                CardHeader(vm, r, sheet, c, nowSec, mine)
                when (sheet.phase) {
                    CardPhase.LOADING -> SearchSkeleton(card = false, c = c, reduced = reduced, horizontal = 0.dp)
                    CardPhase.MISSING -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (mine != null) {
                            Text("This card is no longer there", style = chatSecondaryStyle(c.ink, FontWeight.SemiBold))
                            Text("It may have been deleted", style = chatSecondaryStyle(c.meta))
                        } else {
                            Text("No topic card yet for this conversation", style = chatSecondaryStyle(c.ink, FontWeight.SemiBold))
                            Text("Cards are written in the background; the matching lines are below", style = chatSecondaryStyle(c.meta))
                        }
                    }
                    CardPhase.ERROR -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Could not load the card", style = chatSecondaryStyle(c.ink, FontWeight.SemiBold))
                        OutlinedButton(onClick = a.onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry", color = c.accent) }
                    }
                    CardPhase.DONE -> CardBody(r, sheet, a, c)
                }
                if (r.preview.isNotEmpty()) {
                    Section("Matching lines", c) {
                        for (line in r.preview.take(3)) PreviewLine(line, a.q, c)
                    }
                }
                Spacer(Modifier.size(8.dp))
            }
            ActionBar(r, a, c, reduced, mine)
        }
    }
    if (mine != null && mine.confirming) DeleteConfirm(mine, c)
}

/** Avatar, chat name, range and count; then the full title, the summary, and which topic a sub-topic result borrows. */
@Composable
private fun CardHeader(vm: WhatsAppViewModel, r: WaConvResult, sheet: CardSheet, c: ChatColors, nowSec: Double, mine: MineCard?) {
    val name = r.chatName.ifBlank { vm.searchChatName(r.chat, "") }
    val detailCard = sheet.detail?.card
    val title = detailCard?.title?.takeIf { it.isNotBlank() } ?: r.card?.title?.takeIf { it.isNotBlank() } ?: name
    val summary = detailCard?.summary?.takeIf { it.isNotBlank() } ?: r.card?.summary?.takeIf { it.isNotBlank() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(name, r.chat, vm, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = chatSecondaryStyle(c.ink, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${convRange(r.startTs, r.endTs, nowSec)} · ${messagesLabel(r.nMsgs)}", style = chatMetaStyle(c.meta))
            }
        }
        if (mine != null) YourCardChip(c, Modifier.align(Alignment.Start))
        Text(title, style = chatBodyStyle(c.ink).copy(fontWeight = FontWeight.SemiBold))
        if (mine != null && mine.instruction.isNotBlank()) {
            Text("You asked: ${mine.instruction}", style = chatSecondaryStyle(c.meta).copy(fontStyle = FontStyle.Italic))
        }
        if (sheet.detail?.ofParent == true) {
            Text("From the wider topic this belongs to", style = chatMetaStyle(c.meta).copy(fontStyle = FontStyle.Italic))
        }
        summary?.let { Text(it, style = chatBodyStyle(c.ink)) }
    }
}

/** Every non-empty section of the loaded card, in [visibleSections] order, 24 dp apart. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardBody(r: WaConvResult, sheet: CardSheet, a: CardSheetActions, c: ChatColors) {
    val d = sheet.detail ?: return
    for (section in visibleSections(d)) when (section) {
        CardSection.MOMENTS -> Section("Key moments", c) {
            for (m in d.moments) {
                MomentRow(m, c) {
                    val jid = m.chatJid ?: return@MomentRow
                    a.onOpenMoment(jid, m.ts ?: return@MomentRow, m.msgId)
                }
            }
        }
        CardSection.SAID -> Section("What was said", c) { for (t in d.card.said) Bullet(t, c) }
        CardSection.MEANT -> Section("What it meant", c) { for (t in d.card.meant) Bullet(t, c) }
        CardSection.DETAILS -> Section("Details", c) {
            for (item in d.card.details) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (item.who.isNotBlank()) Text(kindLabel(item.who), style = chatMetaStyle(c.meta).copy(fontWeight = FontWeight.SemiBold))
                        for (k in item.kinds) KindPill(k, c)
                    }
                    Text(item.text, style = chatSecondaryStyle(c.ink))
                }
            }
        }
        CardSection.ENTITIES -> Section("People, places and things", c) { Chips(d.card.entities, c) }
        CardSection.TOPICS -> Section("Topics", c) { Chips(d.card.topics, c) }
        CardSection.MOOD -> Section("Mood", c) {
            for (act in d.card.acts) {
                val dots = intensityDots(act)
                Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(actText(act), style = chatSecondaryStyle(c.ink), modifier = Modifier.weight(1f))
                    Row(
                        Modifier.semantics { contentDescription = "Intensity $dots of $INTENSITY_DOTS" },
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        repeat(INTENSITY_DOTS) { i ->
                            Box(Modifier.size(8.dp).clip(CircleShape).background(if (i < dots) c.meta else c.hairline))
                        }
                    }
                }
            }
            if (d.card.facets.isNotEmpty()) Chips(sortedFacets(d.card.facets).map { it.name }, c)
        }
        CardSection.SUBTOPICS -> Section("Sub-topics", c) {
            for (sub in d.subtopics) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (sub.title.isNotBlank()) Text(sub.title, style = chatSecondaryStyle(c.ink, FontWeight.SemiBold))
                    if (sub.summary.isNotBlank()) Text(sub.summary, style = chatSecondaryStyle(c.ink))
                }
            }
        }
        CardSection.LEXICON -> Section("Private words", c) {
            for (lex in d.card.lexicon) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(lex.term) }
                        append(" — ")
                        append(lex.meaning)
                    },
                    style = chatSecondaryStyle(c.ink),
                )
            }
        }
        CardSection.RECALL -> Section("You might remember it as", c) {
            Text("Tap one to search for it", style = chatMetaStyle(c.meta))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (q in d.card.recallQueries) {
                    SearchPill(q, on = false, c = c, onClick = { a.onSearchFor(q) })
                }
            }
        }
    }
}

/** A small meta-style heading and its content, 8 dp apart. */
@Composable
private fun Section(title: String, c: ChatColors, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = chatMetaStyle(c.meta).copy(fontWeight = FontWeight.SemiBold))
        content()
    }
}

@Composable
private fun Bullet(text: String, c: ChatColors) {
    Row {
        Text("•", style = chatSecondaryStyle(c.meta))
        Spacer(Modifier.width(8.dp))
        Text(text, style = chatSecondaryStyle(c.ink), modifier = Modifier.weight(1f))
    }
}

/** A tiny kind label (`plan`, `idea`) on a tonal pill. */
@Composable
private fun KindPill(kind: String, c: ChatColors) {
    Text(
        kindLabel(kind),
        style = chatMetaStyle(c.meta),
        modifier = Modifier.clip(CircleShape).background(c.inBg).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Non-interactive chips that wrap onto as many lines as they need. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(items: List<String>, c: ChatColors) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (t in items) {
            Text(
                t,
                style = chatSecondaryStyle(c.ink),
                modifier = Modifier.clip(CircleShape).background(c.inBg).heightIn(min = 32.dp).padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

/** A key moment: its kind and one line; a 48 dp row with a chevron that opens the chat there when it names a message. */
@Composable
private fun MomentRow(m: WaCardMoment, c: ChatColors, onOpen: () -> Unit) {
    val open = momentOpenable(m)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).let { if (open) it.clickable(onClick = onOpen) else it },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (m.kind.isNotBlank()) {
            KindPill(m.kind, c)
            Spacer(Modifier.width(12.dp))
        }
        Text(m.oneLine, style = chatSecondaryStyle(c.ink), modifier = Modifier.weight(1f))
        if (open) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Open the chat at this moment", tint = c.meta, modifier = Modifier.size(24.dp))
        }
    }
}

/** Sticky bottom bar: "Found it" (outlined) and the primary "Show in chat"; a card of the owner's has Delete card instead of Found it. */
@Composable
private fun ActionBar(r: WaConvResult, a: CardSheetActions, c: ChatColors, reduced: Boolean, mine: MineCard?) {
    Column(Modifier.fillMaxWidth().background(c.inBg).navigationBarsPadding()) {
        mine?.error?.let {
            Text(
                it,
                style = chatSecondaryStyle(MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (mine != null) {
                TextButton(onClick = mine.onDelete, enabled = !mine.deleting, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(
                        if (mine.deleting) "Deleting..." else "Delete card",
                        style = chatSecondaryStyle(MaterialTheme.colorScheme.error, FontWeight.SemiBold),
                    )
                }
            } else {
                a.found?.let { PickButton(it.label, "Found it", it.enabled, c, reduced, it.onFound) }
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { a.onOpenChat(r) },
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
            ) {
                Text("Show in chat", style = chatSecondaryStyle(c.onAccent, FontWeight.SemiBold))
            }
        }
    }
}

/** "Delete this card?": the card, its words and its vectors go; the messages themselves are untouched. */
@Composable
private fun DeleteConfirm(mine: MineCard, c: ChatColors) {
    AlertDialog(
        onDismissRequest = mine.onKeep,
        title = { Text("Delete this card?") },
        text = { Text("This cannot be undone. The messages stay in the chat.") },
        confirmButton = {
            TextButton(onClick = mine.onConfirm, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = mine.onKeep, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep", color = c.accent) }
        },
    )
}
