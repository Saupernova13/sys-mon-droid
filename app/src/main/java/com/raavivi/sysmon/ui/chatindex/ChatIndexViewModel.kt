package com.raavivi.sysmon.ui.chatindex

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raavivi.sysmon.AppContainer

/** Owns the Chat index state over the container's chat-index repository. */
class ChatIndexViewModel(container: AppContainer) : ViewModel() {

    /** A one-shot snackbar text (e.g. "Card deleted"); the screen clears it once shown. */
    var message by mutableStateOf<String?>(null)

    val state = ChatIndexState(container.chatIndex, viewModelScope, onMessage = { message = it })

    init {
        state.start()
    }
}
