package com.raavivi.sysmon.ui.whatsapp

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The note over results that came back word-shaped only (no embedder answered). */
internal const val DEGRADED_NOTE = "Word matches only — meaning search is off"

/** A centred icon, a value-first line and a hint: the shape of every empty and guidance state. */
@Composable
private fun CenteredNote(icon: ImageVector, title: String, hint: String, c: ChatColors, tint: androidx.compose.ui.graphics.Color = c.meta, modifier: Modifier = Modifier, extra: @Composable () -> Unit = {}) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(32.dp))
        Text(title, style = chatBodyStyle(c.ink).copy(fontWeight = FontWeight.SemiBold), textAlign = TextAlign.Center)
        if (hint.isNotEmpty()) Text(hint, style = chatSecondaryStyle(c.meta), textAlign = TextAlign.Center)
        extra()
    }
}

/** No results: what was missing and what to try. */
@Composable
internal fun SearchEmpty(title: String, hint: String, c: ChatColors, modifier: Modifier = Modifier, extra: @Composable () -> Unit = {}) =
    CenteredNote(Icons.Filled.SearchOff, title, hint, c, modifier = modifier, extra = extra)

/** A failed search: the message and, unless the server rejected the query itself, a Retry. */
@Composable
internal fun SearchError(text: String, c: ChatColors, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) =
    CenteredNote(Icons.Filled.ErrorOutline, text, "", c, tint = MaterialTheme.colorScheme.error, modifier = modifier) {
        if (onRetry != null) {
            OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry", color = c.accent) }
        }
    }

/** A compact info banner at the top of results (not an error). */
@Composable
internal fun InfoBanner(text: String, c: ChatColors, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.inBg).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = c.meta, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = chatSecondaryStyle(c.ink))
    }
}

/** Three tonal placeholder blocks while a query is in flight; they pulse only when reduced motion is off. */
@Composable
internal fun SearchSkeleton(card: Boolean, c: ChatColors, reduced: Boolean, modifier: Modifier = Modifier, horizontal: Dp = 16.dp) {
    val pulse = if (reduced) 0.6f else {
        val t = rememberInfiniteTransition(label = "skeleton")
        val a by t.animateFloat(0.4f, 0.8f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "alpha")
        a
    }
    Column(modifier.fillMaxWidth().padding(horizontal = horizontal, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) {
            Box(
                Modifier.fillMaxWidth().height(if (card) 96.dp else 48.dp).alpha(pulse)
                    .clip(RoundedCornerShape(if (card) 16.dp else 12.dp)).background(c.inBg),
            )
        }
    }
}

/**
 * The view before anything is typed, never blank: what this mode does, this session's recent queries (tap to
 * run again, Clear to forget), example queries that run when tapped (Conversations) and a link to the full
 * search guide. Recent queries are in memory only.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchIdle(
    mode: SearchMode,
    recent: List<String>,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
    c: ChatColors,
    modifier: Modifier = Modifier,
    onGuide: () -> Unit,
) {
    Column(modifier.fillMaxSize().hideKeyboardOnScroll().verticalScroll(rememberScrollState())) {
        if (mode == SearchMode.MESSAGES) {
            CenteredNote(Icons.Filled.Search, "Search words across every chat", "Use quotes for a phrase", c)
        } else {
            CenteredNote(Icons.Filled.Search, "Describe the conversation you remember", "Search finds it by meaning, not exact words", c)
        }
        if (recent.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Recent", style = chatSecondaryStyle(c.meta), modifier = Modifier.weight(1f))
                TextButton(onClick = onClear, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear", color = c.accent) }
            }
            for (q in recent) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onPick(q) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.History, contentDescription = null, tint = c.meta, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(q, style = chatBodyStyle(c.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (mode == SearchMode.CONVERSATIONS) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Try", style = chatMetaStyle(c.meta).copy(fontWeight = FontWeight.SemiBold))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (q in ConversationExamples) SearchPill(q, on = false, c = c, onClick = { onPick(q) })
                }
            }
        }
        TextButton(onClick = onGuide, modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp)) {
            Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = null, tint = c.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("How search works", style = chatSecondaryStyle(c.accent, FontWeight.SemiBold))
        }
    }
}

/** A scrollable column for the states that are not a list (empty, error, loading). */
@Composable
internal fun ScrollingState(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxSize().hideKeyboardOnScroll().verticalScroll(rememberScrollState())) {
        content()
    }
}

/**
 * A result list's scroll state for [mode]: it starts where the list was left (coming back from a chat) and
 * keeps the search state's memory up to date while it is on screen. A new query clears the memory, so a fresh
 * list starts at the top.
 */
@Composable
internal fun rememberSearchListState(s: WhatsAppSearch, mode: SearchMode): LazyListState {
    val start = remember { s.scroll.restore(mode) }
    val state = rememberLazyListState(start.index, start.offset)
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> s.scroll.save(mode, index, offset) }
    }
    return state
}
