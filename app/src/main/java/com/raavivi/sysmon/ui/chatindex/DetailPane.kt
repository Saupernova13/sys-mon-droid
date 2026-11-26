package com.raavivi.sysmon.ui.chatindex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.raavivi.sysmon.core.model.ChatOpenRequest
import com.raavivi.sysmon.core.model.CiNode
import com.raavivi.sysmon.ui.common.Chevron
import com.raavivi.sysmon.ui.common.SettingsRow

/**
 * One node or card in full: its facts, the way down to its children, its card, and its messages (paged). A card
 * the owner asked for has Delete card in a bar at the bottom, in reach of the thumb. [chatLabel] names the chat
 * for "Open in chat". Card and message text is private and never logged.
 */
@Composable
internal fun DetailPane(
    d: DetailState,
    chatLabel: String,
    onDrill: (CiNode) -> Unit,
    onOpenChat: (ChatOpenRequest) -> Unit,
) {
    val node = d.node
    val card = d.card.value
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (node != null) {
                        CiHeading(rangeText(node.start, node.end))
                        Text(nodeFacts(node), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (node != null && node.nChildren > 0) {
                item {
                    RowCard {
                        SettingsRow(
                            title = "${childrenName(node.level).replaceFirstChar { it.uppercase() }} (${node.nChildren})",
                            subtitle = "Open the ${childrenName(node.level)} inside it",
                            onClick = { onDrill(node) },
                            trailing = { Chevron() },
                        )
                    }
                }
            }
            if (d.cardId != null) {
                item {
                    when (d.card.phase) {
                        LoadPhase.IDLE, LoadPhase.LOADING -> CiLoading()
                        LoadPhase.MISSING -> CiStateBlock(
                            "This card is no longer there",
                            "It may have been deleted. Go back to the list.",
                        )
                        LoadPhase.ERROR -> CiStateBlock(
                            "Could not load the card",
                            d.card.error,
                            actionLabel = "Retry",
                            onAction = d::loadCard,
                            isError = true,
                        )
                        LoadPhase.READY -> if (card != null) CardView(card, chatLabel, onOpenChat)
                    }
                }
            }
            if (d.messagesNode != null) {
                item { CiHeading("Messages") }
                messageItems(d)
            }
        }
        if (d.canDelete) DeleteBar(d)
    }
    if (d.confirming) {
        AlertDialog(
            onDismissRequest = d::keepCard,
            title = { Text("Delete this card?") },
            text = { Text("This cannot be undone. The messages stay in the chat.") },
            confirmButton = {
                TextButton(onClick = d::confirmDelete, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = d::keepCard, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep") } },
        )
    }
}

/** The node's messages: a loading spinner, the rows (sender, time, text, a [photo] tag for media), or why there are none. */
private fun androidx.compose.foundation.lazy.LazyListScope.messageItems(d: DetailState) {
    val list = d.messages
    when (list.phase) {
        LoadPhase.IDLE, LoadPhase.LOADING -> item { CiLoading(Modifier.padding(vertical = 16.dp)) }
        LoadPhase.ERROR, LoadPhase.MISSING -> item {
            CiStateBlock("Could not load the messages", list.error, actionLabel = "Retry", onAction = d::reloadMessages, isError = true)
        }
        LoadPhase.READY -> {
            if (list.items.isEmpty()) {
                item { CiStateBlock("No messages in this range", "They may have been deleted.") }
            } else {
                items(list.items, key = { it.rowid }) { m ->
                    val v = messageView(m)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "${v.sender} · ${v.time}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val body = listOf(v.media, v.text).filter { it.isNotBlank() }.joinToString(" ")
                        if (body.isNotBlank()) Text(body, style = MaterialTheme.typography.bodyLarge)
                        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                item {
                    LoadMoreRow(
                        visible = list.next != null,
                        loading = list.loadingMore,
                        failed = list.moreFailed,
                        label = "More messages",
                        onClick = list::loadMore,
                    )
                }
            }
        }
    }
}

/** Delete card: a bar at the bottom (the thumb zone) with the reason when a delete failed. */
@Composable
private fun DeleteBar(d: DetailState) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            d.deleteError?.let { CiLiveLine(it) }
            TextButton(
                onClick = d::askDelete,
                enabled = !d.deleting,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                if (d.deleting) CircularProgressIndicator(Modifier.padding(4.dp).heightIn(max = 20.dp), strokeWidth = 2.dp)
                else Text("Delete card", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
