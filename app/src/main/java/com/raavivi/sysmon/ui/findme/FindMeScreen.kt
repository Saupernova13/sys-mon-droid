package com.raavivi.sysmon.ui.findme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.raavivi.sysmon.LocalAppContainer
import com.raavivi.sysmon.core.model.FindMeDevice
import com.raavivi.sysmon.ui.common.ErrorBox
import com.raavivi.sysmon.ui.common.LoadingBox
import com.raavivi.sysmon.ui.common.ScreenHeader
import com.raavivi.sysmon.ui.common.SectionCard
import com.raavivi.sysmon.ui.common.color
import com.raavivi.sysmon.ui.common.rememberContainerViewModel
import com.raavivi.sysmon.ui.theme.SysMon
import kotlinx.coroutines.delay
import java.nio.ByteBuffer

/**
 * Find Me: paired devices with Locate / Ring controls over a slippy map of their
 * last fixes and the selected device's trail. Admin only, behind `features.findme`.
 */
@Composable
fun FindMeScreen(onBack: () -> Unit) {
    val vm = rememberContainerViewModel { FindMeViewModel(it) }
    val container = LocalAppContainer.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val palette = SysMon.palette

    // Poll only while visible: every 5 s with a pending command or the pair dialog, else every 30 s.
    LaunchedEffect(vm, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.refresh()
                vm.awaitNextPoll()
            }
        }
    }

    var now by remember { mutableDoubleStateOf(System.currentTimeMillis() / 1000.0) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis() / 1000.0
            delay(1000)
        }
    }
    vm.message?.let { msg -> LaunchedEffect(msg) { delay(3500); vm.message = null } }

    val mapState = remember { MapState() }
    var fitted by remember { mutableStateOf(false) }
    val fixPoints = vm.devices.mapNotNull { d -> d.lastFix?.let { LatLon(it.lat, it.lon) } }
    LaunchedEffect(fixPoints, mapState.sized) {
        if (!fitted && fixPoints.isNotEmpty() && mapState.fit(fixPoints)) fitted = true
    }

    var devicesExpanded by rememberSaveable { mutableStateOf(true) }
    var renaming by remember { mutableStateOf<FindMeDevice?>(null) }
    var interval by remember { mutableStateOf<FindMeDevice?>(null) }
    var removing by remember { mutableStateOf<FindMeDevice?>(null) }

    fun hue(index: Int): Color = palette.driveHues[index % palette.driveHues.size]

    fun pick(device: FindMeDevice) {
        vm.select(device.id)
        device.lastFix?.let { mapState.centerOn(LatLon(it.lat, it.lon)) }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = "Find Me", actions = {
            IconButton(onClick = vm::startPair, enabled = !vm.disabled) {
                Icon(Icons.Filled.Add, contentDescription = "Add device")
            }
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        })

        vm.message?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        when {
            !vm.loaded -> LoadingBox()
            vm.disabled || (vm.error != null && vm.devices.isEmpty()) ->
                ErrorBox(vm.error ?: "Find Me is off on the server")
            else -> Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                vm.error?.let {
                    Text(it, color = palette.sigWarn, style = MaterialTheme.typography.bodySmall)
                }
                if (!vm.pushConfigured) {
                    Text(
                        "No push key on the server: commands arrive at the next report.",
                        color = palette.quietInk,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                SectionCard(
                    title = "Devices",
                    trailing = {
                        IconButton(onClick = { devicesExpanded = !devicesExpanded }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                if (devicesExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = if (devicesExpanded) "Collapse devices" else "Expand devices",
                            )
                        }
                    },
                ) {
                    val maxList = (LocalConfiguration.current.screenHeightDp * 0.42f).dp
                    if (vm.devices.isEmpty()) {
                        Text(
                            "No devices paired yet. Use + to add one.",
                            color = palette.quietInk,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else if (!devicesExpanded) {
                        vm.selected?.let { sel ->
                            val idx = vm.devices.indexOf(sel)
                            Text(
                                "${sel.name}: ${formatAge(fixAge(sel, now))}",
                                color = hue(idx),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    } else {
                        Column(
                            Modifier
                                .heightIn(max = maxList)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            vm.devices.forEachIndexed { i, d ->
                                DeviceRow(
                                    device = d,
                                    hue = hue(i),
                                    selected = d.id == vm.selectedId,
                                    nowS = now,
                                    sending = vm.sending,
                                    onSelect = { pick(d) },
                                    onLocate = { vm.locate(d.id) },
                                    onRing = { vm.ring(d.id) },
                                    onStopRing = { vm.stopRing(d.id) },
                                    onRename = { renaming = d },
                                    onInterval = { interval = d },
                                    onRemove = { removing = d },
                                )
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TrailRange.entries.forEach { r ->
                        FilterChip(
                            selected = vm.range == r,
                            onClick = { vm.selectRange(r) },
                            label = { Text(r.label) },
                        )
                    }
                    vm.address?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // Street-level imagery of the selected device's last fix.
                vm.devices.firstOrNull { it.id == vm.selectedId }?.lastFix?.let { fix ->
                    val links = streetViewLinks(fix.lat, fix.lon)
                    val uri = LocalUriHandler.current
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { uri.openUri(links.google) }) { Text("Street View") }
                        TextButton(onClick = { uri.openUri(links.mapillary) }) { Text("Mapillary") }
                    }
                }

                SlippyMap(
                    state = mapState,
                    pins = vm.devices.mapIndexedNotNull { i, d ->
                        d.lastFix?.let { MapPin(d.id, LatLon(it.lat, it.lon), it.accuracyM, hue(i)) }
                    },
                    selectedId = vm.selectedId,
                    trail = vm.trail.map { LatLon(it.lat, it.lon) },
                    imageLoader = container.imageLoader,
                    tileUrl = { z, x, y -> container.api.findMeTileUrl(z, x, y) },
                    onSelect = { id -> vm.devices.firstOrNull { it.id == id }?.let(::pick) },
                    onFitAll = { mapState.fit(fixPoints) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(palette.cardRadius)),
                )
            }
        }
    }

    renaming?.let { d ->
        PromptDialog(
            title = "Rename device",
            label = "Name",
            initial = d.name,
            keyboardType = KeyboardType.Text,
            validate = { it.isNotBlank() },
            onDismiss = { renaming = null },
            onConfirm = { vm.rename(d.id, it); renaming = null },
        )
    }
    interval?.let { d ->
        PromptDialog(
            title = "Report interval",
            label = "Seconds (60 to 3600)",
            initial = d.intervalS.toString(),
            keyboardType = KeyboardType.Number,
            validate = { parseInterval(it) != null },
            onDismiss = { interval = null },
            onConfirm = { text -> parseInterval(text)?.let { vm.setInterval(d.id, it) }; interval = null },
        )
    }
    removing?.let { d ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${d.name}?") },
            text = { Text("The device is unpaired and its stored locations are deleted.") },
            confirmButton = {
                TextButton(onClick = { vm.remove(d.id); removing = null }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
    vm.pair?.let { p ->
        PairDialog(
            state = p,
            nowS = now,
            onCopy = { vm.message = "Link copied" },
            onRetry = vm::startPair,
            onClose = vm::closePair,
        )
    }
}

@Composable
private fun statusColor(phase: CommandPhase): Color {
    val p = SysMon.palette
    return when (phase) {
        CommandPhase.Pending, CommandPhase.Stopping -> p.quietInk
        CommandPhase.Done -> p.sigOk
        CommandPhase.Ringing, CommandPhase.Expired -> p.sigWarn
        CommandPhase.Failed -> p.sigCrit
    }
}

@Composable
private fun DeviceRow(
    device: FindMeDevice,
    hue: Color,
    selected: Boolean,
    nowS: Double,
    sending: Set<String>,
    onSelect: () -> Unit,
    onLocate: () -> Unit,
    onRing: () -> Unit,
    onStopRing: () -> Unit,
    onRename: () -> Unit,
    onInterval: () -> Unit,
    onRemove: () -> Unit,
) {
    val palette = SysMon.palette
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val locate = locateStatus(device, nowS)
    val ring = ringStatus(device, nowS)
    val age = fixAge(device, nowS)
    val ageState = fixAgeSeverity(age, device.intervalS)
    val batteryState = batterySeverity(device.batteryPct, device.charging)
    var menu by remember { mutableStateOf(false) }

    val locating = locate?.phase == CommandPhase.Pending || "${device.id}:locate" in sending
    val ringBusy = ring?.phase == CommandPhase.Pending || ring?.phase == CommandPhase.Stopping ||
        "${device.id}:ring" in sending || "${device.id}:stop_ring" in sending

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(minOf(palette.cardRadius, 8.dp)))
            .background(if (selected) palette.bgFor(hue) else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(hue),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                device.name,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(
                        text = { Text("Interval (${formatInterval(device.intervalS)})") },
                        onClick = { menu = false; onInterval() },
                    )
                    DropdownMenuItem(
                        text = { Text("Remove", color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onRemove() },
                    )
                }
            }
        }

        val fixLine = if (device.lastFix == null) {
            "No fix yet"
        } else {
            listOf("Fix ${formatAge(age)}", formatAccuracy(device.lastFix.accuracyM))
                .filter { it.isNotEmpty() }.joinToString(" ")
        }
        Text(
            fixLine,
            style = MaterialTheme.typography.bodySmall,
            color = ageState.color(ink),
            fontFamily = if (palette.monoNumbers) FontFamily.Monospace else null,
        )
        // Live status from servers that send it: Moving / Stationary since / Unreachable.
        if (device.status != null) {
            Text(
                statusText(device, nowS),
                style = MaterialTheme.typography.bodySmall,
                color = statusSeverity(device.status).color(ink),
            )
        }
        val battery = device.batteryPct?.let { "${it.toInt()}%" + if (device.charging == true) " charging" else "" }
        val batteryLine = listOfNotNull(
            battery?.let { "Battery $it" },
            device.network?.takeIf { it != "none" },
            if (device.network == "none") "offline" else null,
        ).joinToString(" · ")
        if (batteryLine.isNotEmpty()) {
            Text(
                batteryLine,
                style = MaterialTheme.typography.bodySmall,
                color = batteryState.color(ink),
                fontFamily = if (palette.monoNumbers) FontFamily.Monospace else null,
            )
        }

        Row(
            Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onLocate,
                enabled = !locating,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) { Text(if (locating) "Locating..." else "Locate") }
            if (ring?.phase == CommandPhase.Ringing) {
                OutlinedButton(
                    onClick = onStopRing,
                    enabled = !ringBusy,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) { Text("Stop ring", color = palette.sigWarn) }
            } else {
                OutlinedButton(
                    onClick = onRing,
                    enabled = !ringBusy,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) { Text(ring?.takeIf { it.phase == CommandPhase.Pending || it.phase == CommandPhase.Stopping }?.text ?: "Ring") }
            }
        }
        listOfNotNull(locate, ring).filter { it.phase != CommandPhase.Pending && it.phase != CommandPhase.Stopping }.forEach { s ->
            Text(
                s.text,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor(s.phase),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun PromptDialog(
    title: String,
    label: String,
    initial: String,
    keyboardType: KeyboardType,
    validate: (String) -> Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = validate(text)) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PairDialog(
    state: PairState,
    nowS: Double,
    onCopy: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val palette = SysMon.palette
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val pair = state.code
    val expired = pair != null && nowS >= pair.expiresAt

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Add device") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when {
                    state.loading -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    state.error != null -> Text(state.error, color = MaterialTheme.colorScheme.error)
                    pair != null -> {
                        Text(
                            "Enter this code in the Find Me app on the phone, or scan the QR.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            formatCode(pair.code),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 34.sp,
                            letterSpacing = 2.sp,
                            color = if (expired) palette.offline else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            if (expired) "Expired" else "Expires in ${countdown(pair.expiresAt, nowS)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (expired) palette.sigCrit else palette.quietInk,
                        )
                        if (pair.qrSvg.isNotBlank() && !expired) {
                            // Dark modules on a transparent SVG: back them with the light ink.
                            val request = remember(pair.qrSvg) {
                                ImageRequest.Builder(context)
                                    .data(ByteBuffer.wrap(pair.qrSvg.toByteArray()))
                                    .decoderFactory(SvgDecoder.Factory())
                                    .build()
                            }
                            Box(
                                Modifier
                                    .background(MaterialTheme.colorScheme.onSurface)
                                    .padding(8.dp),
                            ) {
                                AsyncImage(
                                    model = request,
                                    imageLoader = container.imageLoader,
                                    contentDescription = "Pairing QR code",
                                    modifier = Modifier.size(190.dp),
                                )
                            }
                        }
                        if (pair.link.isNotBlank()) {
                            Text(
                                pair.link,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString(pair.link))
                                onCopy()
                            }) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("Copy link")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (state.error != null || expired) {
                TextButton(onClick = onRetry) { Text("New code") }
            } else {
                TextButton(onClick = onClose) { Text("Done") }
            }
        },
        dismissButton = {
            if (state.error != null || expired) TextButton(onClick = onClose) { Text("Close") }
        },
    )
}
