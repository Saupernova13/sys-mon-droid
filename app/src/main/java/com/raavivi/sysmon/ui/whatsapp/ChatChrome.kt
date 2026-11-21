@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.raavivi.sysmon.ui.whatsapp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.raavivi.sysmon.core.model.WaChat
import com.raavivi.sysmon.core.model.waInitials
import com.raavivi.sysmon.ui.common.formatClock as unusedClock

/** Initials on a tinted disc; the cached picture, once it loads, covers them (a 404 leaves the initials). */
@Composable
fun ChatAvatar(
    name: String,
    jid: String,
    url: String,
    version: Int,
    imageLoader: ImageLoader,
    c: ChatColors,
    size: Dp = 40.dp,
) {
    val key = "avatar:$jid:$version"
    val hue = c.senderColor(jid)
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(lerp(c.inBg, hue, 0.28f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            waInitials(name),
            style = chatSecondaryStyle(lerp(hue, Color.White, 0.35f), FontWeight.SemiBold)
                .copy(fontSize = if (size >= 44.dp) 16.sp1() else 13.sp1()),
        )
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(url)
                .memoryCacheKey(key)
                .diskCacheKey(key)
                .build(),
            imageLoader = imageLoader,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun Int.sp1() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)
private fun Dp.sp1() = androidx.compose.ui.unit.TextUnit(this.value, androidx.compose.ui.unit.TextUnitType.Sp)

/** One of the header's round icon buttons: 44dp, press fill at 8% ink, with an optional count badge. */
@Composable
internal fun HeaderAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    c: ChatColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = c.ink,
    badge: Int = 0,
    busy: Boolean = false,
    keepTintWhenDisabled: Boolean = false,
) {
    Box(modifier.size(44.dp), contentAlignment = Alignment.Center) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(44.dp)) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = c.accent)
            } else {
                Icon(icon, contentDescription = description, tint = if (enabled || keepTintWhenDisabled) tint else tint.copy(alpha = 0.38f))
            }
        }
        if (badge > 0) CountBadge(badge, c, Modifier.align(Alignment.TopEnd).offset(x = (-2).dp, y = 4.dp))
    }
}

/** Accent pill with a count (11sp semibold, 20dp min). */
@Composable
fun CountBadge(count: Int, c: ChatColors, modifier: Modifier = Modifier, min: Dp = 16.dp) {
    Box(
        modifier
            .defaultMinSize(min, min)
            .clip(CircleShape)
            .background(c.accent)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            style = chatMetaStyle(c.onAccent).copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

/** Conversation header: avatar, name (16sp) and a 13sp subtitle, then icon actions (spec section 6). */
@Composable
fun ConversationHeader(
    name: String,
    subtitle: String,
    avatar: @Composable () -> Unit,
    unread: Int,
    canGoToDate: Boolean,
    pinned: Boolean,
    localPin: Boolean,
    jumpingFirst: Boolean,
    searchOn: Boolean,
    c: ChatColors,
    onBack: () -> Unit,
    onMarkRead: () -> Unit,
    onGoToDate: () -> Unit,
    onJumpFirst: () -> Unit,
    onPin: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .shadow(4.dp, ambientColor = Color.Black, spotColor = Color.Black)
            .background(lerp(c.wall, c.inBg, 0.7f))
            .drawBehind { drawRect(c.hairline, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderAction(Icons.AutoMirrored.Filled.ArrowBack, "Back", c, onBack)
        avatar()
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = chatSecondaryStyle(c.ink, FontWeight.SemiBold).copy(fontSize = 16.sp1(), lineHeight = 20.sp1()),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = chatSecondaryStyle(c.meta), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        HeaderAction(Icons.Filled.Search, "Search in chat", c, onSearch, tint = if (searchOn) c.accent else c.ink)
        HeaderAction(Icons.Filled.DoneAll, "Mark as read", c, onMarkRead, enabled = unread > 0, badge = unread)
        HeaderAction(Icons.Filled.CalendarMonth, "Go to date", c, onGoToDate, enabled = canGoToDate)
        HeaderAction(Icons.Filled.VerticalAlignTop, "Jump to first message", c, onJumpFirst, busy = jumpingFirst)
        // A sys-mon-owned pin cannot be changed from the phone: shown pinned, and the button is inert.
        HeaderAction(
            Icons.Filled.PushPin,
            when { localPin -> "Pinned in sys-mon"; pinned -> "Unpin chat"; else -> "Pin chat" },
            c, onPin, enabled = !localPin, tint = if (pinned || localPin) c.accent else c.meta,
            keepTintWhenDisabled = localPin,
        )
    }
}

/** Jump-to-latest button: scales in/out, with a count of messages that arrived while scrolled up. */
@Composable
fun JumpToLatestFab(
    visible: Boolean,
    count: Int,
    loading: Boolean,
    highlight: Boolean,
    c: ChatColors,
    reduced: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (reduced) fadeIn() else scaleIn(popSpring(), initialScale = 0.5f) + fadeIn(),
        exit = if (reduced) fadeOut() else scaleOut(tween(140), targetScale = 0.5f) + fadeOut(tween(140)),
    ) {
        Box {
            Box(
                Modifier
                    .size(44.dp)
                    .shadow(6.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
                    .clip(CircleShape)
                    .background(if (highlight) lerp(c.inBg, c.accent, 0.3f) else lerp(c.inBg, Color.White, 0.08f))
                    .clickable(onClick = onClick)
                    .semantics { contentDescription = "Jump to latest" },
                contentAlignment = Alignment.Center,
            ) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = c.accent)
                } else {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = c.ink)
                }
            }
            if (count > 0) CountBadge(count, c, Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp))
        }
    }
}

