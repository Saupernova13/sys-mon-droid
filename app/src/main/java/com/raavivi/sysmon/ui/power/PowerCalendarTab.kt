package com.raavivi.sysmon.ui.power

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raavivi.sysmon.core.model.PowerCalendarDay
import com.raavivi.sysmon.core.model.PowerCalendarResponse
import com.raavivi.sysmon.ui.common.SectionCard
import com.raavivi.sysmon.ui.common.formatWatts
import com.raavivi.sysmon.ui.theme.PowerColor
import java.util.Calendar
import java.util.Locale

private val WEEKDAYS = listOf("M", "T", "W", "T", "F", "S", "S")

/**
 * Month grid of recorded power use, one cell per day: a 24-hour sparkline, the
 * day's cost, and a tap target that reveals the exact figures underneath.
 *
 * Every sparkline in a month shares one vertical scale — auto-scaling each cell
 * would make a quiet day look identical to a heavy one. Hours the plug was
 * offline arrive as nulls and break the line into separate runs rather than
 * being drawn as zero.
 */
@Composable
fun PowerCalendarTab(vm: PowerCalendarViewModel) {
    var selectedDate by remember { mutableStateOf<String?>(null) }
    val data = vm.data

    SectionCard(
        title = monthLabel(vm.year, vm.month),
        accent = PowerColor,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.step(-1) }) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous month")
                }
                IconButton(onClick = { vm.step(1) }, enabled = !vm.atCurrentMonth) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = "Next month")
                }
            }
        },
    ) {
        if (vm.loading && data == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = PowerColor)
            Spacer(Modifier.height(8.dp))
        }
        vm.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
        }

        if (data == null) {
            if (!vm.loading && vm.error == null) MutedText("No calendar data yet.")
            return@SectionCard
        }

        MonthGrid(
            data = data,
            year = vm.year,
            month = vm.month,
            selectedDate = selectedDate,
            onSelect = { date -> selectedDate = if (date == selectedDate) null else date },
        )

        Spacer(Modifier.height(10.dp))
        SelectedDayLine(data, selectedDate)
        Spacer(Modifier.height(6.dp))
        MonthSummary(data)
    }
}

