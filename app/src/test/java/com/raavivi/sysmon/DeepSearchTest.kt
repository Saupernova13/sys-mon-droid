package com.raavivi.sysmon

import com.raavivi.sysmon.core.data.whatsapp.HttpWhatsAppRepository
import com.raavivi.sysmon.core.model.WaChatSearchResponse
import com.raavivi.sysmon.core.model.WaConvResult
import com.raavivi.sysmon.core.model.WaFacetCount
import com.raavivi.sysmon.core.model.WaRewrite
import com.raavivi.sysmon.core.model.WaRewritePerson
import com.raavivi.sysmon.core.model.WaRewriteTime
import com.raavivi.sysmon.core.net.ApiProvider
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.SysMonJson
import com.raavivi.sysmon.ui.whatsapp.ConvSearchState
import com.raavivi.sysmon.ui.whatsapp.READ_AS_NOTHING
import com.raavivi.sysmon.ui.whatsapp.SearchModePrefs
import com.raavivi.sysmon.ui.whatsapp.SearchPhase
import com.raavivi.sysmon.ui.whatsapp.actLabel
import com.raavivi.sysmon.ui.whatsapp.deepRangeText
import com.raavivi.sysmon.ui.whatsapp.matchedLine
import com.raavivi.sysmon.ui.whatsapp.modelNote
import com.raavivi.sysmon.ui.whatsapp.readAsDescription
import com.raavivi.sysmon.ui.whatsapp.readAsImagined
import com.raavivi.sysmon.ui.whatsapp.readAsTags
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
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
import java.net.ServerSocket
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.concurrent.thread

