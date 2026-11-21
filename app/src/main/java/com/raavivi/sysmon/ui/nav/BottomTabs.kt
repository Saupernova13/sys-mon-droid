package com.raavivi.sysmon.ui.nav

import com.raavivi.sysmon.core.model.FeaturesResponse
import com.raavivi.sysmon.ui.theme.ThemeStyle

/**
 * Which tabs the bottom bar shows, in order. Classic and Instrument keep Stats, Procs, Files, WhatsApp, More;
 * Minimal swaps them for Health, Find me, WhatsApp, More. WhatsApp and Find me are admin-only and feature-gated
 * (their routers 404 when off), so their tab only exists when the server answers for them.
 */
fun bottomTabs(style: ThemeStyle, isAdmin: Boolean, features: FeaturesResponse?): List<TopDest> {
    val whatsapp = isAdmin && features?.whatsapp == true
    val findMe = isAdmin && features?.findme == true
    return if (style == ThemeStyle.Minimal) {
        buildList {
            add(TopDest.Health)
            if (findMe) add(TopDest.FindMe)
            if (whatsapp) add(TopDest.WhatsApp)
            add(TopDest.More)
        }
    } else {
        buildList {
            add(TopDest.Dashboard)
            add(TopDest.Processes)
            add(TopDest.Files)
            if (whatsapp) add(TopDest.WhatsApp)
            add(TopDest.More)
        }
    }
}

/** The first screen of the app, and where a stranded user lands. */
fun startTab(style: ThemeStyle): TopDest =
    if (style == ThemeStyle.Minimal) TopDest.Health else TopDest.Dashboard

/**
 * True when [route] is a tab route that the current bar does not show and that is not simply a pushed screen.
 * Health and Find me are tools opened from More outside Minimal; Stats, Procs and Files are screens opened from
 * More in Minimal. Anything else missing from the bar (WhatsApp off, Find me off in Minimal) strands the user.
 */
fun isStrandedOnTab(style: ThemeStyle, route: String?, visible: List<TopDest>): Boolean {
    val dest = TopDest.entries.firstOrNull { it.route == route } ?: return false
    if (dest in visible) return false
    val minimal = style == ThemeStyle.Minimal
    val pushedFromMore = if (minimal) {
        dest == TopDest.Dashboard || dest == TopDest.Processes || dest == TopDest.Files
    } else {
        dest == TopDest.Health || dest == TopDest.FindMe
    }
    return !pushedFromMore
}
