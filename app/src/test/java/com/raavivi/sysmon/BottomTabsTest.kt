package com.raavivi.sysmon

import com.raavivi.sysmon.core.model.FeaturesResponse
import com.raavivi.sysmon.ui.nav.Routes
import com.raavivi.sysmon.ui.nav.TopDest
import com.raavivi.sysmon.ui.nav.bottomTabs
import com.raavivi.sysmon.ui.nav.isStrandedOnTab
import com.raavivi.sysmon.ui.nav.startTab
import com.raavivi.sysmon.ui.theme.ThemeStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which tabs each theme shows and where a user is sent when the bar changes under them. */
class BottomTabsTest {

    private val all = FeaturesResponse(whatsapp = true, findme = true)

    @Test
    fun `classic keeps the original bar`() {
        val expected = listOf(TopDest.Dashboard, TopDest.Processes, TopDest.Files, TopDest.WhatsApp, TopDest.More)
        assertEquals(expected, bottomTabs(ThemeStyle.Classic, true, all))
        assertEquals(expected, bottomTabs(ThemeStyle.Instrument, true, all))
        assertEquals(TopDest.Dashboard, startTab(ThemeStyle.Classic))
    }

    @Test
    fun `classic hides whatsapp for viewers and when off`() {
        val noWa = listOf(TopDest.Dashboard, TopDest.Processes, TopDest.Files, TopDest.More)
        assertEquals(noWa, bottomTabs(ThemeStyle.Classic, false, all))
        assertEquals(noWa, bottomTabs(ThemeStyle.Classic, true, FeaturesResponse()))
        assertEquals(noWa, bottomTabs(ThemeStyle.Classic, true, null))
    }

    @Test
    fun `minimal bar is health, find me, whatsapp, more`() {
        assertEquals(
            listOf(TopDest.Health, TopDest.FindMe, TopDest.WhatsApp, TopDest.More),
            bottomTabs(ThemeStyle.Minimal, true, all),
        )
        assertEquals(TopDest.Health, startTab(ThemeStyle.Minimal))
    }

    @Test
    fun `minimal skips gated tabs`() {
        assertEquals(
            listOf(TopDest.Health, TopDest.More),
            bottomTabs(ThemeStyle.Minimal, true, FeaturesResponse()),
        )
        assertEquals(
            listOf(TopDest.Health, TopDest.More),
            bottomTabs(ThemeStyle.Minimal, false, all),
        )
        assertEquals(
            listOf(TopDest.Health, TopDest.WhatsApp, TopDest.More),
            bottomTabs(ThemeStyle.Minimal, true, FeaturesResponse(whatsapp = true)),
        )
        assertEquals(
            listOf(TopDest.Health, TopDest.More),
            bottomTabs(ThemeStyle.Minimal, true, null),
        )
    }

    @Test
    fun `switching to minimal strands the user on stats, procs and files only when they are tabs`() {
        val classic = bottomTabs(ThemeStyle.Classic, true, all)
        val minimal = bottomTabs(ThemeStyle.Minimal, true, all)
        // Classic: Stats is a tab, Health is a tool pushed from More.
        assertFalse(isStrandedOnTab(ThemeStyle.Classic, Routes.HEALTH, classic))
        assertFalse(isStrandedOnTab(ThemeStyle.Classic, TopDest.Dashboard.route, classic))
        // Minimal: Stats/Procs/Files are pushed from More, so they do not strand.
        assertFalse(isStrandedOnTab(ThemeStyle.Minimal, TopDest.Dashboard.route, minimal))
        assertFalse(isStrandedOnTab(ThemeStyle.Minimal, TopDest.Files.route, minimal))
        assertFalse(isStrandedOnTab(ThemeStyle.Minimal, Routes.HEALTH, minimal))
    }

    @Test
    fun `a gated tab that disappears strands the user`() {
        val minimalOff = bottomTabs(ThemeStyle.Minimal, true, FeaturesResponse())
        assertTrue(isStrandedOnTab(ThemeStyle.Minimal, Routes.FIND_ME, minimalOff))
        assertTrue(isStrandedOnTab(ThemeStyle.Minimal, TopDest.WhatsApp.route, minimalOff))
        val classicOff = bottomTabs(ThemeStyle.Classic, true, FeaturesResponse())
        assertTrue(isStrandedOnTab(ThemeStyle.Classic, TopDest.WhatsApp.route, classicOff))
    }

    @Test
    fun `non tab routes never strand`() {
        assertFalse(isStrandedOnTab(ThemeStyle.Minimal, Routes.SETTINGS, emptyList()))
        assertFalse(isStrandedOnTab(ThemeStyle.Classic, null, emptyList()))
    }

    @Test
    fun `theme key round trips`() {
        assertEquals(ThemeStyle.Minimal, ThemeStyle.fromKey("minimal"))
        assertEquals(ThemeStyle.Classic, ThemeStyle.fromKey("nonsense"))
    }
}