/** The composer's context chip: the message being edited, or the one being replied to. */
data class ComposerChip(val editing: Boolean, val who: String, val snippet: String)

/**
 * Floating composer: a pill input with the attach button inside on the left, a reply / edit chip that
 * rides on top of the input, and a 44dp accent button that is the mic while the input is empty and
 * morphs to send (scale + 90 degree rotate, spring) once there is text.
 */
@Composable
fun ChatComposer(
    text: String,
    onTextChange: (String) -> Unit,
    enabled: Boolean,
    c: ChatColors,
    reduced: Boolean,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onMic: () -> Unit,
    chip: ComposerChip? = null,
    onChipCancel: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val hasText = text.isNotBlank()
    val p by animateFloatAsState(if (hasText) 1f else 0f, if (reduced) tween(0) else popSpring(), label = "morph")
    Row(
        modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .shadow(3.dp, RoundedCornerShape(22.dp), ambientColor = Color.Black, spotColor = Color.Black)
                .clip(RoundedCornerShape(22.dp))
                .background(c.inBg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                if (chip != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 10.dp, end = 6.dp, top = 8.dp)
                            .height(IntrinsicSize.Min),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(4.dp).fillMaxHeight().background(c.accent, RoundedCornerShape(2.dp)))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (chip.editing) "Editing message" else "Replying to ${chip.who}",
                                style = chatSecondaryStyle(c.accent, FontWeight.SemiBold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (chip.snippet.isNotBlank()) {
                                Text(
                                    chip.snippet,
                                    style = chatSecondaryStyle(c.meta),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        IconButton(onClick = onChipCancel, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel ${if (chip.editing) "edit" else "reply"}", tint = c.meta)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onAttach, enabled = enabled, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Filled.AttachFile, contentDescription = "Attach file", tint = c.meta)
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = onTextChange,
                        textStyle = chatBodyStyle(c.ink),
                        cursorBrush = SolidColor(c.accent),
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.weight(1f).padding(vertical = 11.dp).padding(end = 14.dp),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (text.isEmpty()) Text("Message", style = chatBodyStyle(c.meta))
                                inner()
                            }
                        },
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(44.dp)
                .shadow(3.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(CircleShape)
                .background(c.accent)
                .clickable(enabled = enabled) { if (hasText) onSend() else onMic() }
                .semantics { contentDescription = if (hasText) "Send" else "Record voice note" },
            contentAlignment = Alignment.Center,
        ) {
            val sendP = p.coerceIn(0f, 1f)
            Icon(
                Icons.Filled.Mic, contentDescription = null, tint = c.onAccent,
                modifier = Modifier.graphicsLayer {
                    alpha = 1f - sendP
                    if (!reduced) {
                        val s = 0.6f + 0.4f * (1f - sendP)
                        scaleX = s; scaleY = s
                        rotationZ = sendP * 90f
                    }
                },
            )
            Icon(
                Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = c.onAccent,
                modifier = Modifier.graphicsLayer {
                    alpha = sendP
                    if (!reduced) {
                        val s = 0.6f + 0.4f * p
                        scaleX = s; scaleY = s
                        rotationZ = (1f - p) * -90f
                    }
                },
            )
        }
    }
}

/** Replaces the composer while recording: pulsing red dot, elapsed timer, cancel and send. */
@Composable
fun ChatRecordingBar(
    elapsed: String,
    c: ChatColors,
    reduced: Boolean,
    danger: Color,
    onCancel: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pulse = rememberInfiniteTransition(label = "rec")
    val a by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (reduced) 1f else 0.3f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "recAlpha",
    )
    Row(modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .shadow(3.dp, RoundedCornerShape(22.dp), ambientColor = Color.Black, spotColor = Color.Black)
                .clip(RoundedCornerShape(22.dp))
                .background(c.inBg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = "Cancel recording", tint = c.meta)
            }
            Box(Modifier.size(10.dp).graphicsLayer { alpha = a }.clip(CircleShape).background(danger))
            Spacer(Modifier.width(10.dp))
            Text(elapsed, style = chatBodyStyle(c.ink).copy(fontFeatureSettings = "tnum"))
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(44.dp)
                .shadow(3.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(CircleShape)
                .background(c.accent)
                .clickable(onClick = onSend)
                .semantics { contentDescription = "Send voice note" },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = c.onAccent) }
    }
}

