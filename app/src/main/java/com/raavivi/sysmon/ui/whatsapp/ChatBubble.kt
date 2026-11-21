@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.raavivi.sysmon.ui.whatsapp

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import coil.ImageLoader
import com.raavivi.sysmon.core.model.WaMessage
import com.raavivi.sysmon.core.model.WaSearchTerm
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Everything a message row needs from the conversation, so the row composables stay free of the VM. */
@Stable
class ChatEnv(
    val colors: ChatColors,
    val group: Boolean,
    val nowSec: Double,
    val reduced: Boolean,
    val imageLoader: ImageLoader,
    /** (message, thumb): the media URL; thumb asks for the server's <=720px JPEG. */
    val mediaUrl: (WaMessage, Boolean) -> String,
    val byId: Map<String, WaMessage>,
    val names: Map<String, String>,
    val reactions: WaReactions,
    val maxBubble: Dp,
    val voice: (WaMessage) -> VoiceUi,
    val onOpenImage: (WaMessage) -> Unit,
    val onVoiceToggle: (WaMessage) -> Unit,
    val onVoiceSeek: (WaMessage, Float) -> Unit,
    val onVoiceSpeed: () -> Unit,
    val onQuoteTap: (String) -> Unit,
    /** Long-press opens the message menu (the screen lifts a clone at the bubble's live rect). */
    val onLongPress: (WaMessage) -> Unit,
    /** A completed swipe-to-reply (or the menu's Reply action). */
    val onReply: (WaMessage) -> Unit,
    /** React from the reactor popover: an empty emoji removes my reaction. */
    val onReact: (WaMessage, String) -> Unit,
    /** Live bubble bounds by message id, for the menu overlay's lifted clone. */
    val rowBounds: MutableMap<String, Rect>,
    /** Reaction chips pop when they first appear or change; off while the conversation is still opening. */
    val popEnabled: Boolean,
    val reactionSeen: MutableMap<String, String>,
    /** The in-chat search's terms, highlighted inside message text; empty when the bar has no results. */
    val marks: List<WaSearchTerm> = emptyList(),
    /** A tapped conversation's start..end (unix seconds): rows inside it are tinted. */
    val range: LongRange? = null,
)

private fun bubbleBg(c: ChatColors, mine: Boolean) = if (mine) c.outBg else c.inBg

/** A day separator pill (also used for the floating date). */
@Composable
fun DayPill(label: String, c: ChatColors, modifier: Modifier = Modifier) {
    Box(
        modifier
            .shadow(2.dp, CircleShape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(CircleShape)
            .background(c.pill)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, style = chatSecondaryStyle(c.pillInk, FontWeight.SemiBold))
    }
}

@Composable
fun DayRowItem(row: DayRow, env: ChatEnv, label: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
        DayPill(label, env.colors)
    }
}

/** Time chip for bubble-free content and photos without a caption. */
@Composable
fun TimeChip(m: WaMessage, c: ChatColors, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(CircleShape)
            .background(c.pill)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { MetaContent(m.ts, m.edited, m.starred, c.pillInk.copy(alpha = 0.8f)) }
}

/**
 * One message in the list: spacing, sender-side alignment, entrance for live items, the bubble (or the
 * bubble-free sticker / jumbo emoji) and its reaction chip. Long-press opens the message menu; a
 * rightward drag resists past 40dp and replies when released past 56dp.
 */
