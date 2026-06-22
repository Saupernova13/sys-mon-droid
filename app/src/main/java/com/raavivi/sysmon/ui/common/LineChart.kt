package com.raavivi.sysmon.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One named line on the chart. [points] are percentages 0..100, oldest -> newest. */
data class ChartSeries(
    val label: String,
    val color: Color,
    val points: List<Float>,
)

/**
 * A lightweight multi-series line chart drawn with Canvas (no external chart lib).
 * The Y axis is fixed 0..100 to plot utilisation percentages.
 */
@Composable
fun PercentLineChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    height: Dp = 140.dp,
    gridColor: Color = Color(0x1FFFFFFF),
    fillUnderFirst: Boolean = true,
) {
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height),
    ) {
        drawGrid(gridColor)
        series.forEachIndexed { index, s ->
            drawSeries(s, fillUnder = fillUnderFirst && index == 0)
        }
    }
}

private fun DrawScope.drawGrid(gridColor: Color) {
    val rows = 4
    for (i in 0..rows) {
        val y = size.height * i / rows
        drawLine(
            color = gridColor,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
        )
    }
}

private fun DrawScope.drawSeries(s: ChartSeries, fillUnder: Boolean) {
    val pts = s.points
    if (pts.size < 2) return
    val maxIndex = pts.size - 1
    fun x(i: Int) = size.width * i / maxIndex
    fun y(v: Float) = size.height * (1f - (v.coerceIn(0f, 100f) / 100f))

    val line = Path().apply {
        moveTo(x(0), y(pts[0]))
        for (i in 1..maxIndex) lineTo(x(i), y(pts[i]))
    }

    if (fillUnder) {
        val fill = Path().apply {
            addPath(line)
            lineTo(x(maxIndex), size.height)
            lineTo(x(0), size.height)
            close()
        }
        drawPath(
            path = fill,
            brush = Brush.verticalGradient(
                colors = listOf(s.color.copy(alpha = 0.30f), s.color.copy(alpha = 0f)),
            ),
        )
    }

    drawPath(
        path = line,
        color = s.color,
        style = Stroke(width = 3f),
    )
}
