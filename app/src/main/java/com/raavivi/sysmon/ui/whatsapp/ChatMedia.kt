package com.raavivi.sysmon.ui.whatsapp

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.raavivi.sysmon.core.model.WaMessage

/** Icon for a media kind (quote cards, previews, document tiles). */
fun waKindIcon(kind: WaMediaKind): ImageVector = when (kind) {
    WaMediaKind.IMAGE -> Icons.Filled.Image
    WaMediaKind.STICKER -> Icons.Filled.EmojiEmotions
    WaMediaKind.GIF -> Icons.Filled.Gif
    WaMediaKind.VIDEO -> Icons.Filled.Videocam
    WaMediaKind.VOICE -> Icons.Filled.Mic
    WaMediaKind.DOCUMENT -> Icons.Filled.InsertDriveFile
}

/**
 * The image request for a bubble photo / sticker: an explicit decode size (the reserved box), a stable
 * memory + disk cache key (the retry [nonce] changes it so a retry really refetches) and a 180ms
 * crossfade. The VM's page prefetch builds the same request, so a bubble scrolling in hits the cache.
 */
fun waImageRequest(context: Context, url: String, widthPx: Int, heightPx: Int, nonce: Int = 0): ImageRequest =
    ImageRequest.Builder(context)
        .data(url)
        .size(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1))
        .memoryCacheKey("$url#$nonce")
        .diskCacheKey("$url#$nonce")
        .crossfade(180)
        .build()

/** Gently sweeping highlight over [base]; with [reduced] motion a flat fill. */
@Composable
fun Modifier.shimmer(base: Color, highlight: Color, reduced: Boolean): Modifier {
    if (reduced) return this.background(base)
    val t = rememberInfiniteTransition(label = "shimmer")
    val phase by t.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerPhase",
    )
    return this.background(base).drawBehindShimmer(highlight, phase)
}

private fun Modifier.drawBehindShimmer(highlight: Color, phase: Float): Modifier =
    this.then(
        Modifier.background(
            Brush.linearGradient(
                0f to Color.Transparent,
                0.5f to highlight,
                1f to Color.Transparent,
                start = Offset(phase * 600f - 300f, 0f),
                end = Offset(phase * 600f + 300f, 300f),
            ),
        ),
    )

/** Placeholder / loaded neutral for media boxes. */
private fun mediaNeutral(c: ChatColors, mine: Boolean): Color = lerp(if (mine) c.outBg else c.inBg, c.ink, 0.07f)

/**
 * A photo or sticker in its reserved box (no layout shift): shimmer until it loads, then a crossfade.
 * Old media that is not downloaded waits for a tap ("Tap to download"); a failed load offers a retry.
 * [overlay] is drawn on top (the time chip when the photo has no caption).
 */
@Composable
fun BubbleImage(
    msg: WaMessage,
    boxW: Dp,
    boxH: Dp,
    sticker: Boolean,
    mine: Boolean,
    env: ChatEnv,
    cornerRadius: Dp,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val c = env.colors
    val context = LocalContext.current
    val density = LocalDensity.current
    var failed by remember(msg.id) { mutableStateOf(false) }
    var nonce by remember(msg.id) { mutableIntStateOf(0) }
    var loaded by remember(msg.id, nonce) { mutableStateOf(false) }
    var load by remember(msg.id, msg.downloaded) {
        mutableStateOf(shouldAutoLoadImage(msg.downloaded, msg.ts, env.nowSec))
    }
    val shape = RoundedCornerShape(cornerRadius)
    val neutral = mediaNeutral(c, mine)
    Box(
        modifier
            .size(boxW, boxH)
            .clip(shape)
            .then(
                when {
                    sticker && loaded -> Modifier
                    !load || failed -> Modifier.background(neutral)
                    else -> Modifier.shimmer(neutral, c.ink.copy(alpha = 0.08f), env.reduced)
                },
            ),
    ) {
        if (!load) {
            TapCard("Tap to download", Icons.Filled.Download, c) { load = true }
        } else {
            val wPx = with(density) { boxW.roundToPx() }
            val hPx = with(density) { boxH.roundToPx() }
            val url = env.mediaUrl(msg, !sticker)
            AsyncImage(
                model = waImageRequest(context, url, wPx, hPx, nonce),
                imageLoader = env.imageLoader,
                contentDescription = msg.filename ?: if (sticker) "sticker" else "photo",
                contentScale = if (sticker) ContentScale.Fit else ContentScale.Crop,
                onState = {
                    loaded = it is AsyncImagePainter.State.Success
                    failed = it is AsyncImagePainter.State.Error
                },
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (sticker) Modifier else Modifier.clickable(onClick = onOpen)),
            )
            if (failed) TapCard("Tap to retry", Icons.Filled.Refresh, c) { failed = false; nonce++ }
        }
        overlay()
    }
}

