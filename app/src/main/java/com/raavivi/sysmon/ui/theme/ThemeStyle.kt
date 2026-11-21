package com.raavivi.sysmon.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Per-phone look: Classic (slate + mint, rounded), Instrument (the web panel's look) or Minimal
 * (Classic colours and type; only the bottom bar differs: Health, Find me, WhatsApp, More).
 */
enum class ThemeStyle(val key: String, val label: String) {
    Classic("classic", "Classic"),
    Instrument("instrument", "Instrument"),
    Minimal("minimal", "Minimal");

    companion object {
        fun fromKey(key: String?): ThemeStyle = entries.firstOrNull { it.key == key } ?: Classic
    }
}

/** The style [SysMonTheme] was composed with, for the nav and screens that behave per theme. */
val LocalThemeStyle = staticCompositionLocalOf { ThemeStyle.Classic }
