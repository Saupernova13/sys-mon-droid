package com.raavivi.sysmon.ui.whatsapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raavivi.sysmon.core.model.WaSearchRow

/** The Messages mode's results: hits grouped by chat, the first rows of each, "N more", and "More results". */
@Composable
internal fun MessageResults(vm: WhatsAppViewModel, c: ChatColors, modifier: Modifier = Modifier) {
    val s = vm.search
    val m = s.messages
    val groups = remember(m.hits) { groupHits(m.hits) }
    val nowSec = remember(m.hits) { System.currentTimeMillis() / 1000.0 }
    when {
        s.query.isBlank() -> SearchNote("Search across every chat", c, modifier)
        m.phase == SearchPhase.IDLE || m.phase == SearchPhase.LOADING -> SearchNote("Searching…", c, modifier)
        m.phase == SearchPhase.ERROR -> SearchNote(m.error ?: SEARCH_FAILED, c, modifier)
        m.hits.isEmpty() -> SearchNote("No messages found", c, modifier)
        else -> LazyColumn(modifier) {
            for (g in groups) {
                val name = vm.searchChatName(g.chat, g.hits.first().chatName)
                val window = groupWindow(g, g.chat in m.expanded)
                item(key = "g:${g.chat}") {
                    GroupHeader(vm, g, name, c) { vm.openSearchHit(g.hits.first()) }
                }
                items(window.shown, key = { "h:${it.chatJid}:${it.msgId}" }) { hit ->
                    HitRow(hit, nowSec, c) { vm.openSearchHit(hit) }
                }
                if (window.rest > 0) {
                    item(key = "r:${g.chat}") {
                        TextButton(onClick = { m.expand(g.chat) }, modifier = Modifier.padding(start = 8.dp)) {
                            Text("${window.rest} more in this chat", color = c.accent)
                        }
                    }
                }
            }
            if (m.nextOffset != null) {
                item(key = "more") {
                    TextButton(onClick = s::loadMore, enabled = !m.loadingMore, modifier = Modifier.padding(start = 8.dp)) {
                        Text("More results", color = c.accent)
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(vm: WhatsAppViewModel, g: HitGroup, name: String, c: ChatColors, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name, g.chat, vm, size = 32.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            name,
            style = chatSecondaryStyle(c.ink, FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        CountBadge(g.hits.size, c)
    }
}

@Composable
private fun HitRow(hit: WaSearchRow, nowSec: Double, c: ChatColors, onClick: () -> Unit) {
    val snippet = remember(hit.snippet) { parseSnippet(hit.snippet) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 58.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            if (hit.voice) Text("Voice note", style = chatMetaStyle(c.accent).copy(fontWeight = FontWeight.SemiBold))
            Text(
                snippet.toAnnotated(c.accent.copy(alpha = 0.35f), c.ink),
                style = chatBodyStyle(c.ink),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(waChatTime(hit.ts.toDouble(), nowSec), style = chatMetaStyle(c.meta))
    }
}