@Composable
private fun TapCard(label: String, icon: ImageVector, c: ChatColors, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(c.pill),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = c.pillInk, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.height(6.dp))
        Text(label, style = chatSecondaryStyle(c.pillInk.copy(alpha = 0.85f)))
    }
}

/** File-type card: accent-tinted 40dp tile, file name (1 line, ellipsis), type label. */
@Composable
fun DocumentCard(msg: WaMessage, kind: WaMediaKind, mine: Boolean, c: ChatColors, modifier: Modifier = Modifier) {
    val name = msg.filename?.takeIf { it.isNotBlank() } ?: msg.mediaType ?: kind.label
    val label = docTypeLabel(msg, kind)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(c.accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) { Icon(waKindIcon(kind), contentDescription = null, tint = if (mine) c.outLink else c.accent, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = chatSecondaryStyle(if (mine) c.outInk else c.ink, FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(label, style = chatMetaStyle(if (mine) c.outMeta else c.meta))
        }
    }
}

/** "PDF", "ZIP", ... from the file name, else the mime subtype, else the kind label. */
fun docTypeLabel(msg: WaMessage, kind: WaMediaKind): String {
    val ext = msg.filename?.substringAfterLast('.', "")?.takeIf { it.length in 1..5 && it.all { ch -> ch.isLetterOrDigit() } }
    if (ext != null) return ext.uppercase()
    val sub = msg.mime.substringAfter('/', "").substringBefore(';').takeIf { it.isNotBlank() && it.length <= 12 }
    return sub?.uppercase() ?: kind.label
}

/** A voice note's player state, read from the VoiceNotePlayer at the call site so the bubble stays plain. */
@Immutable
data class VoiceUi(
    val current: Boolean = false,
    val loading: Boolean = false,
    val playing: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
    val speed: Float = 1f,
)

/**
 * Voice note: 36dp accent play button, a static waveform seek bar (28 bars from a hash of the message
 * id, played portion in accent), elapsed / total under it and the speed chip. Behaviour is the old
 * slider's: seeking only works for the loaded note.
 */
@Composable
fun VoiceBubble(
    msg: WaMessage,
    ui: VoiceUi,
    mine: Boolean,
    c: ChatColors,
    onToggle: () -> Unit,
    onSeek: (Float) -> Unit,
    onSpeed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bars = remember(msg.id) { waWaveformBars(msg.id) }
    var dragging by remember(msg.id) { mutableStateOf<Float?>(null) }
    val frac = dragging ?: if (ui.durationMs > 0) (ui.positionMs.toFloat() / ui.durationMs).coerceIn(0f, 1f) else 0f
    val playedColor = if (mine) c.outLink else c.accent
    val restColor = if (mine) c.outMeta.copy(alpha = 0.45f) else c.meta.copy(alpha = 0.45f)
    val seekable = ui.current && ui.durationMs > 0
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            if (ui.loading) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = playedColor)
            } else {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(c.accent)
                        .clickable(onClick = onToggle)
                        .semantics { contentDescription = if (ui.playing) "Pause voice note" else "Play voice note" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (ui.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = c.onAccent,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .semantics { contentDescription = "Voice note position" }
                    .pointerInput(seekable, ui.durationMs) {
                        if (!seekable) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            dragging = (down.position.x / size.width).coerceIn(0f, 1f)
                            val ok = horizontalDrag(down.id) { ch ->
                                dragging = (ch.position.x / size.width).coerceIn(0f, 1f)
                                ch.consume()
                            }
                            val end = dragging
                            dragging = null
                            if (ok && end != null) onSeek(end)
                        }
                    },
            ) {
                val n = bars.size
                val gap = 2.dp.toPx()
                val bw = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
                for (i in 0 until n) {
                    val h = size.height * bars[i]
                    drawRoundRect(
                        color = if (i < frac * n) playedColor else restColor,
                        topLeft = Offset(i * (bw + gap), (size.height - h) / 2f),
                        size = Size(bw, h),
                        cornerRadius = CornerRadius(bw / 2f),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val time = if (ui.current) "${formatClock(ui.positionMs.toLong())} / ${formatClock(ui.durationMs.toLong())}" else "--:--"
                Text(time, style = chatMetaStyle(if (mine) c.outMeta else c.meta), modifier = Modifier.weight(1f))
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(c.ink.copy(alpha = 0.1f))
                        .clickable(onClick = onSpeed)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) { Text(speedLabel(ui.speed), style = chatMetaStyle(if (mine) c.outInk else c.ink)) }
            }
        }
    }
}
