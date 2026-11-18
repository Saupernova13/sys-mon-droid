package com.raavivi.sysmon.ui.whatsapp

import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.raavivi.sysmon.ui.theme.BgDark
import com.raavivi.sysmon.ui.theme.Mint
import com.raavivi.sysmon.ui.theme.OnSurfaceDark
import com.raavivi.sysmon.ui.theme.SurfaceVariantDark
import com.raavivi.sysmon.ui.theme.SysMon
import com.raavivi.sysmon.ui.theme.ThemeStyle
import kotlin.math.min

/**
 * Colour tokens of the WhatsApp conversation surface (docs: wa-chat-design-spec, section 1). Instrument
 * carries the spec's values; Classic shifts the neutrals to its blue-slate surfaces and uses Mint.
 */
@Immutable
data class ChatColors(
    val wall: Color,
    val wallLift: Color,
    val inBg: Color,
    val outBg: Color,
    val ink: Color,
    val outInk: Color,
    val meta: Color,
    val outMeta: Color,
    val accent: Color,
    val onAccent: Color,
    val outLink: Color,
    val pill: Color,
    val pillInk: Color,
    val hairline: Color,
    val senders: List<Color>,
) {
    fun senderColor(jid: String?): Color = senders[waSenderColorIndex(jid)]
}

private val SenderColors: List<Color> = WA_SENDER_COLORS.map { Color(0xFF000000 or it) }

val InstrumentChatColors = ChatColors(
    wall = Color(0xFF0B0C0B),
    wallLift = Color(0xFF121311),
    inBg = Color(0xFF1D1E1C),
    outBg = Color(0xFF1E4A3D),
    ink = Color(0xFFECE9E2),
    outInk = Color(0xFFE9F2EC),
    meta = Color(0xFFECE9E2).copy(alpha = 0.55f),
    outMeta = Color(0xFFE9F2EC).copy(alpha = 0.6f),
    accent = Color(0xFF3FBF8F),
    onAccent = Color(0xFF06150F),
    outLink = Color(0xFF8FE3C4),
    pill = Color(0xFF1D1E1C).copy(alpha = 0.86f),
    pillInk = Color(0xFFECE9E2),
    hairline = Color(0xFF35342F),
    senders = SenderColors,
)

val ClassicChatColors = ChatColors(
    wall = BgDark,
    wallLift = Color(0xFF151B23),
    inBg = SurfaceVariantDark,
    outBg = Color(0xFF1B4A47),
    ink = OnSurfaceDark,
    outInk = Color(0xFFE9F5F1),
    meta = OnSurfaceDark.copy(alpha = 0.55f),
    outMeta = Color(0xFFE9F5F1).copy(alpha = 0.6f),
    accent = Mint,
    onAccent = BgDark,
    outLink = Color(0xFF8FEBC4),
    pill = SurfaceVariantDark.copy(alpha = 0.86f),
    pillInk = OnSurfaceDark,
    hairline = Color(0xFF2C3542),
    senders = SenderColors,
)

@Composable
fun rememberChatColors(): ChatColors =
    if (SysMon.palette.style == ThemeStyle.Instrument) InstrumentChatColors else ClassicChatColors

/** System "remove animations" / animator-duration-scale 0: springs and transforms drop to plain fades. */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

// -- wallpaper ----------------------------------------------------------------

/** One mark of the wallpaper tile: x, y in the 120dp tile, radius in dp, ring or dot. */
private data class Mark(val x: Float, val y: Float, val r: Float, val ring: Boolean)

// Sparse small dots and rings; wraps cleanly because the tile repeats. An original pattern.
private val WallMarks = listOf(
    Mark(14f, 18f, 1.6f, false), Mark(52f, 9f, 3.2f, true), Mark(96f, 24f, 1.4f, false),
    Mark(30f, 56f, 3.6f, true), Mark(74f, 62f, 1.8f, false), Mark(110f, 70f, 3.0f, true),
    Mark(12f, 98f, 1.5f, false), Mark(58f, 104f, 3.4f, true), Mark(90f, 112f, 1.6f, false),
    Mark(40f, 84f, 1.2f, false),
)

private const val TILE_DP = 120f

/**
 * The conversation wall: a radial gradient (centre slightly lighter) under a faint tiled pattern of
 * dots and rings at ~3.5% ink. Built once per size via drawWithCache.
 */
fun Modifier.chatWallpaper(colors: ChatColors): Modifier = drawWithCache {
    val tilePx = (TILE_DP * density).toInt().coerceAtLeast(8)
    val tile = ImageBitmap(tilePx, tilePx)
    val canvas = Canvas(tile)
    CanvasDrawScope().draw(this, layoutDirection, canvas, Size(tilePx.toFloat(), tilePx.toFloat())) {
        val ink = colors.ink.copy(alpha = 0.035f)
        for (m in WallMarks) {
            val c = Offset(m.x * density, m.y * density)
            if (m.ring) drawCircle(ink, m.r * density, c, style = Stroke(width = 1.2f * density))
            else drawCircle(ink, m.r * density, c)
        }
    }
    val pattern = ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
    val radial = Brush.radialGradient(
        colors = listOf(colors.wallLift, colors.wall),
        center = Offset(size.width / 2f, size.height * 0.4f),
        radius = maxOf(size.width, size.height) * 0.85f,
    )
    onDrawBehind {
        drawRect(colors.wall)
        drawRect(radial)
        drawRect(pattern)
    }
}

