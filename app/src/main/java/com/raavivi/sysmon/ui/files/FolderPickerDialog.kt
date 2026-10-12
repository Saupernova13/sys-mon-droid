package com.raavivi.sysmon.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.raavivi.sysmon.core.model.FsEntry
import com.raavivi.sysmon.ui.common.ErrorBox
import com.raavivi.sysmon.ui.common.LoadingBox

/**
 * Browse-and-pick destination for copy/move. It browses through the
 * view-model's picker state so the main explorer list never moves underneath it.
 */
@Composable
fun FolderPickerDialog(
    vm: FilesViewModel,
    entry: FsEntry,
    isMove: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val verb = if (isMove) "Move" else "Copy"
    var typing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.pickerOpen(vm.path) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.85f),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("$verb “${entry.name}” to", style = MaterialTheme.typography.titleLarge)

                if (typing) {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        label = { Text("Destination folder") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                    Row {
                        TextButton(onClick = { vm.pickerOpen(typed.trim()); typing = false }) { Text("Go") }
                        TextButton(onClick = { typing = false }) { Text("Browse") }
                    }
                } else {
                    Text(
                        vm.pickerPath.ifBlank { "Drives" },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        vm.drives.forEach { d ->
                            AssistChip(onClick = { vm.pickerOpen(d.mountpoint) }, label = { Text(d.mountpoint) })
                        }
                        vm.favorites.forEach { f ->
                            AssistChip(
                                onClick = { vm.pickerOpen(f.path) },
                                label = { Text(f.name.ifBlank { f.path }) },
                                leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                            )
                        }
                    }
                    TextButton(onClick = { typed = vm.pickerPath; typing = true }) { Text("Type a path") }
                }

                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    when {
                        vm.pickerLoading -> LoadingBox()
                        vm.pickerError != null -> ErrorBox(vm.pickerError!!, onRetry = { vm.pickerOpen(vm.pickerPath) })
                        else -> LazyColumn(Modifier.fillMaxSize()) {
                            vm.pickerParent?.let { up ->
                                item {
                                    PickerRow("..", Icons.Filled.ArrowUpward) { vm.pickerOpen(up) }
                                }
                            }
                            items(vm.pickerEntries.filter { it.isDir }, key = { it.path }) { dir ->
                                PickerRow(dir.name, Icons.Filled.Folder) { vm.pickerOpen(dir.path) }
                            }
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(
                        onClick = { onConfirm(vm.pickerPath) },
                        enabled = !typing && !vm.pickerLoading && canTransferTo(entry.path, vm.pickerPath, isMove),
                    ) { Text("$verb here") }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(label, modifier = Modifier.padding(start = 12.dp))
    }
}
