package com.raavivi.sysmon.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.graphics.vector.ImageVector

/** Top-level destinations shown in the bottom navigation bar. */
enum class TopDest(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Dashboard("dashboard", "Stats", Icons.Filled.Dashboard),
    Processes("processes", "Procs", Icons.Filled.Memory),
    Files("files", "Files", Icons.Filled.Folder),
    ModelLog("modellog", "Models", Icons.Filled.Psychology),
    More("more", "More", Icons.Filled.Tune),
}

object Routes {
    const val TEXT_EDITOR = "editor"
    const val PDF_VIEWER = "pdf"
    const val TERMINAL = "terminal"
    const val SCREEN = "screen"
    const val WHATSAPP = "whatsapp"
    const val POWER = "power"
    const val SETTINGS = "settings"
    const val PLUG_ALERTS = "plug-alerts"
}
