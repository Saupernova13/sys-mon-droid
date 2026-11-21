package com.raavivi.sysmon.ui.whatsapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raavivi.sysmon.core.model.WaConvLine
import com.raavivi.sysmon.core.model.WaConvResult

/** The note over results that came back word-shaped only (no embedder answered). */
private const val DEGRADED_NOTE = "Word matches only — meaning search is off"

/** What a pick button reads: "Saved" once saved, "Not saved" after a failed save, else its own [base] label. */
fun pickLabel(base: String, key: Int, pick: PickState): String = when {
    pick.failed == key -> "Not saved"
    pick.saved == key -> "Saved"
    else -> base
}

/** The Conversations mode's results: one card per conversation, each with "Found it", then "Not here". */
@Composable
internal fun ConversationResults(vm: WhatsAppViewModel, c: ChatColors, modifier: Modifier = Modifier) {
    val s = vm.search
    val conv = s.conv
    val nowSec = remember(conv.results) { System.currentTimeMillis() / 1000.0 }
    when {
        s.query.isBlank() -> SearchNote("Search for a conversation", c, modifier)
        conv.phase == SearchPhase.IDLE || conv.phase == SearchPhase.LOADING -> SearchNote("Searching…", c, modifier)
        conv.phase == SearchPhase.ERROR -> SearchNote(conv.error ?: SEARCH_FAILED, c, modifier)
        conv.results.isEmpty() -> Column(modifier) {
            SearchNote("No conversations found", c)
            // Nothing matched: "Not here" still says so for the query.
            NotHereBar(conv, c)
        }
        else -> LazyColumn(modifier) {
            if (conv.degraded) item(key = "degraded") { Text(DEGRADED_NOTE, style = chatSecondaryStyle(c.meta), modifier = Modifier.padding(16.dp, 4.dp)) }
            itemsIndexed(conv.results, key = { _, r -> "c:${r.nodeId}:${r.startRowid}:${r.endRowid}" }) { i, r ->
                ConvCard(vm, r, conv.resultsQ, nowSec, c, foundLabel = pickLabel("Found it", i, conv.pick),
                    foundEnabled = conv.pick.busy == null, onFound = { conv.pickFound(i) }, onOpen = { vm.openConversation(r) })
            }
            item(key = "notHere") { NotHereBar(conv, c) }
        }
    }
}

/** A conversation: the topic card's title and summary when it has one, the range, the parent topic, preview lines. */
@Composable
private fun ConvCard(
    vm: WhatsAppViewModel,
    r: WaConvResult,
    q: String,
    nowSec: Double,
    c: ChatColors,
    foundLabel: String,
    foundEnabled: Boolean,
    onFound: () -> Unit,
    onOpen: () -> Unit,
) {
    val name = r.chatName.ifBlank { vm.searchChatName(r.chat, "") }
    val card = r.card?.takeIf { it.title.isNotBlank() }
    val range = convRange(r.startTs, r.endTs, nowSec)
    val mark = c.accent.copy(alpha = 0.35f)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(c.inBg),
    ) {
        // "Found it" is the card's sibling below, never inside the clickable area: no button in a button.
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 12.dp, end = 12.dp, top = 12.dp)) {
            Avatar(name, r.chat, vm, size = 40.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        card?.title ?: name,
                        style = chatSecondaryStyle(c.ink, FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    CountBadge(r.nMsgs, c)
                }
                Text(if (card != null) "$name · $range" else range, style = chatMetaStyle(c.meta))
                r.breadcrumb?.let {
                    Text(convCrumb(it.startTs, it.endTs, it.nMsgs), style = chatMetaStyle(c.meta).copy(fontStyle = FontStyle.Italic))
                }
                card?.summary?.takeIf { it.isNotBlank() }?.let {
                    Text(queryMarked(it, q, mark), style = chatSecondaryStyle(c.ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                for (line in r.preview) PreviewLine(line, q, c, mark)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onFound, enabled = foundEnabled) { Text(foundLabel, color = c.accent) }
        }
    }
}

/** One preview line: mine on the right and tinted like my bubbles, theirs on the left; the query's words marked. */
@Composable
private fun PreviewLine(line: WaConvLine, q: String, c: ChatColors, mark: androidx.compose.ui.graphics.Color) {
    Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = if (line.fromMe) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(
            queryMarked(line.text, q, mark),
            style = chatSecondaryStyle(if (line.fromMe) c.outInk else c.ink),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (line.fromMe) c.outBg else c.wallLift)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** "Not here" under the list (also shown with no results for a non-empty query), with how many queries are marked. */
@Composable
private fun NotHereBar(conv: ConvSearchState, c: ChatColors) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = conv::pickNotHere, enabled = conv.pick.busy == null) {
            Text(pickLabel("Not here", PICK_NOT_HERE, conv.pick), color = c.accent)
        }
        conv.pick.marked?.let { Text("$it marked", style = chatMetaStyle(c.meta)) }
    }
}