/** Deep search in Conversations: wire shapes, the words it shows, the remembered switch and the state's reruns. Made-up data only. */
@OptIn(ExperimentalCoroutinesApi::class)
class DeepSearchTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val repo = FakeWhatsAppRepository()
    private val zone = ZoneId.of("Africa/Johannesburg")

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun ts(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0, z: ZoneId = zone): Long =
        LocalDate.of(y, m, d).atTime(h, min).atZone(z).toEpochSecond()

    private fun range(y1: Int, m1: Int, d1: Int, y2: Int, m2: Int, d2: Int): String =
        deepRangeText(ts(y1, m1, d1), ts(y2, m2, d2, 23, 59), zone)

    // -- wire ------------------------------------------------------------------------------------

    @Test
    fun `a deep reply decodes its mode, every rewrite field and each result's matched`() {
        val raw = """{"mode":"deep","results":[{"node_id":1,"matched":["time","acts","people"]}],
            |"rewrite":{"source":"rules","topic":"the trip","acts":["argu","plan"],
            |"people":[{"name":"Sam","chats":1},{"name":"Alex","chats":2}],
            |"time":{"phrase":"last December","from":1733011200,"to":1735689599},
            |"expansions":["smooch","peck"],"imagined":"a long talk","extra":1}}""".trimMargin()
        val r = SysMonJson.decodeFromString(WaChatSearchResponse.serializer(), raw)
        assertEquals("deep", r.mode)
        val w = r.rewrite!!
        assertEquals("rules", w.source)
        assertEquals("the trip", w.topic)
        assertEquals(listOf("argu", "plan"), w.acts)
        assertEquals(listOf(WaRewritePerson("Sam", 1), WaRewritePerson("Alex", 2)), w.people)
        assertEquals(WaRewriteTime("last December", 1733011200L, 1735689599L), w.time)
        assertEquals(listOf("smooch", "peck"), w.expansions)
        assertEquals("a long talk", w.imagined)
        assertEquals(listOf("time", "acts", "people"), r.results.single().matched)
    }

    @Test
    fun `an instant reply has no rewrite and nothing matched, and null time and imagined decode`() {
        val instant = SysMonJson.decodeFromString(WaChatSearchResponse.serializer(), """{"results":[{"node_id":1}]}""")
        assertNull(instant.mode)
        assertNull(instant.rewrite)
        assertTrue(instant.results.single().matched.isEmpty())

        val nulls = SysMonJson.decodeFromString(
            WaChatSearchResponse.serializer(),
            """{"mode":"deep","rewrite":{"source":"rules","topic":null,"acts":[],"people":[],"time":null,"expansions":[],"imagined":null}}""",
        )
        val w = nulls.rewrite!!
        assertNull(w.time)
        assertNull(w.imagined)
        assertNull(w.topic)
        assertNull("a server without a rewrite model sends no model key", w.model)
    }

    @Test
    fun `a model rewrite decodes its source and model word`() {
        val raw = """{"mode":"deep","rewrite":{"source":"model","acts":[],"people":[],"time":null,
            |"expansions":["snog"],"imagined":"Two people kiss at a party.","model":"used"}}""".trimMargin()
        val w = SysMonJson.decodeFromString(WaChatSearchResponse.serializer(), raw).rewrite!!
        assertEquals(("model" to "used"), w.source to w.model)
        assertEquals(listOf("snog"), w.expansions)
    }

    // -- act labels and ranges ---------------------------------------------------------------------

    @Test
    fun `act stems read as words, in any case, and an unknown one stays as it is`() {
        assertEquals("arguing", actLabel("argu"))
        assertEquals("flirting", actLabel("flirt"))
        assertEquals("venting", actLabel("vent"))
        assertEquals("planning", actLabel("plan"))
        assertEquals("deciding", actLabel("decid"))
        assertEquals("joking", actLabel("jok"))
        assertEquals("comforting", actLabel("comfort"))
        assertEquals("apologising", actLabel("apolog"))
        assertEquals("arguing", actLabel("ARGU"))
        assertEquals("gossip", actLabel("gossip"))
    }

    @Test
    fun `a range reads as device-local days in the panel's style`() {
        assertEquals("5 Dec 2025", range(2025, 12, 5, 2025, 12, 5))
        assertEquals("1–31 Dec 2025", range(2025, 12, 1, 2025, 12, 31))
        assertEquals("3 Jan – 4 Mar 2025", range(2025, 1, 3, 2025, 3, 4))
        assertEquals("20 Dec 2025 – 3 Jan 2026", range(2025, 12, 20, 2026, 1, 3))
    }

    @Test
    fun `a range whose UTC end crosses midnight in the zone stays on the local day`() {
        // 22:30 UTC on the 5th is 00:30 on the 6th in Johannesburg (UTC+2).
        val from = ts(2025, 12, 5, 22, 30, ZoneOffset.UTC)
        val to = ts(2025, 12, 5, 23, 45, ZoneOffset.UTC)
        assertEquals("6 Dec 2025", deepRangeText(from, to, zone))
        assertEquals("5 Dec 2025", deepRangeText(from, to, ZoneOffset.UTC))
    }

    @Test
    fun `no range reads as empty`() {
        assertEquals("", deepRangeText(0, 0, zone))
        assertEquals("", deepRangeText(100, 50, zone))
    }

    // -- read as ----------------------------------------------------------------------------------

    private val december = WaRewriteTime("last December", ts(2025, 12, 1), ts(2025, 12, 31, 23, 59))

    @Test
    fun `read as tags cover time, acts, people and added words in that order`() {
        val r = WaRewrite(
            time = december,
            acts = listOf("argu", "plan"),
            people = listOf(WaRewritePerson("Sam", 1), WaRewritePerson("Alex", 2)),
            expansions = listOf("smooch", "peck"),
        )
        assertEquals(
            listOf("last December · 1–31 Dec 2025", "arguing", "planning", "Sam", "Alex (2 chats)", "also: smooch, peck"),
            readAsTags(r, zone),
        )
    }

    @Test
    fun `read as skips blanks and falls back for the time`() {
        val blanks = WaRewrite(acts = listOf(" ", "argu"), people = listOf(WaRewritePerson(" ", 3)), expansions = listOf(""))
        assertEquals(listOf("arguing"), readAsTags(blanks, zone))
        assertEquals(listOf("last December"), readAsTags(WaRewrite(time = WaRewriteTime("last December", 0, 0)), zone))
        assertEquals(listOf("1–31 Dec 2025"), readAsTags(WaRewrite(time = december.copy(phrase = " ")), zone))
        assertEquals(listOf("a time"), readAsTags(WaRewrite(time = WaRewriteTime("", 0, 0)), zone))
        assertTrue(readAsTags(WaRewrite(), zone).isEmpty())
    }

    @Test
    fun `the imagined text is trimmed and null when blank`() {
        assertEquals("a long talk", readAsImagined(WaRewrite(imagined = "  a long talk ")))
        assertNull(readAsImagined(WaRewrite(imagined = "   ")))
        assertNull(readAsImagined(WaRewrite()))
    }

    @Test
    fun `the description is one sentence and says when nothing was read`() {
        val r = WaRewrite(time = december, acts = listOf("argu"), people = listOf(WaRewritePerson("Alex", 2)))
        assertEquals("Read as: last December, 1–31 Dec 2025; arguing; Alex, 2 chats", readAsDescription(r, zone))
        assertEquals(
            "Read as: arguing; imagined: a long talk",
            readAsDescription(WaRewrite(acts = listOf("argu"), imagined = "a long talk"), zone),
        )
        assertEquals(READ_AS_NOTHING, readAsDescription(WaRewrite(), zone))
        assertEquals("Read as: just the words", READ_AS_NOTHING)
    }

    @Test
    fun `a rules-only read says why when the server has a rewrite model that gave nothing`() {
        assertEquals("Rules only: the model is still starting", modelNote("loading"))
        assertEquals("Rules only: the model did not answer", modelNote("failed"))
        for (m in listOf("used", "", null, "other")) assertNull("$m", modelNote(m))
        assertEquals(
            "Read as: arguing. Rules only: the model is still starting",
            readAsDescription(WaRewrite(acts = listOf("argu"), model = "loading"), zone),
        )
        assertEquals("Read as: just the words. Rules only: the model did not answer", readAsDescription(WaRewrite(model = "failed"), zone))
        assertEquals("Read as: arguing", readAsDescription(WaRewrite(acts = listOf("argu"), model = "used"), zone))
    }

    // -- matches ----------------------------------------------------------------------------------

    @Test
    fun `matched reads in a fixed order, ignores unknowns and is null when empty`() {
        assertEquals("Matches: when · who · what happened", matchedLine(listOf("acts", "people", "time")))
        assertEquals("Matches: who", matchedLine(listOf("people", "mystery")))
        assertEquals("Matches: when · what happened", matchedLine(listOf("acts", "time")))
        assertNull(matchedLine(listOf("mystery")))
        assertNull(matchedLine(emptyList()))
    }

    // -- state ------------------------------------------------------------------------------------

    private class MemPrefs(var deep: Boolean = false, var failRead: Boolean = false, var failSave: Boolean = false) : SearchModePrefs {
        var saved: List<Boolean> = emptyList()
        var readGate: CompletableDeferred<Unit>? = null
        override suspend fun load(): String? = null
        override suspend fun save(key: String) = Unit
        override suspend fun deep(): Boolean {
            readGate?.await()
            if (failRead) error("read failed")
            return deep
        }
        override suspend fun saveDeep(on: Boolean) {
            if (failSave) error("save failed")
            saved = saved + on
            deep = on
        }
    }

    private fun stateOf(prefs: SearchModePrefs = MemPrefs()) =
        ConvSearchState(repo, CoroutineScope(dispatcher), { }, { }, prefs)

    private fun answer(vararg c: Pair<String, Int>, rewrite: WaRewrite? = null, mode: String? = null) =
        ApiResult.Ok(
            WaChatSearchResponse(
                results = listOf(WaConvResult(nodeId = 1, nMsgs = 4)),
                facetCounts = c.map { WaFacetCount(it.first, it.second) },
                mode = mode,
                rewrite = rewrite,
            ),
        )

    @Test
    fun `with Deep off a search sends deep false`() {
        val conv = stateOf()
        conv.run("cinema night")
        assertEquals(listOf(false), repo.chatSearchDeep)
        assertFalse(conv.deep)
        assertFalse(conv.deepReply)
    }

    @Test
    fun `turning Deep on saves it and runs the same query again with the same themes`() {
        val prefs = MemPrefs()
        val conv = stateOf(prefs)
        repo.chatSearchResult = answer("planning" to 5)
        conv.run("cinema night")
        conv.pressTheme("planning")

        conv.setDeep(true)

        assertTrue(conv.deep)
        assertEquals(listOf(true), prefs.saved)
        assertEquals(listOf(false, false, true), repo.chatSearchDeep)
        assertEquals(listOf("cinema night", "cinema night", "cinema night"), repo.chatSearchQueries)
        assertEquals(listOf("planning"), repo.chatSearchThemes.last())
        assertEquals(listOf("planning"), conv.selectedThemes)
    }

    @Test
    fun `the rerun shows the skeleton while the deep answer is awaited`() {
        val conv = stateOf()
        conv.run("cinema night")
        val gate = CompletableDeferred<Unit>()
        repo.chatSearchGate = gate
        conv.setDeep(true)
        assertEquals(SearchPhase.LOADING, conv.phase)
        gate.complete(Unit)
        assertEquals(SearchPhase.DONE, conv.phase)
    }

    @Test
    fun `turning Deep on with no query only saves it`() {
        val prefs = MemPrefs()
        val conv = stateOf(prefs)
        conv.setDeep(true)
        assertEquals(listOf(true), prefs.saved)
        assertTrue(repo.chatSearchQueries.isEmpty())
        conv.setDeep(true)
        assertEquals(listOf(true), prefs.saved)
    }

    @Test
    fun `a theme press while Deep is on keeps sending deep true`() {
        val conv = stateOf()
        repo.chatSearchResult = answer("planning" to 5)
        conv.setDeep(true)
        conv.run("cinema night")
        conv.pressTheme("planning")
        conv.clearThemes()
        assertEquals(listOf(true, true, true), repo.chatSearchDeep)
    }

    @Test
    fun `the saved choice loads at the start`() {
        val conv = stateOf(MemPrefs(deep = true))
        assertTrue(conv.deep)
        conv.run("cinema night")
        assertEquals(listOf(true), repo.chatSearchDeep)
    }

    @Test
    fun `a choice made before the load lands wins`() {
        val prefs = MemPrefs(deep = false).also { it.readGate = CompletableDeferred() }
        val conv = stateOf(prefs)
        conv.setDeep(true)
        prefs.deep = false
        prefs.readGate!!.complete(Unit)
        assertTrue(conv.deep)
    }

    @Test
    fun `a saved Deep that lands after a query ran runs that query again, deep`() {
        val prefs = MemPrefs(deep = true).also { it.readGate = CompletableDeferred() }
        val conv = stateOf(prefs)
        conv.run("cinema night")
        prefs.readGate!!.complete(Unit)
        assertTrue(conv.deep)
        assertEquals(listOf(false, true), repo.chatSearchDeep)
        assertEquals(listOf("cinema night", "cinema night"), repo.chatSearchQueries)
    }

    @Test
    fun `a saved choice that matches what ran changes nothing`() {
        val prefs = MemPrefs(deep = false).also { it.readGate = CompletableDeferred() }
        val conv = stateOf(prefs)
        conv.run("cinema night")
        prefs.readGate!!.complete(Unit)
        assertFalse(conv.deep)
        assertEquals(listOf(false), repo.chatSearchDeep)
    }

    @Test
    fun `a failed read means off and a failed save does not crash`() {
        val conv = stateOf(MemPrefs(deep = true, failRead = true))
        assertFalse(conv.deep)
        val failing = stateOf(MemPrefs(failSave = true))
        failing.setDeep(true)
        assertTrue(failing.deep)
    }

    @Test
    fun `the rewrite is kept only for a deep reply and dropped by an instant reply and by clear`() {
        val conv = stateOf()
        val read = WaRewrite(source = "rules", acts = listOf("argu"))
        repo.chatSearchResult = answer(rewrite = read, mode = "deep")
        conv.run("cinema night")
        assertEquals(read, conv.rewrite)
        assertTrue(conv.deepReply)

        repo.chatSearchResult = answer()
        conv.run("another night")
        assertNull(conv.rewrite)
        assertFalse(conv.deepReply)

        repo.chatSearchResult = answer(rewrite = read, mode = "deep")
        conv.run("cinema night")
        conv.clear()
        assertNull(conv.rewrite)
    }

    @Test
    fun `a deep reply without a rewrite still counts as deep, with nothing read`() {
        val conv = stateOf()
        repo.chatSearchResult = answer(mode = "deep")
        conv.run("cinema night")
        assertEquals(WaRewrite(), conv.rewrite)
        assertTrue(conv.deepReply)
        assertTrue(readAsTags(conv.rewrite!!).isEmpty())
    }

    @Test
    fun `an instant rewrite sent without the mode word is ignored`() {
        val conv = stateOf()
        repo.chatSearchResult = answer(rewrite = WaRewrite(acts = listOf("argu")))
        conv.run("cinema night")
        assertNull(conv.rewrite)
    }

    // -- the request ------------------------------------------------------------------------------

    /** Serves `{}` to each request on a local socket and records the request lines: the real client, no network. */
    private fun requestLines(calls: (HttpWhatsAppRepository) -> Unit, count: Int): List<String> {
        val lines = mutableListOf<String>()
        ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val t = thread(isDaemon = true) {
                repeat(count) {
                    server.accept().use { s ->
                        val reader = s.getInputStream().bufferedReader()
                        lines += reader.readLine()
                        while (reader.readLine().orEmpty().isNotEmpty()) Unit
                        s.getOutputStream().write(
                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray(),
                        )
                    }
                }
            }
            val repo = HttpWhatsAppRepository(ApiProvider("http://127.0.0.1:${server.localPort}"))
            calls(repo)
            t.join(10_000)
        }
        return lines
    }

    @Test
    fun `mode deep is sent only when deep, beside facets, and no mode otherwise`() {
        val lines = requestLines(
            calls = { repo ->
                runBlocking {
                    assertNotNull(repo.chatSearch("night", 20, listOf("a", "b"), deep = true))
                    assertNotNull(repo.chatSearch("night", 20, listOf("a", "b")))
                    assertNotNull(repo.chatSearch("night", 20, emptyList(), deep = true))
                }
            },
            count = 3,
        )
        assertEquals(3, lines.size)
        assertTrue(lines[0], lines[0].contains("facets=a%2Cb") && lines[0].contains("mode=deep"))
        assertTrue(lines[1], lines[1].contains("facets=a%2Cb") && !lines[1].contains("mode="))
        assertTrue(lines[2], !lines[2].contains("facets=") && lines[2].contains("mode=deep"))
    }
}
