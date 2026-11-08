package com.raavivi.sysmon.ui.common.snake

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.raavivi.sysmon.ui.theme.SysMon
import kotlin.random.Random

/**
 * A snake that plays itself, as a transfer-progress animation (port of the web
 * snake-progress.js). [progress] is 0..1, or null for a wait with no percentage: the
 * snake then keeps growing toward a ceiling (snake-busy.js). [color] is the body
 * colour while running; Done and Failed use the success and critical tokens.
 * With animations disabled system-wide the snake is drawn still.
 */
@Composable
fun SnakeProgress(
    progress: Float?,
    status: SnakeStatus,
    modifier: Modifier = Modifier,
    color: Color = SysMon.palette.idDisk,
) {
    val palette = SysMon.palette
    val density = LocalDensity.current
    val context = LocalContext.current
    val reduced = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val cellPx = with(density) { SnakeConfig.CELL_DP.dp.toPx() }
    val cols = if (size.width > 0) SnakeConfig.cellsFor(size.width.toFloat(), cellPx) else 0
    val rows = if (size.height > 0) SnakeConfig.cellsFor(size.height.toFloat(), cellPx) else 0
    val engine = remember(cols, rows) {
        if (cols == 0 || rows == 0) null else SnakeEngine(cols, rows, Random(System.nanoTime()))
    }
    val frame = remember { mutableLongStateOf(0L) }

    engine?.let {
        it.busy = progress == null
        SideEffect { if (progress != null) it.setProgress(progress) }
    }

    LaunchedEffect(engine, status, reduced, progress.takeIf { reduced }) {
        val e = engine ?: return@LaunchedEffect
        if (reduced) {
            e.layoutStatic()
            frame.longValue++
            return@LaunchedEffect
        }
        var last = -1L
        frame.longValue++
        while (status == SnakeStatus.Running || e.zooming) {
            withFrameMillis { now ->
                val dt = if (last < 0) 0L else now - last
                last = now
                e.advance(dt, status == SnakeStatus.Running)
                frame.longValue++
            }
        }
    }

    val bodyColor = when (status) {
        SnakeStatus.Running -> color
        SnakeStatus.Done -> palette.sigOk
        SnakeStatus.Failed -> palette.sigCrit
        SnakeStatus.Cancelled -> palette.quietInk
    }
    val foodColor = palette.nominalInk
    val trackColor = palette.track
    val lineColor = palette.hairline
    val rounded = !palette.isInstrument

    Canvas(modifier.clipToBounds().onSizeChanged { size = it }) {
        frame.longValue  // redraw whenever the engine advanced
        drawRect(trackColor)
        val e = engine ?: return@Canvas
        val s = e.scale
        val w = e.cols * cellPx
        val h = e.rows * cellPx
        val ox = (this.size.width - w) / 2f
        val oy = (this.size.height - h) / 2f
        scale(s, s, pivot = Offset(this.size.width / 2f, this.size.height / 2f)) {
            translate(ox, oy) {
                if (s < 2f) {
                    // Fades in as the camera settles, so the doubled spacing at the
                    // start of a pull-back does not read as a jump.
                    val a = (2f - s) * 0.5f
                    val stroke = 1f / s
                    for (x in 1 until e.cols) {
                        drawLine(lineColor, Offset(x * cellPx, 0f), Offset(x * cellPx, h), stroke, alpha = a)
                    }
                    for (y in 1 until e.rows) {
                        drawLine(lineColor, Offset(0f, y * cellPx), Offset(w, y * cellPx), stroke, alpha = a)
                    }
                }
                drawRect(lineColor, Offset.Zero, Size(w, h), style = Stroke(1f / s))
                val gap = 0.1f * cellPx
                val inner = Size(cellPx - 2 * gap, cellPx - 2 * gap)
                val radius = if (rounded) CornerRadius(1.dp.toPx()) else CornerRadius.Zero
                for (i in e.length - 1 downTo 0) {
                    val c = e.cellAt(i)
                    drawRoundRect(
                        bodyColor,
                        Offset(e.cellX(c) * cellPx + gap, e.cellY(c) * cellPx + gap),
                        inner,
                        radius,
                    )
                }
                if (e.food >= 0) {
                    val f = cellPx * 0.2f
                    drawRoundRect(
                        foodColor,
                        Offset(e.cellX(e.food) * cellPx + f, e.cellY(e.food) * cellPx + f),
                        Size(cellPx - 2 * f, cellPx - 2 * f),
                        radius,
                    )
                }
            }
        }
    }
}
