package com.raavivi.sysmon.ui.theme

/** Per-phone look: Classic (slate + mint, rounded) or Instrument (the web panel's look). */
enum class ThemeStyle(val key: String, val label: String) {
    Classic("classic", "Classic"),
    Instrument("instrument", "Instrument");

    companion object {
        fun fromKey(key: String?): ThemeStyle = entries.firstOrNull { it.key == key } ?: Classic
    }
}