@Composable
fun MessageItem(row: MsgRow, env: ChatEnv, fresh: Boolean, flash: Boolean, modifier: Modifier = Modifier) {
    val m = row.msg
    val mine = m.fromMe
    val c = env.colors
    val kind = m.mediaKind()
    val revoked = m.revoked
    val sum = remember(env.reactions[m.id], revoked) {
        if (revoked) ReactionSummary.Empty else waSummarizeReactions(env.reactions[m.id])
    }
    val jumbo = remember(m.text, m.replyToId, m.forwarded, kind, revoked) {
        if (kind == null && !revoked && m.replyToId.isBlank() && m.replyToText.isBlank() && !m.forwarded) waEmojiOnlyCount(m.text) else 0
    }
    val sticker = kind == WaMediaKind.STICKER && !revoked
    val bubbleFree = sticker || jumbo > 0

    val inRange = env.range?.let { m.ts.toLong() in it } == true
    Box(
        modifier
            .fillMaxWidth()
            .then(if (inRange) Modifier.background(c.accent.copy(alpha = 0.12f)) else Modifier)
            .padding(top = if (row.groupFirst) 10.dp else 2.dp)
            .padding(horizontal = 10.dp),
        contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .entrance(fresh, mine, env.reduced)
                .padding(bottom = if (sum.total > 0) 12.dp else 0.dp),
        ) {
            val side = if (mine) Alignment.TopEnd else Alignment.TopStart
            // The swipe follows the finger: free to 40dp, resisted toward 72dp; a release past 56dp
            // replies and springs back either way. Reduced motion still moves (it is a drag), but
            // without the haptic tick.
            val scope = rememberCoroutineScope()
            val haptic = LocalHapticFeedback.current
            val swipe = remember { Animatable(0f) }
            var rawDrag by remember(m.id) { mutableStateOf(0f) }
            var ticked by remember(m.id) { mutableStateOf(false) }
            Icon(
                Icons.AutoMirrored.Filled.Reply, contentDescription = null, tint = c.accent,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 10.dp, start = 2.dp)
                    .size(18.dp)
                    .graphicsLayer {
                        val p = waSwipeProgress(swipe.value)
                        alpha = p
                        scaleX = 0.6f + 0.4f * p
                        scaleY = 0.6f + 0.4f * p
                    },
            )
            Box(
                Modifier
                    .onGloballyPositioned { if (m.id.isNotBlank()) env.rowBounds[m.id] = it.boundsInRoot() }
                    .combinedClickable(onClick = {}, onLongClick = { env.onLongPress(m) })
                    .pointerInput(m.id) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (waSwipeReached(swipe.value)) env.onReply(m)
                                scope.launch { swipe.animateTo(0f, moveSpring()) }
                            },
                            onDragCancel = { scope.launch { swipe.animateTo(0f, moveSpring()) } },
                        ) { _, dx ->
                            rawDrag = (rawDrag + dx).coerceAtLeast(0f)
                            scope.launch { swipe.snapTo(waSwipeOffset(rawDrag)) }
                            val reached = waSwipeReached(swipe.value)
                            if (reached != ticked) {
                                ticked = reached
                                if (reached && !env.reduced) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        }
                    }
                    .offset { IntOffset(swipe.value.roundToInt(), 0) },
            ) {
                if (bubbleFree) {
                    Column(Modifier.align(side), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                        if (row.groupFirst && env.group && !mine) {
                            SenderName(m, env, Modifier.padding(start = 4.dp, bottom = 2.dp))
                        }
                        if (sticker) {
                            BubbleImage(
                                msg = m, boxW = WA_STICKER_BOX.dp, boxH = WA_STICKER_BOX.dp, sticker = true, mine = mine, env = env,
                                cornerRadius = 16.dp, onOpen = {},
                            )
                        } else {
                            Text(
                                m.text.trim(),
                                style = chatBodyStyle(c.ink).copy(fontSize = waJumboSize(jumbo).sp, lineHeight = (waJumboSize(jumbo) + 8).sp),
                            )
                        }
                        TimeChip(m, c, Modifier.padding(top = 2.dp))
                    }
                } else {
                    val shape = remember(mine, row.groupFirst, row.groupLast) {
                        BubbleShape(mine, tail = row.groupFirst, topNeighbour = !row.groupFirst, bottomNeighbour = !row.groupLast)
                    }
                    val convex = remember(mine, row.groupFirst, row.groupLast) {
                        BubbleShape(mine, tail = false, topNeighbour = !row.groupFirst, bottomNeighbour = !row.groupLast)
                    }
                    val pulse = remember { Animatable(0f) }
                    LaunchedEffect(flash) {
                        if (flash) {
                            pulse.animateTo(0.5f, tween(120))
                            pulse.animateTo(0f, tween(400))
                        }
                    }
                    Box(
                        Modifier
                            .align(side)
                            .widthIn(max = env.maxBubble)
                            .pressScale(env.reduced)
                            .bubbleShadow(shape, convex, mine)
                            .background(bubbleBg(c, mine), shape)
                            .background(c.accent.copy(alpha = pulse.value), shape),
                    ) {
                        BubbleBody(row, env, kind)
                    }
                }
                if (sum.total > 0) {
                    ReactionChip(
                        sum = sum,
                        m = m,
                        env = env,
                        modifier = Modifier
                            .align(if (mine) Alignment.BottomEnd else Alignment.BottomStart)
                            .offset(x = if (mine) (-8).dp else 8.dp, y = 12.dp),
                    )
                }
            }
        }
    }
}