/** Search field as a pill with a leading search icon. */
@Composable
fun ChatSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    c: ChatColors,
    modifier: Modifier = Modifier,
    placeholder: String = "Search chats",
    /** The IME's Search key; null keeps the plain keyboard. */
    onSearch: (() -> Unit)? = null,
    autoFocus: Boolean = false,
) {
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(c.inBg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = c.meta, modifier = Modifier.padding(start = 14.dp, end = 8.dp).size(20.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = chatBodyStyle(c.ink),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(imeAction = if (onSearch != null) ImeAction.Search else ImeAction.Default),
            keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
            modifier = Modifier.weight(1f).padding(vertical = 11.dp).padding(end = 14.dp).focusRequester(focus),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = chatBodyStyle(c.meta))
                    inner()
                }
            },
        )
    }
}

/** A chat-list row: avatar 48dp, name + time, preview line, pin and an accent unread badge that pops
 *  on change. Long-press opens [menu] (pins, mark as read) in an anchored dropdown. */
@Composable
fun ChatListRow(
    chat: WaChat,
    nowSec: Double,
    avatar: @Composable () -> Unit,
    c: ChatColors,
    reduced: Boolean,
    active: Boolean,
    onClick: () -> Unit,
    menuOpen: Boolean = false,
    onMenuOpenChange: (Boolean) -> Unit = {},
    menu: @Composable ColumnScope.() -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val preview = remember(chat) { waPreviewLine(chat, waIsGroup(chat.jid)) }
    val unread = chat.unread > 0
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .pressScale(reduced)
                .background(if (active) c.accent.copy(alpha = 0.10f) else Color.Transparent)
                .combinedClickable(onClick = onClick, onLongClick = { onMenuOpenChange(true) })
                .heightIn(min = 72.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            avatar()
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        chat.name,
                        style = chatSecondaryStyle(c.ink, FontWeight.SemiBold).copy(fontSize = 15.sp1(), lineHeight = 20.sp1()),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (chat.lastTs > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            waChatTime(chat.lastTs, nowSec),
                            style = chatMetaStyle(if (unread) c.accent else c.meta),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        if (preview != null) PreviewText(preview, c)
                    }
                    if (chat.pinned) {
                        Icon(
                            Icons.Filled.PushPin,
                            contentDescription = if (chat.localPin) "Pinned in sys-mon" else "Pinned",
                            tint = if (chat.localPin) c.accent else c.meta,
                            modifier = Modifier.padding(start = 6.dp).size(14.dp),
                        )
                    }
                    if (unread) {
                        Spacer(Modifier.width(6.dp))
                        UnreadBadge(chat.unread, c, reduced)
                    }
                }
            }
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { onMenuOpenChange(false) },
            offset = DpOffset(56.dp, (-40).dp),
        ) {
            menu()
        }
    }
}

@Composable
private fun PreviewText(p: PreviewLine, c: ChatColors) {
    val style = chatSecondaryStyle(c.meta)
    if (p.prefix.isNotEmpty()) Text(p.prefix, style = style, maxLines = 1, softWrap = false)
    if (p.revoked) {
        Icon(Icons.Filled.Block, contentDescription = null, tint = c.meta, modifier = Modifier.padding(end = 4.dp).size(13.dp))
    } else p.kind?.let {
        Icon(waKindIcon(it), contentDescription = null, tint = c.meta, modifier = Modifier.padding(end = 4.dp).size(14.dp))
    }
    Text(
        p.text,
        style = if (p.revoked) style.copy(fontStyle = FontStyle.Italic) else style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Accent pill, 20dp min; pops with a spring when the count changes. */
@Composable
fun UnreadBadge(count: Int, c: ChatColors, reduced: Boolean) {
    val scale = remember { Animatable(1f) }
    var shown by remember { mutableIntStateOf(count) }
    LaunchedEffect(count) {
        if (count != shown) {
            shown = count
            if (!reduced) {
                scale.snapTo(0.6f)
                scale.animateTo(1f, popSpring())
            }
        }
    }
    CountBadge(count, c, Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }, min = 20.dp)
}
