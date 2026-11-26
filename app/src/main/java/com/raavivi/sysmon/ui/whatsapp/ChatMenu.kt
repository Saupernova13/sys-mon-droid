@file:OptIn(ExperimentalFoundationApi::class)

package com.raavivi.sysmon.ui.whatsapp

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Shortcut
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.raavivi.sysmon.core.model.WaChat
import com.raavivi.sysmon.core.model.WaMessage
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private fun tween120() = tween<Float>(120)

/**
 * The message menu (hold for more options), Telegram style: a scrim over the conversation, a lifted
 * clone of the pressed bubble, a reaction bar above it (quick row plus an expandable grid) and the
 * action card below. Delete swaps the card for its inline confirm. Dismissal plays in reverse.
 */
@Composable
fun MessageMenuOverlay(
    row: MsgRow,
    env: ChatEnv,
    danger: Color,
    myEmoji: String,
    onReact: (WaMessage, String) -> Unit,
    onAction: (WaMessage, WaMenuAction) -> Unit,
    onDelete: (WaMessage, Boolean) -> Unit,
    onDismiss: () -> Unit,
    canSelect: Boolean = false,
) {
    val m = row.msg
    val mine = m.fromMe
    val c = env.colors
    val scope = rememberCoroutineScope()
    val lift = remember { Animatable(if (env.reduced) 1f else 0f) }
    LaunchedEffect(Unit) { if (!env.reduced) lift.animateTo(1f, popSpring()) }

    fun close() {
        scope.launch {
            if (!env.reduced) lift.animateTo(0f, tween120())
            onDismiss()
        }
    }
    BackHandler { close() }

    var container by remember { mutableStateOf(Rect.Zero) }
    var gridOpen by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    val bounds = env.rowBounds[m.id] ?: Rect.Zero
    val density = LocalDensity.current

    Box(Modifier.fillMaxSize().onGloballyPositioned { container = it.boundsInRoot() }) {
        // The scrim: a tap outside the bars and the clone plays the close.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 0.45f * lift.value }
                .background(Color.Black)
                .pointerInput(Unit) { detectTapGestures { close() } },
        )

        // The lifted clone at the bubble's rect, scaled toward its sender side.
        Box(
            Modifier
                .offset {
                    IntOffset(
                        (bounds.left - container.left).roundToInt(),
                        (bounds.top - container.top).roundToInt(),
                    )
                }
                .graphicsLayer {
                    scaleX = 1f + 0.03f * lift.value
                    scaleY = 1f + 0.03f * lift.value
                    alpha = lift.value
                    transformOrigin = if (mine) TransformOrigin(1f, 0.5f) else TransformOrigin(0f, 0.5f)
                },
        ) {
            BubbleClone(row, env)
        }

        val actions = remember(m, env.nowSec, canSelect) { waMenuActions(m, env.nowSec, canSelect) }
        val pad8 = with(density) { 8.dp.toPx() }
        val barH = with(density) { 56.dp.toPx() }
        val cardH = with(density) {
            val rows = if (confirming) (if (waCanRevoke(m, env.nowSec)) 3 else 2) else actions.size
            (rows * 48 + 18).dp.toPx()
        }
        val bubbleTop = bounds.top - container.top
        val bubbleBottom = bounds.bottom - container.top
        val barTop = (bubbleTop - barH - pad8).coerceAtLeast(pad8 / 2)
        val cardTop = (bubbleBottom + pad8).coerceAtMost((container.height - cardH - pad8).coerceAtLeast(bubbleTop + barH))
        val align = if (mine) Alignment.TopEnd else Alignment.TopStart

        // Reaction bar: the quick row, or the expandable grid.
        Box(Modifier.align(align).offset { IntOffset(0, barTop.roundToInt()) }.graphicsLayer { alpha = lift.value }) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = lerp(c.inBg, Color.White, 0.05f),
                shadowElevation = 10.dp,
            ) {
                if (gridOpen) {
                    Column(Modifier.padding(6.dp)) {
                        WA_EMOJI_GRID.chunked(8).forEach { chunk ->
                            Row {
                                chunk.forEach { emoji ->
                                    EmojiCell(emoji, c.ink, c.accent, ringed = emoji == myEmoji) {
                                        onReact(m, if (emoji == myEmoji) "" else emoji)
                                        close()
                                    }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.Center) {
                            EmojiCell("\u2715", c.ink, c.accent, ringed = false) { gridOpen = false }
                        }
                    }
                } else {
                    Row(
                        Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WA_QUICK_EMOJI.forEach { emoji ->
                            EmojiCell(emoji, c.ink, c.accent, ringed = emoji == myEmoji) {
                                onReact(m, if (emoji == myEmoji) "" else emoji)
                                close()
                            }
                        }
                        Box(Modifier.width(1.dp).height(28.dp).background(c.hairline))
                        EmojiCell("+", c.ink, c.accent, ringed = false) { gridOpen = true }
                    }
                }
            }
        }

        // Action card, with Delete's inline confirm.
        Box(Modifier.align(align).offset { IntOffset(0, cardTop.roundToInt()) }.graphicsLayer { alpha = lift.value }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = lerp(c.inBg, Color.White, 0.05f),
                shadowElevation = 12.dp,
                modifier = Modifier.widthIn(min = 220.dp),
            ) {
                Column {
                    if (confirming) {
                        if (waCanRevoke(m, env.nowSec)) {
                            MenuRow(Icons.Filled.Delete, "Delete for everyone", danger) { onDelete(m, true); close() }
                        }
                        MenuRow(Icons.Filled.Delete, "Delete for me", danger) { onDelete(m, false); close() }
                        MenuRow(Icons.Filled.Close, "Cancel", c.ink) { confirming = false }
                    } else {
                        for (act in actions) {
                            MenuRow(menuIcon(act), menuLabel(act), if (act == WaMenuAction.DELETE) danger else c.ink) {
                                if (act == WaMenuAction.DELETE) confirming = true
                                else {
                                    onAction(m, act)
                                    if (act != WaMenuAction.INFO && act != WaMenuAction.FORWARD) close()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun menuIcon(act: WaMenuAction): ImageVector = when (act) {
    WaMenuAction.REPLY -> Icons.AutoMirrored.Filled.Reply
    WaMenuAction.COPY -> Icons.Filled.ContentCopy
    WaMenuAction.FORWARD -> Icons.Filled.Shortcut
    WaMenuAction.EDIT -> Icons.Filled.Edit
    WaMenuAction.SAVE -> Icons.Filled.Save
    WaMenuAction.SELECT -> Icons.Filled.CheckCircle
    WaMenuAction.INFO -> Icons.Filled.Info
    WaMenuAction.DELETE -> Icons.Filled.Delete
}

private fun menuLabel(act: WaMenuAction): String = when (act) {
    WaMenuAction.REPLY -> "Reply"
    WaMenuAction.COPY -> "Copy"
    WaMenuAction.FORWARD -> "Forward"
    WaMenuAction.EDIT -> "Edit"
    WaMenuAction.SAVE -> "Save"
    WaMenuAction.SELECT -> "Select messages"
    WaMenuAction.INFO -> "Info"
    WaMenuAction.DELETE -> "Delete"
}

/** One 44dp action row: icon, 12dp gap, 15sp label. */
@Composable
private fun MenuRow(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .heightIn(min = 44.dp)
            .fillMaxWidth()
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = chatSecondaryStyle(tint).copy(fontSize = 15.sp))
    }
}

/** One 40dp tappable emoji cell; [ring] draws an accent circle around my current reaction. */
@Composable
private fun EmojiCell(emoji: String, ink: Color, ring: Color, ringed: Boolean, onTap: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).combinedClickable(onClick = onTap), contentAlignment = Alignment.Center) {
        Text(emoji, style = chatSecondaryStyle(ink).copy(fontSize = if (emoji.length > 2) 18.sp else 20.sp))
        if (ringed) {
            Box(Modifier.matchParentSize().padding(2.dp).border(1.5.dp, ring, CircleShape))
        }
    }
}

/** The chat-row menu (long-press a chat): pins, rename and mark-as-read, hosted by the row's DropdownMenu. */
@Composable
fun ChatRowMenuItems(
    chat: WaChat,
    c: ChatColors,
    onTogglePin: () -> Unit,
    onToggleLocalPin: () -> Unit,
    onRename: () -> Unit,
    onMarkRead: () -> Unit,
) {
    if (chat.localPin) {
        DropdownMenuItem(
            text = { Text("Remove sys-mon pin") },
            leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null, tint = c.accent) },
            onClick = onToggleLocalPin,
        )
    } else {
        DropdownMenuItem(
            text = { Text(if (chat.pinned) "Unpin chat" else "Pin chat") },
            leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null, tint = if (chat.pinned) c.accent else c.meta) },
            onClick = onTogglePin,
        )
        if (!chat.pinned) {
            DropdownMenuItem(
                text = { Text("Pin in sys-mon") },
                leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null, tint = c.meta) },
                onClick = onToggleLocalPin,
            )
        }
    }
    DropdownMenuItem(
        text = { Text("Rename in sys-mon") },
        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null, tint = c.meta) },
        onClick = onRename,
    )
    if (chat.unread > 0) {
        DropdownMenuItem(
            text = { Text("Mark as read") },
            leadingIcon = { Icon(Icons.Filled.DoneAll, contentDescription = null, tint = c.meta) },
            onClick = onMarkRead,
        )
    }
}

