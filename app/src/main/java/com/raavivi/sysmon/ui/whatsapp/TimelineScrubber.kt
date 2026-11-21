package com.raavivi.sysmon.ui.whatsapp

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raavivi.sysmon.ui.theme.SysMon
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToInt

private val THUMB_HEIGHT = 6.dp

/**
 * Google-Photos-style scrubber: year labels, a thumb following the reading position, and a date
 * bubble while dragging. A drag (or tap) jumps to the chosen day on release; the thumb ignores
 * scroll while the finger is down. The root is wider than the track so the bubble fits to its left;
 * only the track itself takes touches.
 */
@Composable
internal fun TimelineScrubber(
    tl: WaTimeline,
    readingPos: Double,
    busy: Boolean,
    onJump: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** Track positions (0..1) to mark: days with search hits, the ends of a tapped conversation. */
    marks: List<Double> = emptyList(),
) {
    val density = LocalDensity.current
    var heightPx by remember { mutableIntStateOf(0) }
    var dragFrac by remember { mutableStateOf<Double?>(null) }
    val frac = dragFrac ?: readingPos
    val seg = dayAt(tl, frac)
    val thumbHPx = with(density) { THUMB_HEIGHT.toPx() }
    val thumbY = (frac * heightPx).toFloat().coerceIn(0f, (heightPx - thumbHPx).coerceAtLeast(0f))

    Box(modifier.width(160.dp).fillMaxHeight().onSizeChanged { heightPx = it.height }) {
        ScrubberTrack(
            tl = tl,
            heightPx = heightPx,
            thumbY = thumbY,
            busy = busy,
            marks = marks,
            description = fmtDay(seg.day),
            onDrag = { dragFrac = it },
            onJump = onJump,
            modifier = Modifier.align(Alignment.TopEnd),
        )
        if (dragFrac != null) {
            ScrubberDateBubble(
                label = fmtDay(seg.day),
                thumbY = thumbY,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}

/** The touch-sensitive rail: year labels, the thumb or a busy spinner, and the drag / tap gesture. */
@Composable
private fun ScrubberTrack(
    tl: WaTimeline,
    heightPx: Int,
    thumbY: Float,
    busy: Boolean,
    marks: List<Double>,
    description: String,
    onDrag: (Double?) -> Unit,
    onJump: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnJump by rememberUpdatedState(onJump)
    val currentOnDrag by rememberUpdatedState(onDrag)
    Box(
        modifier
            .width(28.dp)
            .fillMaxHeight()
            .semantics {
                contentDescription = "Timeline"
                stateDescription = description
            }
            .pointerInput(tl) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun fracOf(y: Float): Double =
                        if (size.height > 0) (y / size.height).toDouble() else 0.0
                    var last: Double? = fracOf(down.position.y)
                    currentOnDrag(last)
                    down.consume()
                    val completed = drag(down.id) { change ->
                        last = fracOf(change.position.y)
                        currentOnDrag(last)
                        change.consume()
                    }
                    val end = last
                    currentOnDrag(null)
                    if (completed && end != null) currentOnJump(dayAt(tl, end).firstTs)
                }
            },
    ) {
        // Rail and thumb hug the right edge so the year labels to their left stay clear.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(end = 6.dp)
                .width(2.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        if (marks.isNotEmpty()) {
            val markColor = MaterialTheme.colorScheme.tertiary
            Box(
                Modifier.matchParentSize().drawBehind {
                    val w = 10.dp.toPx()
                    val h = 2.dp.toPx()
                    for (m in marks) drawRect(markColor, Offset(size.width - 11.dp.toPx(), (m * size.height).toFloat()), Size(w, h))
                },
            )
        }
        ScrubberYearLabels(tl, heightPx)
        if (busy) {
            CircularProgressIndicator(
                Modifier.align(Alignment.TopEnd).offset { IntOffset(0, thumbY.roundToInt()) }.size(14.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, thumbY.roundToInt()) }
                    .width(14.dp)
                    .height(THUMB_HEIGHT)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/** Year labels at their track position, dropping any too close to the previous one. */
@Composable
private fun ScrubberYearLabels(tl: WaTimeline, heightPx: Int) {
    val density = LocalDensity.current
    val labels = remember(tl, heightPx) {
        val minGap = with(density) { 14.dp.toPx() }
        var lastY = -minGap
        tl.years.mapNotNull { y ->
            val py = (y.pos * heightPx).toFloat()
            if (py - lastY < minGap) null else { lastY = py; y.year to py }
        }
    }
    for ((year, py) in labels) {
        Text(
            year,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            maxLines = 1,
            softWrap = false,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.offset { IntOffset(0, py.roundToInt()) },
        )
    }
}

/** The date shown to the left of the thumb while dragging. */
@Composable
private fun ScrubberDateBubble(label: String, thumbY: Float, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    Surface(
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shape = RoundedCornerShape(if (SysMon.palette.isInstrument) 2.dp else 8.dp),
        modifier = modifier
            .padding(end = 32.dp)
            .offset { IntOffset(0, (thumbY - with(density) { 8.dp.toPx() }).coerceAtLeast(0f).roundToInt()) },
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/** Date picker limited to days that have messages; picking one jumps to that day's first message. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GoToDateDialog(tl: WaTimeline, initialDay: String, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val present = remember(tl) { tl.segments.mapTo(HashSet()) { it.day } }
    val selectable = remember(tl) {
        object : SelectableDates {
            // Picker millis are UTC midnight, so the UTC date is the calendar day the user tapped.
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().toString() in present

            override fun isSelectableYear(year: Int): Boolean =
                year in tl.first.day.take(4).toInt()..tl.last.day.take(4).toInt()
        }
    }
    val initialMillis = remember(initialDay) {
        runCatching { LocalDate.parse(initialDay).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
    }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initialMillis,
        initialDisplayedMonthMillis = initialMillis,
        yearRange = tl.first.day.take(4).toInt()..tl.last.day.take(4).toInt(),
        selectableDates = selectable,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val ymd = state.selectedDateMillis
                        ?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
                    val target = ymd?.let { firstDayOnOrAfter(tl, it) }
                    if (target != null) onPick(target.firstTs) else onDismiss()
                },
                enabled = state.selectedDateMillis != null,
            ) { Text("Go") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = state)
    }
}
