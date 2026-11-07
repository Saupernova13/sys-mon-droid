package com.raavivi.sysmon.ui.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** What the compress form produced. */
data class CompressRequest(val dest: String, val format: String, val password: String?)

/**
 * Compress form: archive name, destination folder, format and an optional password.
 * [pickFolder] opens the folder picker and reports the chosen folder via its callback.
 * tar has no encryption, so choosing it disables and clears the password fields.
 */
@Composable
fun CompressDialog(
    paths: List<String>,
    cwd: String,
    sep: String,
    formats: List<String>,
    pickFolder: (current: String, onPicked: (String) -> Unit) -> Unit,
    onConfirm: (CompressRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    var format by remember { mutableStateOf(if ("zip" in formats) "zip" else formats.first()) }
    var name by remember { mutableStateOf(defaultArchiveName(paths, cwd, format)) }
    var folder by remember { mutableStateOf(cwd) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    val tar = format == "tar"
    val error = compressFormError(name, if (tar) "" else password, if (tar) "" else confirm)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Compress ${if (paths.size == 1) baseName(paths.first()) else "${paths.size} items"}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Archive name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Save in", style = MaterialTheme.typography.labelMedium)
                OutlinedButton(onClick = { pickFolder(folder) { folder = it } }, modifier = Modifier.fillMaxWidth()) {
                    Text(folder.ifBlank { "Choose a folder" }, fontFamily = FontFamily.Monospace, maxLines = 1)
                }
                Text("Format", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    formats.forEach { f ->
                        FilterChip(
                            selected = f == format,
                            onClick = {
                                format = f
                                name = withArchiveExt(name, f)
                                if (f == "tar") { password = ""; confirm = "" }
                            },
                            label = { Text(f) },
                        )
                    }
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password (optional)") },
                    singleLine = true,
                    enabled = !tar,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text("Confirm password") },
                    singleLine = true,
                    enabled = !tar,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showError && error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (error != null || folder.isBlank()) {
                    showError = true
                } else {
                    onConfirm(
                        CompressRequest(
                            dest = joinPath(folder, ensureArchiveExt(name, format), sep),
                            format = format,
                            password = password.takeIf { !tar && it.isNotEmpty() },
                        ),
                    )
                }
            }) { Text("Compress") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Password prompt for opening or extracting an encrypted archive. */
@Composable
fun PasswordDialog(message: String, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(message) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
        },
        confirmButton = { TextButton(onClick = { onSubmit(text) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
