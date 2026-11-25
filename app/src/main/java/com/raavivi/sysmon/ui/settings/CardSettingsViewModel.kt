package com.raavivi.sysmon.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raavivi.sysmon.AppContainer

/** Owns the Topic cards edit state (settings read, draft, picker, save) over the container's WhatsApp repository. */
class CardSettingsViewModel(container: AppContainer) : ViewModel() {

    /** A one-shot snackbar text (e.g. "Card settings saved"); the screen clears it once shown. */
    var message by mutableStateOf<String?>(null)

    val state = CardSettingsState(container.whatsapp, viewModelScope, onMessage = { message = it })

    init {
        state.load()
    }
}
