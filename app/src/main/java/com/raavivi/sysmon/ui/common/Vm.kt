package com.raavivi.sysmon.ui.common

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.raavivi.sysmon.AppContainer
import com.raavivi.sysmon.LocalAppContainer

/** Builds a [ViewModel] from the [AppContainer], scoped to the current nav entry. */
@Composable
inline fun <reified VM : ViewModel> rememberContainerViewModel(
    crossinline create: (AppContainer) -> VM,
): VM {
    val container = LocalAppContainer.current
    return viewModel(factory = viewModelFactory { initializer { create(container) } })
}
