package com.raavivi.sysmon.ui.whatsapp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.raavivi.sysmon.core.model.WaMessage
import com.raavivi.sysmon.ui.common.formatClock
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

/**
 * The chat's photo gallery (spec section 8): every loaded photo in chat order, starting at the tapped
 * one. Swipe pages; pinch / pan 1-5x (paging disabled while zoomed); double-tap toggles 2.5x at the
 * tapped point; swipe down dismisses with the backdrop fading along. The top bar hides on a tap:
 * close, sender + date, counter, Save, and "Show in chat" which scrolls back to the bubble.
 */
@Composable
fun ChatGallery(
    photos: List<WaMessage>,
    startIndex: Int,
    names: Map<String, String>,
    nowSec: Double,
    url: (WaMessage) -> String,
    thumbUrl: (WaMessage) -> String,
    imageLoader: coil.ImageLoader,
    onSave: (WaMessage) -> Unit,
    onShowInChat: (WaMessage) -> Unit,
    onClose: () -> Unit,
) {
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, photos.lastIndex)) { photos.size }
    val c = rememberChatColors()
    val context = LocalContext.current

    var chrome by remember { mutableStateOf(true) }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val dismissY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // Zoom and pan are per page: they reset when the settled page changes.
    LaunchedEffect(pager.settledPage) {
        scale = 1f
        pan = Offset.Zero
        dismissY.snapTo(0f)
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val fade = 1f - min(1f, abs(dismissY.value) / 480f)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (0.985f * fade).coerceAtLeast(0.35f)))) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
                // Swiping pages is disabled while zoomed (the pan owns the gesture instead).
                userScrollEnabled = scale <= 1.01f,
            ) { page ->
                val m = photos[page]
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(m.id) {
                            detectTapGestures(
                                onTap = { chrome = !chrome },
                                onDoubleTap = { pos ->
                                    if (scale > 1.01f) {
                                        scale = 1f
                                        pan = Offset.Zero
                                    } else {
                                        // Zoom 2.5x centred on the tapped point, nudged toward it.
                                        scale = 2.5f
                                        val w = size.width.toFloat()
                                        val h = size.height.toFloat()
                                        pan = Offset((w / 2 - pos.x) * 0.35f, (h / 2 - pos.y) * 0.35f)
                                    }
                                },
                            )
                        }
                        .pointerInput(m.id) {
                            detectTransformGestures { _, gesturePan, zoom, _ ->
                                if (zoom != 1f) {
                                    scale = (scale * zoom).coerceIn(1f, 5f)
                                    if (scale <= 1.01f) {
                                        scale = 1f
                                        pan = Offset.Zero
                                    }
                                }
                                if (scale > 1f) pan += gesturePan
                            }
                        }
                        .pointerInput(m.id) {
                            // Swipe down to dismiss, only at rest (never mid-zoom).
                            detectVerticalDragGestures(
                                onDragEnd = {
                                    if (dismissY.value > 200f) onClose()
                                    else scope.launch { dismissY.animateTo(0f, tween(160)) }
                                },
                                onDragCancel = { },
                            ) { _, dy ->
                                if (scale <= 1.01f) scope.launch { dismissY.snapTo((dismissY.value + dy).coerceAtLeast(0f)) }
                            }
                        },
                ) {
                    // The cached thumb shows instantly; the full-resolution image fades in over it.
                    var full by remember(m.id) { mutableStateOf(false) }
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(url(m)).crossfade(false).build(),
                        imageLoader = imageLoader,
                        contentDescription = "photo",
                        contentScale = ContentScale.Fit,
                        onState = { if (it is coil.compose.AsyncImagePainter.State.Success) full = true },
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = pan.x
                                translationY = pan.y + dismissY.value
                                alpha = if (full) 1f else 0f
                            },
                    )
                    if (!full) {
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(thumbUrl(m)).crossfade(false).build(),
                            imageLoader = imageLoader,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    translationY = dismissY.value
                                    alpha = 1f - min(0.4f, dismissY.value / 900f)
                                },
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = chrome,
                enter = fadeIn(tween(140)),
                exit = fadeOut(tween(140)),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                val m = photos[pager.settledPage]
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = c.pillInk)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (m.fromMe) "You" else (names[m.sender] ?: m.senderName.ifBlank { m.sender }),
                            style = chatSecondaryStyle(c.pillInk, androidx.compose.ui.text.font.FontWeight.SemiBold)
                                .copy(fontSize = 14.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            waDayLabel(m.ts, nowSec) + " " + formatClock(m.ts),
                            style = chatMetaStyle(c.pillInk),
                            maxLines = 1,
                        )
                    }
                    Text(
                        "${pager.settledPage + 1} / ${photos.size}",
                        style = chatMetaStyle(c.pillInk),
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                    IconButton(onClick = { onSave(m) }) {
                        Icon(Icons.Filled.Save, contentDescription = "Save", tint = c.pillInk)
                    }
                    IconButton(onClick = { onShowInChat(m) }) {
                        Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = "Show in chat", tint = c.pillInk)
                    }
                }
            }
        }
    }
}