@Composable
private fun MonthGrid(
    data: PowerCalendarResponse,
    year: Int,
    month: Int,
    selectedDate: String?,
    onSelect: (String) -> Unit,
) {
    val byDate = remember(data) { data.days.associateBy { it.date } }
    // One scale for the whole month so cells are comparable. The floor keeps a
    // month of trickle draw from rendering as dramatic spikes.
    val maxW = remember(data) {
        maxOf(data.days.flatMap { it.hours }.filterNotNull().maxOrNull() ?: 0.0, 50.0)
    }

    val cal = remember(year, month) {
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, 1)
        }
    }
    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    // Calendar.DAY_OF_WEEK is 1=Sunday..7=Saturday; shift so Monday leads.
    val leadingBlanks = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
    val today = remember { Calendar.getInstance() }
    val todayDate = "%04d-%02d-%02d".format(
        today.get(Calendar.YEAR),
        today.get(Calendar.MONTH) + 1,
        today.get(Calendar.DAY_OF_MONTH),
    )

    Row(Modifier.fillMaxWidth()) {
        WEEKDAYS.forEach { w ->
            Text(
                w,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(4.dp))

    val cells = leadingBlanks + daysInMonth
    val weeks = (cells + 6) / 7
    for (week in 0 until weeks) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (slot in 0 until 7) {
                val index = week * 7 + slot
                val dayNum = index - leadingBlanks + 1
                if (dayNum !in 1..daysInMonth) {
                    Spacer(Modifier.weight(1f))
                    continue
                }
                val date = "%04d-%02d-%02d".format(year, month, dayNum)
                DayCell(
                    modifier = Modifier.weight(1f),
                    dayNum = dayNum,
                    day = byDate[date],
                    maxW = maxW,
                    currency = data.currency,
                    isToday = date == todayDate,
                    isSelected = date == selectedDate,
                    onClick = { onSelect(date) },
                )
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun DayCell(
    modifier: Modifier,
    dayNum: Int,
    day: PowerCalendarDay?,
    maxW: Double,
    currency: String,
    isToday: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val hasData = day != null && day.kwh > 0
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier
            .aspectRatio(0.78f)
            .clip(shape)
            .background(
                if (isSelected) PowerColor.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            )
            .then(
                if (isToday) Modifier.border(1.dp, PowerColor.copy(alpha = 0.7f), shape)
                else Modifier,
            )
            .clickable(enabled = hasData, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            dayNum.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            color = if (hasData) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (day == null || !hasData) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    "—",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }
        DaySparkline(
            hours = day.hours,
            maxW = maxW,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        Text(
            compactMoney(currency, day.cost),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            color = PowerColor,
            maxLines = 1,
        )
    }
}

/**
 * A day's 24 hourly averages as a filled area anchored to the baseline. Null
 * hours (plug offline) split the path so a gap never reads as a zero reading.
 */
@Composable
private fun DaySparkline(hours: List<Double?>, maxW: Double, modifier: Modifier) {
    Canvas(modifier) {
        if (hours.isEmpty()) return@Canvas
        val span = maxW.coerceAtLeast(1.0)
        val stepX = if (hours.size > 1) size.width / (hours.size - 1) else size.width
        val baseline = size.height

        var run = mutableListOf<Pair<Float, Float>>()
        fun flush() {
            if (run.size >= 2) {
                val area = Path().apply {
                    moveTo(run.first().first, baseline)
                    run.forEach { (x, y) -> lineTo(x, y) }
                    lineTo(run.last().first, baseline)
                    close()
                }
                drawPath(area, PowerColor.copy(alpha = 0.35f))
                val line = Path().apply {
                    moveTo(run.first().first, run.first().second)
                    run.drop(1).forEach { (x, y) -> lineTo(x, y) }
                }
                drawPath(line, PowerColor, style = Stroke(width = 1.2f))
            }
            run = mutableListOf()
        }

        hours.forEachIndexed { i, v ->
            if (v == null) {
                flush()
            } else {
                val y = baseline - (baseline * (v / span).coerceIn(0.0, 1.0)).toFloat()
                run.add(i * stepX to y)
            }
        }
        flush()
    }
}

@Composable
private fun SelectedDayLine(data: PowerCalendarResponse, selectedDate: String?) {
    val day = data.days.firstOrNull { it.date == selectedDate }
    if (day == null) {
        MutedText(
            if (data.days.isEmpty()) "No power recorded this month."
            else "Tap a day for its exact usage.",
        )
        return
    }
    val peak = day.hours.filterNotNull().maxOrNull() ?: 0.0
    Text(
        "${day.date} · ${fmtKwh(day.kwh)} · ${money(data.currency, day.cost)} · peak ${formatWatts(peak)}",
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun MonthSummary(data: PowerCalendarResponse) {
    val recorded = data.days.size
    Text(
        if (recorded == 0) {
            "No days recorded."
        } else {
            "$recorded day${if (recorded == 1) "" else "s"} recorded · " +
                "${fmtKwh(data.monthKwh)} · ${money(data.currency, data.monthCost)}"
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MutedText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun monthLabel(year: Int, month: Int): String {
    val cal = Calendar.getInstance().apply {
        clear()
        set(year, month - 1, 1)
    }
    return java.text.SimpleDateFormat("LLLL yyyy", Locale.getDefault()).format(cal.time)
}

private fun fmtKwh(kwh: Double): String = String.format(Locale.US, "%.2f kWh", kwh)

private fun money(currency: String, amount: Double): String =
    currency + String.format(Locale.US, "%.2f", amount)

/** Cell-sized money: a 7-column grid has no room for cents, so only sub-R10
 *  amounts keep a decimal (where dropping it would round most days to "R0"). */
private fun compactMoney(currency: String, amount: Double): String =
    currency + String.format(Locale.US, if (amount >= 10) "%.0f" else "%.1f", amount)