/** The menu overlay's lifted copy of a bubble (or bubble-free sticker / jumbo): same body, no gestures. */
@Composable
fun BubbleClone(row: MsgRow, env: ChatEnv, modifier: Modifier = Modifier) {
    val m = row.msg
    val mine = m.fromMe
    val c = env.colors
    val kind = m.mediaKind()
    val sticker = kind == WaMediaKind.STICKER && !m.revoked
    val jumbo = remember(m.text, m.replyToId, m.forwarded, kind, m.revoked) {
        if (kind == null && !m.revoked && m.replyToId.isBlank() && m.replyToText.isBlank() && !m.forwarded) waEmojiOnlyCount(m.text) else 0
    }
    if (sticker || jumbo > 0) {
        Column(modifier, horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            if (sticker) {
                BubbleImage(
                    msg = m, boxW = WA_STICKER_BOX.dp, boxH = WA_STICKER_BOX.dp, sticker = true, mine = mine, env = env,
                    cornerRadius = 16.dp, onOpen = {},
                )
            } else {
                Text(
                    m.text.trim(),
                    style = chatBodyStyle(c.ink).copy(fontSize = waJumboSize(jumbo).sp, lineHeight = (waJumboSize(jumbo) + 8).sp),
                )
            }
            TimeChip(m, c, Modifier.padding(top = 2.dp))
        }
        return
    }
    val shape = remember(mine, row.groupFirst, row.groupLast) {
        BubbleShape(mine, tail = row.groupFirst, topNeighbour = !row.groupFirst, bottomNeighbour = !row.groupLast)
    }
    val convex = remember(mine, row.groupFirst, row.groupLast) {
        BubbleShape(mine, tail = false, topNeighbour = !row.groupFirst, bottomNeighbour = !row.groupLast)
    }
    Box(
        modifier
            .widthIn(max = env.maxBubble)
            .bubbleShadow(shape, convex, mine)
            .background(bubbleBg(c, mine), shape),
    ) {
        BubbleBody(row, env, kind)
    }
}

