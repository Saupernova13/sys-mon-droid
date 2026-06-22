package com.raavivi.sysmon.ui.files

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raavivi.sysmon.AppContainer
import com.raavivi.sysmon.core.model.FsWriteBody
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.safeCall
import com.raavivi.sysmon.ui.common.ErrorBox
import com.raavivi.sysmon.ui.common.LoadingBox
import com.raavivi.sysmon.ui.common.ScreenHeader
import com.raavivi.sysmon.ui.common.rememberContainerViewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

class TextEditorViewModel(
    private val container: AppContainer,
    private val path: String,
) : ViewModel() {
    var content by mutableStateOf("")
    var loading by mutableStateOf(true)
        private set
    var saving by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var status by mutableStateOf<String?>(null)
    var dirty by mutableStateOf(false)
        private set

    init { load() }

    fun load() {
        loading = true
        error = null
        viewModelScope.launch {
            when (val r = safeCall { container.api.api.fsRead(path) }) {
                is ApiResult.Ok -> { content = r.value.content; dirty = false }
                is ApiResult.Err -> error = r.message
            }
            loading = false
        }
    }

    fun onChange(s: String) { content = s; dirty = true; status = null }

    fun save() {
        if (saving) return
        saving = true
        viewModelScope.launch {
            status = when (val r = safeCall { container.api.api.fsWrite(FsWriteBody(path, content)) }) {
                is ApiResult.Ok -> { dirty = false; "Saved" }
                is ApiResult.Err -> "Save failed: ${r.message}"
            }
            saving = false
        }
    }
}

@Composable
fun TextEditorScreen(path: String, onBack: () -> Unit) {
    val vm = rememberContainerViewModel { TextEditorViewModel(it, path) }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        ScreenHeader(
            title = path.substringAfterLast('\\').substringAfterLast('/'),
            actions = {
                vm.status?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = vm::save, enabled = !vm.saving && vm.dirty) {
                    Icon(Icons.Filled.Save, contentDescription = "Save")
                }
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )

        Box(Modifier.fillMaxSize()) {
            when {
                vm.loading -> LoadingBox()
                vm.error != null -> ErrorBox(vm.error!!, onRetry = vm::load)
                else -> TextField(
                    value = vm.content,
                    onValueChange = vm::onChange,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    colors = TextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            }
        }
    }
}
