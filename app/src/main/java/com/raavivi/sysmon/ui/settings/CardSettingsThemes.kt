package com.raavivi.sysmon.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.raavivi.sysmon.ui.common.SectionCard

/**
 * The themes the card writer tags each topic with: one chip per theme (tap to remove, with Undo in the
 * snackbar), a field to add one and, when the list differs from the built-in one, a button to go back to it.
 * Part of the draft: Save is the sheet's own.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ThemesSection(st: CardSettingsState, draft: CardDraft) {
    SectionCard(title = "Themes") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "The card writer tags each topic with the themes that fit, from this list. Cards already written keep theirs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (name in draft.facets) {
                    InputChip(
                        selected = false,
                        onClick = { st.removeTheme(name) },
                        label = { Text(name, maxLines = 1) },
                        trailingIcon = {
                            Icon(Icons.Filled.Close, contentDescription = "Remove $name", modifier = Modifier.size(18.dp))
                        },
                    )
                }
            }
            AddThemeRow(st)
            if (st.offersBuiltIn) {
                TextButton(onClick = st::useBuiltInThemes, modifier = Modifier.heightIn(min = 48.dp)) { Text("Use the built-in list") }
            }
        }
    }
}

/** "Add a theme" with Done on the keyboard, an Add button beside it, and the refusal under the field. */
@Composable
private fun AddThemeRow(st: CardSettingsState) {
    var text by remember { mutableStateOf("") }
    val add = { if (st.addTheme(text)) text = "" }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                st.clearThemeError()
            },
            label = { Text("Add a theme") },
            singleLine = true,
            isError = st.themeError != null,
            supportingText = st.themeError?.let { error -> { Text(error) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            modifier = Modifier.weight(1f),
        )
        Button(onClick = { add() }, modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp)) { Text("Add") }
    }
}
