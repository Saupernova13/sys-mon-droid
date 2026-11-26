package com.raavivi.sysmon.ui.chatindex

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.raavivi.sysmon.core.model.ChatOpenRequest
import com.raavivi.sysmon.core.model.CiCardRow
import com.raavivi.sysmon.core.model.CiFamily
import com.raavivi.sysmon.core.model.CiNode
import com.raavivi.sysmon.ui.common.Chevron
import com.raavivi.sysmon.ui.common.SettingsRow

/**
 * Browse: the chat list, then one chat's sessions or cards, drilling down through topics and sub-topics, then a
 * node's or card's detail. A breadcrumb shows where you are and each crumb is a way back; Back steps up one level.
 */
@Composable
internal fun BrowseTab(b: BrowseState, onOpenChat: (ChatOpenRequest) -> Unit) {
    val nav = b.nav
    Column(Modifier.fillMaxSize()) {
        if (nav.family != null) Breadcrumb(nav, b::goTo)
        val detail = b.detail
        when {
            detail != null -> DetailPane(detail, nav.family?.label.orEmpty(), b::drill, onOpenChat)
            nav.family == null -> ChatList(b)
            else -> ChatBody(b)
        }
    }
}

/** "Chats > Name > Session 12 Mar 2026 ..." as a row of text buttons; the current crumb is plain text. */
@Composable
private fun Breadcrumb(nav: BrowseNav, onGo: (Crumb) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs(nav).forEachIndexed { i, c ->
            if (i > 0) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (c.current) {
                Text(
                    c.label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 14.dp),
                )
            } else {
                TextButton(onClick = { onGo(c) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(c.label, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// -- the chat list ------------------------------------------------------------------------------------

@Composable
private fun ChatList(b: BrowseState) {
    PagedRows(
        list = b.chats,
        key = { it.id },
        emptyTitle = "No chats are indexed yet",
        emptyHint = "Chats appear here once chat search has indexed them.",
        errorTitle = "Could not load the chats",
        loadMoreLabel = "Load more chats",
    ) { f -> ChatRow(f) { b.openChat(f) } }
}

@Composable
private fun ChatRow(f: CiFamily, onClick: () -> Unit) {
    SettingsRow(
        title = familyLabel(f),
        subtitle = familySubtitle(f),
        onClick = onClick,
        trailing = { Chevron() },
    )
}

// -- a chat: sessions | cards, or the children of a node -----------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatBody(b: BrowseState) {
    val nav = b.nav
    val atRoot = nav.trail.isEmpty()
    Column(Modifier.fillMaxSize()) {
        if (atRoot) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                BrowseMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = nav.mode == m,
                        onClick = { b.setMode(m) },
                        shape = SegmentedButtonDefaults.itemShape(i, BrowseMode.entries.size),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(m.label) }
                }
            }
        }
        val cards = b.cardList
        val nodes = b.nodeList
        when {
            nav.mode == BrowseMode.CARDS && cards != null -> PagedRows(
                list = cards,
                key = { it.id },
                emptyTitle = "No cards for this chat yet",
                emptyHint = "Cards are written in the background; you can also ask for your own from the chat.",
                errorTitle = "Could not load the cards",
                loadMoreLabel = "Load more cards",
            ) { c -> CardRowView(c) { b.openCard(c) } }
            nodes != null -> {
                val parentLevel = (nav.list as? BrowseList.Children)?.parentLevel
                val what = if (parentLevel == null) "sessions" else childrenName(parentLevel)
                PagedRows(
                    list = nodes,
                    key = { it.id },
                    emptyTitle = "No $what here",
                    emptyHint = "Nothing was indexed at this level.",
                    errorTitle = "Could not load the $what",
                    loadMoreLabel = "Load more $what",
                ) { n -> NodeRow(n) { b.openNode(n) } }
            }
        }
    }
}

@Composable
private fun NodeRow(n: CiNode, onClick: () -> Unit) {
    SettingsRow(
        title = nodeLabel(n),
        subtitle = nodeSubtitle(n),
        onClick = onClick,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (n.card != null) CiChip("Card")
                VectorDot(n.hasVector)
                Chevron()
            }
        },
    )
}

@Composable
private fun CardRowView(c: CiCardRow, onClick: () -> Unit) {
    SettingsRow(
        title = cardRowLabel(c),
        subtitle = "${rangeText(c.start, c.end)} · ${messagesText(c.nMsgs)}",
        onClick = onClick,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (c.isManual) CiChip("Yours", emphasis = true)
                Chevron()
            }
        },
    )
}

// -- shared list --------------------------------------------------------------------------------------

/** A paged list of rows, each on its own surface, with its loading, empty, error and "load more" states. */
@Composable
private fun <T> PagedRows(
    list: PagedList<T>,
    key: (T) -> Any,
    emptyTitle: String,
    emptyHint: String,
    errorTitle: String,
    loadMoreLabel: String,
    row: @Composable (T) -> Unit,
) {
    when (list.phase) {
        LoadPhase.IDLE, LoadPhase.LOADING -> CiLoading()
        LoadPhase.ERROR, LoadPhase.MISSING -> CiStateBlock(
            title = errorTitle,
            hint = list.error,
            actionLabel = "Retry",
            onAction = list::reload,
            isError = true,
        )
        LoadPhase.READY -> if (list.items.isEmpty()) {
            CiStateBlock(emptyTitle, emptyHint)
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list.items, key = key) { item -> RowCard { row(item) } }
                item {
                    LoadMoreRow(
                        visible = list.next != null,
                        loading = list.loadingMore,
                        failed = list.moreFailed,
                        label = loadMoreLabel,
                        onClick = list::loadMore,
                    )
                }
            }
        }
    }
}

/** A heading row for a section of a detail view (read as a heading by TalkBack). */
@Composable
internal fun CiHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.semantics { heading() },
    )
}
