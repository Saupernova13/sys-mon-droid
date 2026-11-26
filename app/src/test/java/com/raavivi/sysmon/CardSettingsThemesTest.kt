package com.raavivi.sysmon

import com.raavivi.sysmon.core.model.WaCardSettings
import com.raavivi.sysmon.core.model.WaCardSettingsPatch
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.SysMonJson
import com.raavivi.sysmon.ui.settings.CARD_MAX_THEMES
import com.raavivi.sysmon.ui.settings.CARD_THEME_MAX_CHARS
import com.raavivi.sysmon.ui.settings.CardSettingsState
import com.raavivi.sysmon.ui.settings.addTheme
import com.raavivi.sysmon.ui.settings.canSave
import com.raavivi.sysmon.ui.settings.draftOf
import com.raavivi.sysmon.ui.settings.draftProblem
import com.raavivi.sysmon.ui.settings.isEmpty
import com.raavivi.sysmon.ui.settings.offersBuiltIn
import com.raavivi.sysmon.ui.settings.patchOf
import com.raavivi.sysmon.ui.settings.removeTheme
import com.raavivi.sysmon.ui.settings.useBuiltIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The themes of the Card settings sheet: the server's rules, the draft, the patch and the state. Made-up themes only. */
@OptIn(ExperimentalCoroutinesApi::class)
class CardSettingsThemesTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val repo = FakeWhatsAppRepository()
    private val builtIn = listOf("planning", "weekend", "family")
    private val base = WaCardSettings(
        window = "03:00-06:00",
        minMsgs = 10,
        facets = listOf("planning", "money"),
        defaultFacets = builtIn,
    )
    private lateinit var st: CardSettingsState

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo.cardSettingsResult = ApiResult.Ok(base)
        st = CardSettingsState(repo, CoroutineScope(dispatcher)) { }
        st.load()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    // -- rules ----------------------------------------------------------------------------------

    @Test
    fun `a theme is trimmed and its inner whitespace collapsed`() {
        val (list, error) = addTheme(listOf("planning"), "  night   out \t now ")
        assertNull(error)
        assertEquals(listOf("planning", "night out now"), list)
    }

    @Test
    fun `a blank theme, a comma and a too long name are refused with the list unchanged`() {
        val one = listOf("planning")
        assertEquals(one to "Type a theme name", addTheme(one, "   "))
        assertEquals(one, addTheme(one, "a,b").first)
        assertEquals("A theme cannot hold a comma", addTheme(one, "a,b").second)
        assertEquals(one, addTheme(one, "bad\u0007name").first)
        assertNotNull(addTheme(one, "bad\u0007name").second)
        assertEquals(one, addTheme(one, "x".repeat(CARD_THEME_MAX_CHARS + 1)).first)
        assertNotNull(addTheme(one, "x".repeat(CARD_THEME_MAX_CHARS + 1)).second)
        assertNull(addTheme(one, "x".repeat(CARD_THEME_MAX_CHARS)).second)
    }

    @Test
    fun `a theme already listed in any case is refused`() {
        val (list, error) = addTheme(listOf("Planning"), "pLANNING")
        assertEquals(listOf("Planning"), list)
        assertEquals("That theme is already listed", error)
    }

    @Test
    fun `at most forty themes may be listed`() {
        val full = List(CARD_MAX_THEMES) { "t$it" }
        val (list, error) = addTheme(full, "one more")
        assertEquals(full, list)
        assertEquals("At most 40 themes may be listed", error)
    }

    @Test
    fun `removing a theme ignores case and leaves the others in order`() {
        assertEquals(listOf("a", "c"), removeTheme(listOf("a", "B", "c"), "b"))
        assertEquals(listOf("a"), removeTheme(listOf("a"), "zzz"))
    }

    @Test
    fun `the built-in list is offered only when it differs and is not empty`() {
        assertTrue(offersBuiltIn(listOf("planning"), builtIn))
        assertFalse(offersBuiltIn(builtIn, builtIn))
        assertFalse(offersBuiltIn(listOf("planning"), emptyList()))
        val d = draftOf(base)
        assertEquals(builtIn, useBuiltIn(d, builtIn).facets)
        assertEquals(d, useBuiltIn(d, emptyList()))
    }

    // -- draft and patch ------------------------------------------------------------------------

    @Test
    fun `the patch leaves themes out when unchanged and sends the names otherwise`() {
        val d = draftOf(base)
        assertNull(patchOf(base, d).facets)
        assertTrue(patchOf(base, d).isEmpty())
        assertEquals(WaCardSettingsPatch(facets = listOf("planning")), patchOf(base, d.copy(facets = listOf("planning"))))
        assertEquals(WaCardSettingsPatch(facets = builtIn), patchOf(base, useBuiltIn(d, builtIn)))
    }

    @Test
    fun `the patch json omits themes when null and lists them when set`() {
        val none = SysMonJson.encodeToString(WaCardSettingsPatch.serializer(), WaCardSettingsPatch(minMsgs = 12))
        assertEquals("""{"min_msgs":12}""", none)
        val some = SysMonJson.encodeToString(WaCardSettingsPatch.serializer(), WaCardSettingsPatch(facets = listOf("a", "b")))
        assertEquals("""{"facets":["a","b"]}""", some)
    }

    @Test
    fun `an empty theme list cannot be saved and says what to do`() {
        val d = draftOf(base).copy(facets = emptyList())
        assertEquals("Keep at least one theme, or use the built-in list", draftProblem(d))
        assertFalse(canSave(base, d))
    }

    @Test
    fun `a server that sends no themes neither shows the section nor blocks saving`() {
        val old = WaCardSettings(window = "03:00-06:00", minMsgs = 10)
        val d = draftOf(old)
        assertFalse(d.hasThemes)
        assertNull(draftProblem(d))
        assertTrue(canSave(old, d.copy(minMsgs = 11)))
    }

    @Test
    fun `the settings document decodes its themes and where they came from, with defaults when absent`() {
        val raw = """{"window":"","min_msgs":10,"chats":[],"facets":["a","b"],"default_facets":["a","b","c"],
            |"source":{"window":"settings","chats":"env","min_msgs":"env","facets":"settings"}}""".trimMargin()
        val s = SysMonJson.decodeFromString(WaCardSettings.serializer(), raw)
        assertEquals(listOf("a", "b"), s.facets)
        assertEquals(listOf("a", "b", "c"), s.defaultFacets)
        assertEquals("settings", s.source?.facets)
        val old = SysMonJson.decodeFromString(WaCardSettings.serializer(), """{"window":"","min_msgs":10,"source":{"window":"env"}}""")
        assertTrue(old.facets.isEmpty())
        assertTrue(old.defaultFacets.isEmpty())
        assertEquals("", old.source?.facets)
    }

    // -- state ----------------------------------------------------------------------------------

    @Test
    fun `adding a theme puts it in the draft and clears the error, a refused one keeps the reason`() {
        assertTrue(st.addTheme("weekend"))
        assertEquals(listOf("planning", "money", "weekend"), st.draft!!.facets)
        assertNull(st.themeError)
        assertTrue(st.canSave)
        assertFalse(st.addTheme("WEEKEND"))
        assertEquals("That theme is already listed", st.themeError)
        st.clearThemeError()
        assertNull(st.themeError)
    }

    @Test
    fun `saving sends only the themes that changed`() {
        st.addTheme("weekend")
        st.save()
        assertEquals(listOf(WaCardSettingsPatch(facets = listOf("planning", "money", "weekend"))), repo.cardSettingsSaves)
        assertFalse(st.dirty)
    }

    @Test
    fun `removing a theme can be undone to the same place`() {
        val seq = st.removalSeq
        st.removeTheme("planning")
        assertEquals(listOf("money"), st.draft!!.facets)
        assertEquals(seq + 1, st.removalSeq)
        assertTrue(st.dirty)
        st.undoRemoveTheme()
        assertEquals(listOf("planning", "money"), st.draft!!.facets)
        assertFalse(st.dirty)
        st.undoRemoveTheme()
        assertEquals(listOf("planning", "money"), st.draft!!.facets)
    }

    @Test
    fun `removing every theme blocks Save until one is added or the built-in list is used`() {
        st.removeTheme("planning")
        st.removeTheme("money")
        assertFalse(st.canSave)
        assertEquals("Keep at least one theme, or use the built-in list", st.problem)
        assertTrue(st.offersBuiltIn)
        st.useBuiltInThemes()
        assertEquals(builtIn, st.draft!!.facets)
        assertTrue(st.canSave)
        assertFalse(st.offersBuiltIn)
    }
}
