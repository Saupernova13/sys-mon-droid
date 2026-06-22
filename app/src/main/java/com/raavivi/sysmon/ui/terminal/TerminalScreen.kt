package com.raavivi.sysmon.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raavivi.sysmon.core.term.TerminalEmulator
import com.raavivi.sysmon.ui.common.ScreenHeader
import com.raavivi.sysmon.ui.common.rememberContainerViewModel

private val TermDefaultFg = Color(0xFFD7DAE0)
private val TermBg = Color(0xFF0B0E13)
private val TermFontSize = 12.sp

// Control byte sequences sent to the PTY.
private const val ESC = "\u001B"
private const val CTRL_C = "\u0003"
private const val CTRL_D = "\u0004"

@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val vm = rememberContainerViewModel { TerminalViewModel(it) }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        ScreenHeader(
            title = vm.title,
            actions = {
                ConnDot(vm.state)
                IconButton(onClick = vm::connect) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Reconnect")
                }
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )

        val measurer = rememberTextMeasurer()
        val cellStyle = remember { TextStyle(fontFamily = FontFamily.Monospace, fontSize = TermFontSize) }
        val sample = remember(measurer) { measurer.measure("MMMMMMMMMM", cellStyle) }
        val charW = sample.size.width / 10f
        val lineH = sample.size.height.toFloat()

        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(TermBg),
        ) {
            TerminalOutput(vm.lines, cellStyle)

            // Derive grid size from the available space and the cell metrics.
            val cols = if (charW > 0) (constraints.maxWidth / charW).toInt().coerceIn(20, 400) else 80
            val rows = if (lineH > 0) (constraints.maxHeight / lineH).toInt().coerceIn(6, 200) else 24
            LaunchedEffect(cols, rows) { vm.resize(cols, rows) }
        }

        KeyBar(vm)
        InputRow(onSubmit = { vm.sendInput(it + "\r") })
    }
}

@Composable
private fun TerminalOutput(lines: List<List<TerminalEmulator.TermSpan>>, style: TextStyle) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
    ) {
        itemsIndexed(lines) { _, spans ->
            Text(
                text = spans.toAnnotated(),
                style = style,
                color = TermDefaultFg,
                softWrap = false,
                maxLines = 1,
            )
        }
    }
}

private fun List<TerminalEmulator.TermSpan>.toAnnotated(): AnnotatedString {
    if (isEmpty()) return AnnotatedString(" ")
    return buildAnnotatedString {
        for (sp in this@toAnnotated) {
            withStyle(
                SpanStyle(
                    color = sp.fg?.let { Color(it) } ?: TermDefaultFg,
                    background = sp.bg?.let { Color(it) } ?: Color.Unspecified,
                    fontWeight = if (sp.bold) FontWeight.Bold else FontWeight.Normal,
                ),
            ) { append(sp.text) }
        }
    }
}

@Composable
private fun KeyBar(vm: TerminalViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeyChip("Esc") { vm.sendInput(ESC) }
        KeyChip("Tab") { vm.sendInput("\t") }
        KeyChip("^C") { vm.sendInput(CTRL_C) }
        KeyChip("^D") { vm.sendInput(CTRL_D) }
        KeyIconChip(Icons.Filled.KeyboardArrowUp, "Up") { vm.sendInput("$ESC[A") }
        KeyIconChip(Icons.Filled.KeyboardArrowDown, "Down") { vm.sendInput("$ESC[B") }
        KeyIconChip(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Left") { vm.sendInput("$ESC[D") }
        KeyIconChip(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Right") { vm.sendInput("$ESC[C") }
    }
}

@Composable
private fun KeyChip(label: String, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(label, fontFamily = FontFamily.Monospace) })
}

@Composable
private fun KeyIconChip(icon: ImageVector, desc: String, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Icon(icon, contentDescription = desc, modifier = Modifier.size(18.dp)) })
}

@Composable
private fun InputRow(onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("Type a command…") },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                onSubmit(text); text = ""
            }),
        )
        Spacer(Modifier.width(6.dp))
        IconButton(onClick = { onSubmit(text); text = "" }) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
        }
    }
}

@Composable
private fun ConnDot(state: TermState) {
    val color = when (state) {
        TermState.Connected -> MaterialTheme.colorScheme.primary
        TermState.Connecting -> MaterialTheme.colorScheme.tertiary
        TermState.Disconnected -> MaterialTheme.colorScheme.error
    }
    Box(
        Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
    Spacer(Modifier.width(8.dp))
}
