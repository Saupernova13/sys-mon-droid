package com.raavivi.sysmon.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.raavivi.sysmon.ui.common.ScreenHeader
import com.raavivi.sysmon.ui.common.rememberContainerViewModel
import kotlin.math.min

@Composable
fun ScreenShareScreen(onBack: () -> Unit) {
    val vm = rememberContainerViewModel { ScreenShareViewModel(it) }
    var scrollMode by remember { mutableStateOf(false) }
    var keyboardOpen by remember { mutableStateOf(false) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        ScreenHeader(
            title = "Screen",
            actions = {
                ConnDot(vm.state)
                IconButton(onClick = { keyboardOpen = !keyboardOpen }) {
                    Icon(Icons.Filled.Keyboard, contentDescription = "Keyboard")
                }
                IconButton(onClick = vm::connect) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Reconnect")
                }
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = !scrollMode,
                onClick = { scrollMode = false },
                leadingIcon = { Icon(Icons.Filled.TouchApp, contentDescription = null, modifier = Modifier.size(18.dp)) },
                label = { Text("Pointer") },
            )
            FilterChip(
                selected = scrollMode,
                onClick = { scrollMode = true },
                leadingIcon = { Icon(Icons.Filled.SwapVert, contentDescription = null, modifier = Modifier.size(18.dp)) },
                label = { Text("Scroll") },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "tap = click · hold = right-click",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
                .onSizeChanged { boxSize = it }
                .pointerInput(scrollMode, boxSize, vm.frameWidth, vm.frameHeight) {
                    detectTapGestures(
                        onTap = { p ->
                            norm(p, boxSize, vm.frameWidth, vm.frameHeight)?.let { vm.click(it.first, it.second) }
                        },
                        onLongPress = { p ->
                            norm(p, boxSize, vm.frameWidth, vm.frameHeight)?.let { vm.click(it.first, it.second, "right") }
                        },
                    )
                }
                .pointerInput(scrollMode, boxSize, vm.frameWidth, vm.frameHeight) {
                    var last = Offset.Zero
                    var accum = 0f
                    detectDragGestures(
                        onDragStart = { p ->
                            last = p
                            accum = 0f
                            if (!scrollMode) norm(p, boxSize, vm.frameWidth, vm.frameHeight)?.let { vm.mouseDown(it.first, it.second) }
                        },
                        onDrag = { change, drag ->
                            change.consume()
                            last = change.position
                            if (scrollMode) {
                                accum += drag.y
                                val step = (accum / SCROLL_STEP_PX).toInt()
                                if (step != 0) {
                                    accum -= step * SCROLL_STEP_PX
                                    norm(change.position, boxSize, vm.frameWidth, vm.frameHeight)?.let {
                                        vm.scroll(it.first, it.second, step.toFloat())
                                    }
                                }
                            } else {
                                norm(change.position, boxSize, vm.frameWidth, vm.frameHeight)?.let { vm.moveTo(it.first, it.second) }
                            }
                        },
                        onDragEnd = {
                            if (!scrollMode) norm(last, boxSize, vm.frameWidth, vm.frameHeight)?.let { vm.mouseUp(it.first, it.second) }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            val f = vm.frame
            if (f != null) {
                Image(
                    bitmap = f,
                    contentDescription = "Remote screen",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text(
                    when (vm.state) {
                        ScreenState.Connecting -> "Connecting…"
                        ScreenState.Disconnected -> "Disconnected — tap reconnect"
                        ScreenState.Connected -> "Waiting for frames…"
                    },
                    color = Color.White,
                )
            }
        }

        if (keyboardOpen) KeyboardBar(vm)
    }
}

@Composable
private fun KeyboardBar(vm: ScreenShareViewModel) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Type to send to PC…") },
                keyboardActions = KeyboardActions(onDone = { vm.typeString(text); text = "" }),
            )
            Spacer(Modifier.width(6.dp))
            IconButton(onClick = { vm.typeString(text); text = "" }) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send text")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyBtn("Enter") { vm.pressKey("Enter") }
            KeyBtn("Tab") { vm.pressKey("Tab") }
            KeyBtn("Esc") { vm.pressKey("Escape") }
            KeyBtn("Bksp") { vm.pressKey("Backspace") }
            KeyBtn("Win") { vm.pressKey("Meta") }
        }
    }
}

@Composable
private fun KeyBtn(label: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) {
        Text(label)
    }
}

@Composable
private fun ConnDot(state: ScreenState) {
    val color = when (state) {
        ScreenState.Connected -> MaterialTheme.colorScheme.primary
        ScreenState.Connecting -> MaterialTheme.colorScheme.tertiary
        ScreenState.Disconnected -> MaterialTheme.colorScheme.error
    }
    Box(
        Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
    Spacer(Modifier.width(8.dp))
}

private const val SCROLL_STEP_PX = 36f

/** Map a touch point (px in the box) to normalised 0..1 over the letterboxed image. */
private fun norm(p: Offset, box: IntSize, fw: Int, fh: Int): Pair<Float, Float>? {
    if (fw <= 0 || fh <= 0 || box.width <= 0 || box.height <= 0) return null
    val scale = min(box.width.toFloat() / fw, box.height.toFloat() / fh)
    val dispW = fw * scale
    val dispH = fh * scale
    val offX = (box.width - dispW) / 2f
    val offY = (box.height - dispH) / 2f
    val nx = ((p.x - offX) / dispW).coerceIn(0f, 1f)
    val ny = ((p.y - offY) / dispH).coerceIn(0f, 1f)
    return nx to ny
}
