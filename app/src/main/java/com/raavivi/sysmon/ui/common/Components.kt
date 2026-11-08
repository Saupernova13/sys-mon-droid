package com.raavivi.sysmon.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.raavivi.sysmon.ui.theme.SysMon

/**
 * A titled section used for every dashboard / list screen. Classic renders a
 * rounded tonal card; Instrument renders a full-width band with a tinted header.
 * [state], when Caution or Warning, shows its label at the end of the Instrument band.
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    trailing: @Composable (() -> Unit)? = null,
    state: Severity? = null,
    content: @Composable () -> Unit,
) {
    val palette = SysMon.palette
    if (palette.bandHeaders) {
        val stateShown = state?.takeIf { it == Severity.Caution || it == Severity.Warning }
        Column(modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(palette.bgFor(accent))
                    .drawBehind {
                        drawRect(accent, size = Size(size.width, 2.dp.toPx()))
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title.uppercase(),
                    color = accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.16.em,
                    modifier = Modifier.weight(1f),
                )
                trailing?.invoke()
                if (stateShown != null) {
                    if (trailing != null) Spacer(Modifier.width(8.dp))
                    Text(
                        stateShown.label,
                        color = stateShown.color(accent),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(bottomStart = palette.cardRadius, bottomEnd = palette.cardRadius),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    content()
                }
            }
        }
        return
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(palette.cardRadius),
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .height(14.dp)
                            .width(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(accent),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                trailing?.invoke()
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/**
 * Horizontal usage bar. With [state] the fill follows [meterColor]; without it the
 * fill turns red past 90% as before. Instrument draws a square track with 10% ticks.
 */
@Composable
fun UsageBar(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    barHeight: Int = 10,
    state: Severity? = null,
) {
    val palette = SysMon.palette
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        label = "usage",
    )
    val target = when {
        state != null -> state.meterColor(color)
        fraction > 0.9f -> MaterialTheme.colorScheme.error
        else -> color
    }
    val barColor by animateColorAsState(targetValue = target, label = "barColor")
    if (palette.meterTicks) {
        val tickColor = palette.tick
        Box(
            modifier
                .fillMaxWidth()
                .height(barHeight.dp)
                .background(palette.track)
                .border(1.dp, palette.hairline),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(animated)
                    .fillMaxSize()
                    .background(barColor),
            )
            Canvas(Modifier.fillMaxSize()) {
                for (i in 1..9) {
                    val x = size.width * i / 10f
                    drawLine(tickColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                }
            }
        }
        return
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(barHeight.dp)
            .clip(RoundedCornerShape(barHeight.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .height(barHeight.dp)
                .clip(RoundedCornerShape(barHeight.dp))
                .background(barColor),
        )
    }
}

/**
 * A small label : value row used throughout detail views. [valueColor] overrides
 * the value ink (e.g. a severity colour); Unspecified keeps the default.
 */
@Composable
fun StatRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.Unspecified,
) {
    val palette = SysMon.palette
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (palette.monoNumbers) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (valueColor != Color.Unspecified) valueColor else MaterialTheme.colorScheme.onSurface,
            )
        } else {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = valueColor,
            )
        }
    }
}

/** The primary number of a card: monospace, coloured by [state] (see [color]). */
@Composable
fun Reading(
    text: String,
    state: Severity,
    identity: Color,
    modifier: Modifier = Modifier,
    large: Boolean = true,
) {
    val palette = SysMon.palette
    val base = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge
    val style = if (palette.isInstrument) {
        base.copy(
            fontSize = if (large) 34.sp else 20.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.04).em,
        )
    } else {
        base.copy(fontWeight = FontWeight.Bold)
    }
    Text(
        text,
        modifier = modifier,
        style = style,
        fontFamily = FontFamily.Monospace,
        color = state.color(identity),
    )
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun ErrorBox(message: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (onRetry != null) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}