@Composable
private fun SenderName(m: WaMessage, env: ChatEnv, modifier: Modifier = Modifier) {
    val name = m.senderName.ifBlank { waSenderLabel(m.sender, env.names) }
    Text(
        name,
        style = chatSecondaryStyle(env.colors.senderColor(m.sender), FontWeight.SemiBold),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun BubbleBody(row: MsgRow, env: ChatEnv, kind: WaMediaKind?) {
    val m = row.msg
    val mine = m.fromMe
    val c = env.colors
    val revoked = m.revoked
    val image = kind == WaMediaKind.IMAGE && !revoked
    val ink = if (mine) c.outInk else c.ink
    val metaInk = if (mine) c.outMeta else c.meta
    val caption = if (revoked) "" else waCaptionOf(m)
    val box = if (image) waMediaBox(m.width, m.height) else null
    // Header lines sit inside the 3dp media inset, so they get the rest of the 10dp text padding.
    val hp = if (image) 7.dp else 0.dp
    val outer = when {
        image -> Modifier.width(box!!.w.dp + 6.dp).padding(3.dp)
        else -> Modifier.width(IntrinsicSize.Max).padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 6.dp)
    }
    Column(outer) {
        if (row.groupFirst && env.group && !mine) {
            SenderName(m, env, Modifier.padding(horizontal = hp).padding(top = if (image) 3.dp else 0.dp, bottom = 1.dp))
        }
        waForwardLabel(m).takeIf { it.isNotEmpty() && !revoked }?.let { label ->
            Row(Modifier.padding(horizontal = hp).padding(top = if (image) 3.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.AutoMirrored.Filled.Reply, contentDescription = null, tint = metaInk,
                    modifier = Modifier.size(13.dp).graphicsLayer { scaleX = -1f },
                )
                Spacer(Modifier.width(4.dp))
                Text(label, style = chatSecondaryStyle(metaInk).copy(fontStyle = FontStyle.Italic))
            }
        }
        if (!revoked) {
            waQuoteView(m, env.byId, env.names)?.let { q ->
                QuoteCard(
                    q, mine, c,
                    onClick = { if (m.replyToId.isNotBlank()) env.onQuoteTap(m.replyToId) },
                    modifier = Modifier.padding(horizontal = if (image) 4.dp else 0.dp).padding(top = if (image) 4.dp else 2.dp, bottom = 4.dp),
                )
            }
        }

        if (revoked) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Block, contentDescription = null, tint = metaInk, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                TextWithMeta(
                    text = buildAnnotatedString { append("This message was deleted") },
                    style = chatBodyStyle(metaInk).copy(fontStyle = FontStyle.Italic),
                    quoteRanges = emptyList(), quoteBar = metaInk,
                ) { MetaContent(m.ts, false, false, metaInk) }
            }
            return@Column
        }

        var captionOverlayed = false
        when (kind) {
            WaMediaKind.IMAGE -> {
                captionOverlayed = caption.isBlank()
                BubbleImage(
                    msg = m, boxW = box!!.w.dp, boxH = box.h.dp, sticker = false, mine = mine, env = env,
                    cornerRadius = 15.dp, onOpen = { env.onOpenImage(m) },
                    overlay = {
                        if (captionOverlayed) {
                            TimeChip(m, c, Modifier.align(Alignment.BottomEnd).padding(6.dp))
                        }
                    },
                )
            }
            WaMediaKind.VOICE -> VoiceBubble(
                msg = m, ui = env.voice(m), mine = mine, c = c,
                onToggle = { env.onVoiceToggle(m) },
                onSeek = { env.onVoiceSeek(m, it) },
                onSpeed = env.onVoiceSpeed,
                modifier = Modifier.width(236.dp),
            )
            null, WaMediaKind.STICKER -> {}
            else -> DocumentCard(m, kind, mine, c, Modifier.padding(top = 2.dp, bottom = 2.dp).widthIn(min = 210.dp))
        }

        if (caption.isNotBlank()) {
            val f = remember(caption) { formatWaText(caption) }
            val theme = remember(c, mine) {
                FormatTheme(codeBg = ink.copy(alpha = 0.1f), link = if (mine) c.outLink else c.accent, quoteInk = metaInk)
            }
            val ann = remember(f, theme, env.marks) {
                f.toAnnotatedString(theme).withHighlights(findTermRanges(f.text, env.marks), c.markBg())
            }
            TextWithMeta(
                text = ann,
                style = chatBodyStyle(ink),
                quoteRanges = remember(f) { f.quoteRanges() },
                quoteBar = metaInk,
                modifier = Modifier.padding(horizontal = hp).padding(top = if (image) 4.dp else 0.dp, bottom = if (image) 3.dp else 0.dp),
            ) { MetaContent(m.ts, m.edited, m.starred, metaInk) }
        } else if (!captionOverlayed) {
            Row(
                Modifier.align(Alignment.End).padding(horizontal = hp, vertical = 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { MetaContent(m.ts, m.edited, m.starred, metaInk) }
        }
    }
}

@Composable
private fun QuoteCard(q: QuoteView, mine: Boolean, c: ChatColors, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg = if (mine) lerp(c.outBg, Color.Black, 0.18f) else lerp(c.inBg, Color.White, 0.06f)
    val barColor = if (q.fromMe) c.accent else c.senderColor(q.authorJid)
    val ink = if (mine) c.outInk else c.ink
    val metaInk = if (mine) c.outMeta else c.meta
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(barColor, RoundedCornerShape(2.dp)))
        Column(Modifier.padding(start = 8.dp, end = 10.dp, top = 5.dp, bottom = 5.dp)) {
            Text(q.author, style = chatSecondaryStyle(barColor, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                q.mediaKind?.let {
                    Icon(waKindIcon(it), contentDescription = null, tint = metaInk, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                val text = q.text.ifBlank { q.mediaKind?.label ?: "" }
                Text(
                    text,
                    style = chatSecondaryStyle(if (q.text.isBlank()) metaInk else ink.copy(alpha = 0.85f)),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Reactions hang off the bubble's bottom edge: up to 3 distinct emoji (most frequent first) and the
 * total when more than one. Pops with a spring when it first appears or changes; tapping lists who
 * reacted, and tapping my own reaction removes it (the emoji itself is also reactable from the
 * long-press menu).
 */
@Composable
private fun ReactionChip(sum: ReactionSummary, m: WaMessage, env: ChatEnv, modifier: Modifier = Modifier) {
    val c = env.colors
    val msgId = m.id
    val sig = waReactionSig(sum)
    val scale = remember { Animatable(1f) }
    LaunchedEffect(sig, env.popEnabled) {
        val seen = env.reactionSeen[msgId]
        env.reactionSeen[msgId] = sig
        if (env.popEnabled && seen != sig && !env.reduced) {
            scale.snapTo(0.6f)
            scale.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow * 4))
        }
    }
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier
                .scale(scale.value)
                .heightIn(min = 22.dp)
                .border(1.dp, c.wall, CircleShape)
                .clip(CircleShape)
                .background(lerp(c.inBg, Color.White, 0.1f))
                .clickable { open = true }
                .padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(sum.top.joinToString("") { it.emoji }, style = chatSecondaryStyle(c.ink))
            if (sum.total > 1) Text(sum.total.toString(), style = chatSecondaryStyle(c.ink.copy(alpha = 0.8f)))
        }
        if (open) {
            Popup(alignment = Alignment.BottomStart, onDismissRequest = { open = false }) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = lerp(c.inBg, Color.White, 0.05f),
                    shadowElevation = 8.dp,
                ) {
                    Column(Modifier.padding(vertical = 6.dp).widthIn(min = 160.dp, max = 260.dp)) {
                        for (r in sum.reactors) {
                            Row(
                                Modifier
                                    .heightIn(min = 40.dp)
                                    .padding(horizontal = 14.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .combinedClickable(
                                        onClick = {
                                            if (r.key == "me") {
                                                open = false
                                                env.onReact(m, "")
                                            }
                                        },
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(r.emoji, style = chatSecondaryStyle(c.ink).copy(fontSize = 18.sp))
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    if (r.key == "me") "You - tap to remove" else waSenderLabel(r.key, env.names),
                                    style = chatSecondaryStyle(if (r.key == "me") c.accent else c.ink),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