// -- bubble shape -------------------------------------------------------------

/**
 * Chat bubble outline (spec section 3): radius 18dp, the sender-side corners that face a neighbour in
 * the group shrink to 6dp, and the first bubble of a group gets a small curved tail (~8dp wide) at its
 * top sender-side corner. The tail overflows the layout bounds into the gutter; used for both the fill
 * and the shadow.
 */
@Immutable
class BubbleShape(
    private val mine: Boolean,
    private val tail: Boolean,
    private val topNeighbour: Boolean,
    private val bottomNeighbour: Boolean,
    private val radius: Dp = 18.dp,
    private val neighbourRadius: Dp = 6.dp,
    private val tailWidth: Dp = 8.dp,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        val cap = min(w, h) / 2f
        val r = min(with(density) { radius.toPx() }, cap)
        val nr = min(with(density) { neighbourRadius.toPx() }, cap)
        val tw = with(density) { tailWidth.toPx() }
        // Built as an incoming bubble (sender on the left); an outgoing one is mirrored afterwards.
        val tl = if (tail) 0f else if (topNeighbour) nr else r
        val bl = if (bottomNeighbour) nr else r
        val path = Path().apply {
            if (tail) {
                val drop = min(with(density) { 12.dp.toPx() }, h - bl - 1f).coerceAtLeast(1f)
                moveTo(-tw, 0f)
                lineTo(w - r, 0f)
                arcTo(androidx.compose.ui.geometry.Rect(w - 2 * r, 0f, w, 2 * r), -90f, 90f, false)
                lineTo(w, h - r)
                arcTo(androidx.compose.ui.geometry.Rect(w - 2 * r, h - 2 * r, w, h), 0f, 90f, false)
                lineTo(bl, h)
                arcTo(androidx.compose.ui.geometry.Rect(0f, h - 2 * bl, 2 * bl, h), 90f, 90f, false)
                lineTo(0f, drop)
                cubicTo(0f, drop * 0.45f, -tw * 0.35f, drop * 0.08f, -tw, 0f)
                close()
            } else {
                moveTo(tl, 0f)
                lineTo(w - r, 0f)
                arcTo(androidx.compose.ui.geometry.Rect(w - 2 * r, 0f, w, 2 * r), -90f, 90f, false)
                lineTo(w, h - r)
                arcTo(androidx.compose.ui.geometry.Rect(w - 2 * r, h - 2 * r, w, h), 0f, 90f, false)
                lineTo(bl, h)
                arcTo(androidx.compose.ui.geometry.Rect(0f, h - 2 * bl, 2 * bl, h), 90f, 90f, false)
                lineTo(0f, tl)
                arcTo(androidx.compose.ui.geometry.Rect(0f, 0f, 2 * tl, 2 * tl), 180f, 90f, false)
                close()
            }
        }
        if (mine) {
            val m = Matrix().apply { translate(w, 0f); scale(-1f, 1f) }
            path.transform(m)
        }
        return Outline.Generic(path)
    }
}

/** Soft two-layer depth for a bubble: a convex shadow shape on older Androids (concave path shadows need API 30). */
fun Modifier.bubbleShadow(shape: Shape, convexFallback: Shape, mine: Boolean): Modifier = this.shadow(
    elevation = 2.dp,
    shape = if (Build.VERSION.SDK_INT >= 30) shape else convexFallback,
    clip = false,
    ambientColor = Color.Black.copy(alpha = 0.55f),
    spotColor = if (mine) Color(0xFF062820) else Color.Black,
)

// -- motion -------------------------------------------------------------------

/** Short, mild spring for pops (user feedback: the web version felt slow): damping 0.8, StiffnessMedium, no lingering overshoot. */
fun <T> popSpring() = spring<T>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)

/** Spring for positional moves: damping 0.85, StiffnessMedium. */
fun <T> moveSpring() = spring<T>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium)

/**
 * Scale to 0.98 while a pointer is down on this element (never consumes, so clicks and scrolling are
 * untouched). With [reduced] motion nothing moves. Call it inside composition.
 */
@Composable
fun Modifier.pressScale(reduced: Boolean = false): Modifier {
    var pressed by remember { mutableStateOf(false) }
    val scale = remember { Animatable(1f) }
    LaunchedEffect(pressed, reduced) {
        scale.animateTo(if (pressed && !reduced) 0.98f else 1f, spring(dampingRatio = 1f, stiffness = Spring.StiffnessHigh))
    }
    return this
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                pressed = true
                waitForUpOrCancellation()
                pressed = false
            }
        }
        .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}

/** Entrance for live / sent items: rises 10dp, scales .96 -> 1, fades; origin at the sender-side bottom corner. */
@Composable
fun Modifier.entrance(active: Boolean, mine: Boolean, reduced: Boolean): Modifier {
    val p = remember { Animatable(if (active) 0f else 1f) }
    LaunchedEffect(active) { if (active) p.animateTo(1f, tween(170, easing = FastOutSlowInEasing)) }
    return this.graphicsLayer {
        val t = p.value
        alpha = t.coerceIn(0f, 1f)
        if (!reduced) {
            translationY = (1f - t) * 10.dp.toPx()
            val s = 0.96f + 0.04f * t
            scaleX = s
            scaleY = s
            transformOrigin = TransformOrigin(if (mine) 1f else 0f, 1f)
        }
    }
}
