package com.raavivi.sysmon.ui.whatsapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController

/**
 * Drops the keyboard and the field's focus when the user drags a scrollable list inside. Apply it to the
 * list itself (or to a parent of it); it consumes nothing, so the scroll goes on as usual.
 */
@Composable
fun Modifier.hideKeyboardOnScroll(): Modifier {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val connection = remember(keyboard, focus) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (dismissesKeyboard(available, source == NestedScrollSource.UserInput)) hideKeyboard(keyboard, focus)
                return Offset.Zero
            }
        }
    }
    return nestedScroll(connection)
}

/** Hide the keyboard and clear focus, so the field stops being the target. */
fun hideKeyboard(keyboard: SoftwareKeyboardController?, focus: FocusManager) {
    keyboard?.hide()
    focus.clearFocus()
}

/** A callback that hides the keyboard and clears focus (a tap on a result). */
@Composable
fun rememberHideKeyboard(): () -> Unit {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    return remember(keyboard, focus) { { hideKeyboard(keyboard, focus) } }
}