/** Forward picker: searchable chat list (phone-digit search included); picking forwards and closes. */
@Composable
fun ForwardSheet(chats: List<WaChat>, c: ChatColors, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val shown = remember(chats, q) { if (q.isBlank()) chats else chats.filter { waChatMatches(it, q) } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = lerp(c.wall, Color.Black, 0.15f),
            modifier = Modifier.fillMaxWidth().heightIn(max = 540.dp),
        ) {
            Column(Modifier.padding(top = 14.dp)) {
                Text(
                    "Forward to",
                    style = chatSecondaryStyle(c.ink, FontWeight.SemiBold).copy(fontSize = 16.sp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                ChatSearchField(q, { q = it }, c, Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                LazyColumn(Modifier.heightIn(max = 430.dp).padding(bottom = 10.dp)) {
                    items(shown, key = { it.jid }) { chat ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .combinedClickable(onClick = { onPick(chat.jid) })
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(chat.name, style = chatSecondaryStyle(c.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** Message info sheet: full sent time, sender, forwarded / edited / starred state. */
@Composable
fun InfoSheet(m: WaMessage, names: Map<String, String>, c: ChatColors, zone: ZoneId = ZoneId.systemDefault(), onDismiss: () -> Unit) {
    val sent = remember(m.ts, zone) {
        Instant.ofEpochSecond(m.ts.toLong()).atZone(zone).format(DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm"))
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = lerp(c.wall, Color.Black, 0.15f), modifier = Modifier.widthIn(max = 420.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text("Message info", style = chatSecondaryStyle(c.ink, FontWeight.SemiBold).copy(fontSize = 16.sp))
                Spacer(Modifier.height(12.dp))
                InfoRow("Sent", sent, c)
                InfoRow("From", if (m.fromMe) "You" else (names[m.sender] ?: m.senderName.ifBlank { m.sender }), c)
                m.forwarded.let {
                    if (it) InfoRow("Forwarded", waForwardLabel(m), c)
                }
                if (m.edited) InfoRow("Edited", "yes", c)
                if (m.starred) InfoRow("Starred", "yes", c)
                m.mediaKind()?.let { InfoRow("Type", it.label, c) }
                if (m.id.isNotBlank()) InfoRow("Id", m.id, c)
                Spacer(Modifier.height(14.dp))
                Text(
                    "Close",
                    style = chatSecondaryStyle(c.accent, FontWeight.SemiBold),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .combinedClickable(onClick = onDismiss)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, c: ChatColors) {
    Row(Modifier.padding(vertical = 5.dp)) {
        Text(label, style = chatSecondaryStyle(c.meta), modifier = Modifier.width(92.dp))
        Text(value, style = chatSecondaryStyle(c.ink), maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}
