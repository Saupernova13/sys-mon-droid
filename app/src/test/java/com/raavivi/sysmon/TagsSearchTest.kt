package com.raavivi.sysmon

import com.raavivi.sysmon.core.model.WaCardDetail
import com.raavivi.sysmon.core.model.WaChatSearchStatus
import com.raavivi.sysmon.core.model.WaTag
import com.raavivi.sysmon.core.model.WaTagCount
import com.raavivi.sysmon.core.model.WaTagFacet
import com.raavivi.sysmon.core.model.WaTagResult
import com.raavivi.sysmon.core.model.WaTagSuggestResponse
import com.raavivi.sysmon.core.model.WaTagsReq
import com.raavivi.sysmon.core.model.WaTagsResponse
import com.raavivi.sysmon.core.model.WaTopicCard
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.SysMonJson
import com.raavivi.sysmon.ui.whatsapp.CardPhase
import com.raavivi.sysmon.ui.whatsapp.FACET_MAX
import com.raavivi.sysmon.ui.whatsapp.FACET_SHOWN
import com.raavivi.sysmon.ui.whatsapp.SEARCH_FAILED
import com.raavivi.sysmon.ui.whatsapp.SearchMode
import com.raavivi.sysmon.ui.whatsapp.SearchModePrefs
import com.raavivi.sysmon.ui.whatsapp.TAGS_PAGE
import com.raavivi.sysmon.ui.whatsapp.TAGS_QUERY_MS
import com.raavivi.sysmon.ui.whatsapp.TAGS_SUGGEST_MS
import com.raavivi.sysmon.ui.whatsapp.TAG_CATS
import com.raavivi.sysmon.ui.whatsapp.TagFilters
import com.raavivi.sysmon.ui.whatsapp.TagGroup
import com.raavivi.sysmon.ui.whatsapp.TagPick
import com.raavivi.sysmon.ui.whatsapp.TagSelection
import com.raavivi.sysmon.ui.whatsapp.TagSort
import com.raavivi.sysmon.ui.whatsapp.TagsPhase
import com.raavivi.sysmon.ui.whatsapp.TagsSearchState
import com.raavivi.sysmon.ui.whatsapp.WhatsAppSearch
import com.raavivi.sysmon.ui.whatsapp.asConv
import com.raavivi.sysmon.ui.whatsapp.browseCats
import com.raavivi.sysmon.ui.whatsapp.buildTagsQuery
import com.raavivi.sysmon.ui.whatsapp.catLabel
import com.raavivi.sysmon.ui.whatsapp.effectiveSort
import com.raavivi.sysmon.ui.whatsapp.facetWindow
import com.raavivi.sysmon.ui.whatsapp.filterCount
import com.raavivi.sysmon.ui.whatsapp.hasMoreTags
import com.raavivi.sysmon.ui.whatsapp.key
import com.raavivi.sysmon.ui.whatsapp.noteChats
import com.raavivi.sysmon.ui.whatsapp.otherTags
import com.raavivi.sysmon.ui.whatsapp.searchModeOf
import com.raavivi.sysmon.ui.whatsapp.tagsTotalText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** The Tags search: its pure decisions, wire shapes and state, over the scriptable fake repository. Made-up data only. */
@OptIn(ExperimentalCoroutinesApi::class)
class TagsSearchTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val repo = FakeWhatsAppRepository()
    private val alice = "27820000001@s.whatsapp.net"
    private val bob = "27820000002@s.whatsapp.net"

    private lateinit var state: TagsSearchState
    private var offCalls = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        state = TagsSearchState(repo, CoroutineScope(dispatcher)) { offCalls++ }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun advance(ms: Long) = dispatcher.scheduler.advanceTimeBy(ms)

    private val holiday = TagPick("topic", "holiday", "Holiday")
    private val mia = TagPick("entity", "mia", "Mia")
    private val plan = TagPick("topic", "plan", "Plans")

    private fun result(id: Long, node: Long? = id + 100, chat: String = alice, matched: List<WaTag> = emptyList(), tags: List<WaTag> = emptyList()) =
        WaTagResult(
            cardId = id, nodeId = node, chat = chat, family = listOf(chat), chatName = "Invented Mia",
            startTs = 100, startRowid = 1, endTs = 200, endRowid = 12, nMsgs = 12,
            title = " A title $id ", summary = "A summary", matched = matched, tags = tags,
        )

    private fun page(vararg r: WaTagResult, total: Int = r.size, facets: Map<String, List<WaTagFacet>> = emptyMap()) =
        ApiResult.Ok(WaTagsResponse(total = total, results = r.toList(), facets = facets))

    // -- selection -------------------------------------------------------------------------------

    @Test
    fun `adding puts a tag in a group and moves it when added again elsewhere`() {
        val sel = TagSelection().with(holiday).with(mia, TagGroup.ANY)
        assertEquals(listOf(holiday), sel.all)
        assertEquals(listOf(mia), sel.any)
        assertEquals(TagGroup.ANY, sel.groupOf(mia))

        val moved = sel.with(holiday, TagGroup.NONE)
        assertTrue(moved.all.isEmpty())
        assertEquals(listOf(holiday), moved.none)
        assertEquals(2, moved.size)
    }

    @Test
    fun `a tap cycles must have, any of, exclude and round again`() {
        var sel = TagSelection().with(holiday)
        sel = sel.cycled(holiday)
        assertEquals(TagGroup.ANY, sel.groupOf(holiday))
        sel = sel.cycled(holiday)
        assertEquals(TagGroup.NONE, sel.groupOf(holiday))
        sel = sel.cycled(holiday)
        assertEquals(TagGroup.ALL, sel.groupOf(holiday))
        // A tag that is not selected stays out.
        assertEquals(sel, sel.cycled(mia))
    }

    @Test
    fun `removing and clearing drop tags from every group`() {
        val sel = TagSelection().with(holiday).with(mia, TagGroup.NONE).with(plan, TagGroup.ANY)
        assertEquals(2, sel.without(mia).size)
        assertNull(sel.without(mia).groupOf(mia))
        assertTrue(TagSelection().isEmpty)
    }

    @Test
    fun `two tags with the same value in different categories are different tags`() {
        val a = TagPick("topic", "paris")
        val b = TagPick("entity", "paris")
        val sel = TagSelection().with(a).with(b)
        assertEquals(2, sel.size)
        assertEquals(1, sel.without(a).size)
    }

    @Test
    fun `category words and glyphs`() {
        assertEquals("People, places & things", catLabel("entity"))
        assertEquals("Private words", catLabel("term"))
        assertEquals("zzz", catLabel("zzz"))
        assertEquals(8, TAG_CATS.size)
        assertEquals(listOf("+", "~", "−"), TagGroup.entries.map { it.glyph })
        assertEquals(TAG_CATS.map { it.first }, browseCats(""))
        assertEquals(listOf("who"), browseCats("who"))
    }

    // -- query body ------------------------------------------------------------------------------

    @Test
    fun `the query body carries the selection and paging, and no filters by default`() {
        val sel = TagSelection().with(holiday).with(mia, TagGroup.ANY).with(plan, TagGroup.NONE)
        val body = buildTagsQuery(sel, TagFilters(), offset = 0)
        assertEquals(listOf("topic" to "holiday"), body.all.map { it.cat to it.value })
        assertEquals(listOf("entity" to "mia"), body.any.map { it.cat to it.value })
        assertEquals(listOf("topic" to "plan"), body.none.map { it.cat to it.value })
        assertEquals("newest", body.sort)
        assertEquals(TAGS_PAGE, body.limit)
        assertEquals(0, body.offset)
        assertNull(body.chat)
        assertNull(body.since)
        assertNull(body.until)
        assertNull(body.minIntensity)
        assertNull(body.unresolved)
    }

    @Test
    fun `filters go in the body and until is the end of the chosen day`() {
        val f = TagFilters(
            chat = alice, since = LocalDate.of(2026, 11, 2), until = LocalDate.of(2026, 11, 3),
            minIntensity = 3, unresolved = true, sort = TagSort.LONGEST,
        )
        val body = buildTagsQuery(TagSelection(), f, offset = 60, limit = 30, zone = ZoneOffset.UTC)
        assertEquals(alice, body.chat)
        assertEquals(LocalDate.of(2026, 11, 2).atStartOfDay(ZoneOffset.UTC).toEpochSecond(), body.since)
        // 4 Nov 00:00: the 3rd is included.
        assertEquals(LocalDate.of(2026, 11, 4).atStartOfDay(ZoneOffset.UTC).toEpochSecond(), body.until)
        assertEquals(3, body.minIntensity)
        assertEquals(true, body.unresolved)
        assertEquals("longest", body.sort)
        assertEquals(60, body.offset)
    }

    @Test
    fun `sorting by matches falls back to newest without any-of tags`() {
        val f = TagFilters(sort = TagSort.MATCHES)
        assertEquals(TagSort.NEWEST, effectiveSort(TagSelection().with(holiday), f))
        assertEquals("newest", buildTagsQuery(TagSelection().with(holiday), f).sort)
        assertEquals(TagSort.MATCHES, effectiveSort(TagSelection().with(holiday, TagGroup.ANY), f))
        assertEquals("matches", buildTagsQuery(TagSelection().with(holiday, TagGroup.ANY), f).sort)
    }

    @Test
    fun `the filter count counts what differs from the defaults`() {
        assertEquals(0, filterCount(TagFilters()))
        assertEquals(3, filterCount(TagFilters(chat = alice, minIntensity = 2, unresolved = true)))
        assertEquals(1, filterCount(TagFilters(sort = TagSort.OLDEST)))
    }

    @Test
    fun `the request encodes lists, sort and paging, and leaves unset filters out`() {
        val json = SysMonJson.encodeToString(buildTagsQuery(TagSelection().with(holiday), TagFilters(minIntensity = 2)))
        assertTrue(json, json.contains("\"all\":[{\"cat\":\"topic\",\"value\":\"holiday\"}]"))
        assertTrue(json, json.contains("\"any\":[]"))
        assertTrue(json, json.contains("\"none\":[]"))
        assertTrue(json, json.contains("\"sort\":\"newest\""))
        assertTrue(json, json.contains("\"limit\":30"))
        assertTrue(json, json.contains("\"offset\":0"))
        assertTrue(json, json.contains("\"min_intensity\":2"))
        assertFalse(json, json.contains("chat"))
        assertFalse(json, json.contains("since"))
        assertFalse(json, json.contains("unresolved"))
    }

    // -- helpers ---------------------------------------------------------------------------------

    @Test
    fun `a facet list shows twelve, then up to thirty once expanded`() {
        val tags = (1..40).map { WaTagFacet("v$it", "V$it", 100 - it) }
        val closed = facetWindow(tags, false)
        assertEquals(FACET_SHOWN, closed.shown.size)
        assertEquals(FACET_MAX - FACET_SHOWN, closed.more)
        val open = facetWindow(tags, true)
        assertEquals(FACET_MAX, open.shown.size)
        assertEquals(0, open.more)
        val few = facetWindow(tags.take(5), false)
        assertEquals(5, few.shown.size)
        assertEquals(0, few.more)
        assertEquals(15 - FACET_SHOWN, facetWindow(tags.take(15), false).more)
    }

    @Test
    fun `other tags leave out the matched ones and stop at six`() {
        val tags = (1..9).map { WaTag("topic", "t$it", "T$it") }
        val r = result(1, matched = listOf(tags[0], tags[1]), tags = tags)
        assertEquals(tags.subList(2, 8), otherTags(r))
    }

    @Test
    fun `chats seen are kept in first-seen order with their names`() {
        val known = noteChats(emptyMap(), listOf(result(1, chat = alice), result(2, chat = bob)))
        assertEquals(listOf(alice, bob), known.keys.toList())
        assertEquals("Invented Mia", known[alice])
        val again = noteChats(known, listOf(result(3, chat = bob)))
        assertEquals(known, again)
    }

    @Test
    fun `texts and paging`() {
        assertEquals("1 conversation", tagsTotalText(1))
        assertEquals("12 conversations", tagsTotalText(12))
        assertTrue(hasMoreTags(30, 100, 30))
        assertFalse(hasMoreTags(100, 100, 30))
        assertFalse(hasMoreTags(30, 100, 0))
    }

    @Test
    fun `a tag result becomes a conversation for the card sheet, node or not`() {
        val withNode = result(1, node = 7).asConv()
        assertEquals(7L, withNode.nodeId)
        assertEquals("A title 1", withNode.card!!.title)
        assertEquals(1L, withNode.card!!.id)
        val noNode = result(2, node = null).asConv()
        assertEquals(0L, noNode.nodeId)
        assertEquals(listOf(alice), noNode.family)
        assertEquals(100L, noNode.startTs)
    }

    // -- wire ------------------------------------------------------------------------------------

    @Test
    fun `a tags reply decodes results with and without a topic node, and the facets`() {
        val body = SysMonJson.decodeFromString(
            WaTagsResponse.serializer(),
            """{"total":2,"results":[
               {"card_id":1,"node_id":null,"chat":"27820000001@s.whatsapp.net",
                "family":["27820000001@s.whatsapp.net","27820000009@lid"],"chat_name":"Invented Mia",
                "start_ts":100,"start_rowid":1,"end_ts":200,"end_rowid":12,"n_msgs":12,
                "title":"A title","summary":"A summary",
                "matched":[{"cat":"topic","value":"holiday","label":"Holiday"}],
                "tags":[{"cat":"topic","value":"plan","label":"Plans"},{"cat":"entity","value":"mia","label":"Mia"}],"extra":1},
               {"card_id":2,"node_id":9,"chat":"27820000002@s.whatsapp.net","family":[],"chat_name":"",
                "start_ts":1,"start_rowid":1,"end_ts":2,"end_rowid":2,"n_msgs":1,"title":"","summary":"","matched":[],"tags":[]}],
               "facets":{"topic":[{"value":"plan","label":"Plans","count":1}],"entity":[{"value":"mia","label":"Mia","count":2}],
               "who":[],"facet":[],"act":[],"moment":[],"detail":[],"term":[]}}""",
        )
        assertEquals(2, body.total)
        val first = body.results[0]
        assertNull(first.nodeId)
        assertEquals(1L, first.cardId)
        assertEquals(2, first.family.size)
        assertEquals(12, first.nMsgs)
        assertEquals("holiday", first.matched.single().value)
        assertEquals(listOf("Plans", "Mia"), first.tags.map { it.label })
        assertEquals(9L, body.results[1].nodeId)
        assertEquals(8, body.facets.size)
        assertEquals(2, body.facets.getValue("entity").single().count)
        assertTrue(body.facets.getValue("who").isEmpty())
    }

    @Test
    fun `a type-ahead reply decodes its tags and categories`() {
        val body = SysMonJson.decodeFromString(
            WaTagSuggestResponse.serializer(),
            """{"tags":[{"cat":"entity","value":"mia","label":"Mia","count":4}],"categories":[{"cat":"entity","distinct":3,"cards":5}]}""",
        )
        assertEquals("Mia", body.tags.single().label)
        assertEquals(4, body.tags.single().count)
        assertEquals(3, body.categories.single().distinct)
    }

    // -- state: the query ------------------------------------------------------------------------

    @Test
    fun `starting loads the browse view once`() {
        repo.tagsResult = page(result(1), result(2), total = 2, facets = mapOf("topic" to listOf(WaTagFacet("plan", "Plans", 3))))
        state.start()

        assertEquals(TagsPhase.DONE, state.phase)
        assertEquals(2, state.results.size)
        assertEquals(2, state.total)
        assertEquals("Plans", state.facets.getValue("topic").single().label)
        assertEquals(setOf(alice), state.knownChats.keys)
        assertFalse(state.more)

        state.start()
        assertEquals(1, repo.tagsQueries.size)
    }

    @Test
    fun `a failed start tries again on the next start`() {
        repo.tagsResult = ApiResult.Err("boom", 502)
        state.start()
        assertEquals(TagsPhase.ERROR, state.phase)
        assertEquals(SEARCH_FAILED, state.error)

        repo.tagsResult = page(result(1))
        state.start()
        assertEquals(TagsPhase.DONE, state.phase)
        assertEquals(2, repo.tagsQueries.size)
    }

    @Test
    fun `a 404 says chat search is off`() {
        repo.tagsResult = ApiResult.Err("off", 404)
        state.start()
        assertEquals(TagsPhase.OFF, state.phase)
        assertTrue(state.off)
        assertEquals(1, offCalls)
    }

    @Test
    fun `a rejected query shows the server's words`() {
        repo.tagsResult = ApiResult.Err("tags.all: too many tags", 400)
        state.start()
        assertEquals("tags.all: too many tags", state.error)
    }

    @Test
    fun `a change asks again after the debounce, from offset zero, with the new selection`() {
        repo.tagsResult = page(result(1))
        state.start()
        state.add(holiday)

        advance(TAGS_QUERY_MS - 1)
        assertEquals(1, repo.tagsQueries.size)
        advance(2)

        assertEquals(2, repo.tagsQueries.size)
        val body = repo.tagsQueries.last()
        assertEquals(listOf("holiday"), body.all.map { it.value })
        assertEquals(0, body.offset)
    }

    @Test
    fun `several quick changes ask once`() {
        state.start()
        state.add(holiday)
        advance(100)
        state.add(mia, TagGroup.NONE)
        advance(100)
        state.setMinIntensity(3)
        advance(TAGS_QUERY_MS + 1)

        assertEquals(2, repo.tagsQueries.size)
        val body = repo.tagsQueries.last()
        assertEquals(listOf("holiday"), body.all.map { it.value })
        assertEquals(listOf("mia"), body.none.map { it.value })
        assertEquals(3, body.minIntensity)
    }

    @Test
    fun `the Tap adds choice decides the group of the next pick`() {
        state.start()
        state.chooseAddGroup(TagGroup.NONE)
        state.add(mia)
        assertEquals(TagGroup.NONE, state.selection.groupOf(mia))
        state.add(holiday, TagGroup.ANY)
        assertEquals(TagGroup.ANY, state.selection.groupOf(holiday))
    }

    @Test
    fun `a stale response never overwrites a newer one`() {
        val gate = CompletableDeferred<Unit>()
        repo.tagsGate = gate
        repo.tagsResults += page(result(1), total = 1)
        repo.tagsResults += page(result(2), result(3), total = 2)
        state.start()
        state.add(holiday)
        advance(TAGS_QUERY_MS + 1)

        gate.complete(Unit)

        assertEquals(listOf(2L, 3L), state.results.map { it.cardId })
        assertEquals(2, state.total)
        assertEquals(TagsPhase.DONE, state.phase)
    }

    @Test
    fun `a reply that lands after the question changed is dropped`() {
        val gate = CompletableDeferred<Unit>()
        repo.tagsGate = gate
        repo.tagsResult = page(result(1))
        state.start()
        // The question changes while the first answer is still out, before the next ask goes.
        state.add(holiday)
        gate.complete(Unit)

        assertEquals(TagsPhase.LOADING, state.phase)
        assertTrue(state.results.isEmpty())
    }

    @Test
    fun `a change resets paging and a sort of matches without any-of falls back`() {
        repo.tagsResult = page(result(1), total = 5)
        state.start()
        state.setSort(TagSort.MATCHES)
        advance(TAGS_QUERY_MS + 1)
        // No any-of tag: the sort is back to newest, in the state and on the wire.
        assertEquals(TagSort.NEWEST, state.filters.sort)
        assertEquals("newest", repo.tagsQueries.last().sort)

        state.add(holiday, TagGroup.ANY)
        state.setSort(TagSort.MATCHES)
        advance(TAGS_QUERY_MS + 1)
        assertEquals("matches", repo.tagsQueries.last().sort)

        state.remove(holiday)
        advance(TAGS_QUERY_MS + 1)
        assertEquals(TagSort.NEWEST, state.filters.sort)
    }

    @Test
    fun `clearing the selection asks again, clearing nothing asks nothing`() {
        state.start()
        state.clearSelection()
        advance(TAGS_QUERY_MS + 1)
        assertEquals(1, repo.tagsQueries.size)

        state.add(holiday)
        state.clearSelection()
        advance(TAGS_QUERY_MS + 1)
        assertEquals(2, repo.tagsQueries.size)
        assertTrue(repo.tagsQueries.last().all.isEmpty())
    }

    @Test
    fun `filters re-run the query and reset restores the defaults`() {
        state.start()
        state.setChat(alice)
        state.setSince(LocalDate.of(2026, 1, 5))
        state.setUnresolved(true)
        advance(TAGS_QUERY_MS + 1)
        val body = repo.tagsQueries.last()
        assertEquals(alice, body.chat)
        assertNotNull(body.since)
        assertEquals(true, body.unresolved)
        assertEquals(3, filterCount(state.filters))

        state.resetFilters()
        advance(TAGS_QUERY_MS + 1)
        assertEquals(TagFilters(), state.filters)
        assertNull(repo.tagsQueries.last().chat)
    }

    // -- state: paging ---------------------------------------------------------------------------

    @Test
    fun `more appends the next page from the loaded count`() {
        repo.tagsResults += page(result(1), result(2), total = 4)
        repo.tagsResults += page(result(3), result(4), total = 4)
        state.start()
        assertTrue(state.more)

        state.loadMore()

        assertEquals(listOf(1L, 2L, 3L, 4L), state.results.map { it.cardId })
        assertEquals(2, repo.tagsQueries.last().offset)
        assertFalse(state.more)
        assertFalse(state.loadingMore)
        // Nothing left: no more asking.
        state.loadMore()
        assertEquals(2, repo.tagsQueries.size)
    }

    @Test
    fun `a failed page leaves the list and lets it be asked again`() {
        repo.tagsResults += page(result(1), total = 3)
        repo.tagsResults += ApiResult.Err("boom", 502)
        repo.tagsResults += page(result(2), result(3), total = 3)
        state.start()

        state.loadMore()
        assertEquals(1, state.results.size)
        assertTrue(state.more)
        assertFalse(state.loadingMore)

        state.loadMore()
        assertEquals(3, state.results.size)
    }

    @Test
    fun `a page that lands after a change is dropped`() {
        repo.tagsResults += page(result(1), total = 3)
        state.start()
        val gate = CompletableDeferred<Unit>()
        repo.tagsGate = gate
        repo.tagsResults += page(result(9), total = 3)

        state.loadMore()
        assertTrue(state.loadingMore)
        state.add(holiday)
        gate.complete(Unit)

        assertEquals(listOf(1L), state.results.map { it.cardId })
        assertFalse(state.loadingMore)
    }

    // -- state: type-ahead -----------------------------------------------------------------------

    @Test
    fun `typing asks the type-ahead after the debounce with the category and limit`() {
        repo.suggestResult = ApiResult.Ok(WaTagSuggestResponse(tags = listOf(WaTagCount("entity", "mia", "Mia", 4))))
        state.chooseCat("entity")
        state.onInput("mi")
        advance(TAGS_SUGGEST_MS - 1)
        assertTrue(repo.suggestCalls.isEmpty())
        assertTrue(state.suggestLoading)
        advance(2)

        assertEquals(listOf(Triple("entity", "mi", 8)), repo.suggestCalls)
        assertEquals("Mia", state.suggestions.single().label)
        assertFalse(state.suggestLoading)
    }

    @Test
    fun `typing again restarts the type-ahead debounce`() {
        state.onInput("m")
        advance(200)
        state.onInput("mi")
        advance(TAGS_SUGGEST_MS + 1)
        assertEquals(listOf("mi"), repo.suggestCalls.map { it.second })
    }

    @Test
    fun `an emptied field clears the suggestions and asks nothing`() {
        repo.suggestResult = ApiResult.Ok(WaTagSuggestResponse(tags = listOf(WaTagCount("entity", "mia", "Mia", 4))))
        state.onInput("mi")
        advance(TAGS_SUGGEST_MS + 1)
        assertEquals(1, state.suggestions.size)

        state.onInput("")
        assertTrue(state.suggestions.isEmpty())
        advance(TAGS_SUGGEST_MS + 1)
        assertEquals(1, repo.suggestCalls.size)
    }

    @Test
    fun `a stale type-ahead reply is dropped`() {
        val gate = CompletableDeferred<Unit>()
        repo.suggestGate = gate
        repo.suggestResult = ApiResult.Ok(WaTagSuggestResponse(tags = listOf(WaTagCount("entity", "mia", "Mia", 4))))
        state.onInput("mi")
        advance(TAGS_SUGGEST_MS + 1)

        state.onInput("")
        gate.complete(Unit)

        assertTrue(state.suggestions.isEmpty())
    }

    @Test
    fun `suggestions leave out tags already selected`() {
        state.add(mia)
        repo.suggestResult = ApiResult.Ok(
            WaTagSuggestResponse(tags = listOf(WaTagCount("entity", "mia", "Mia", 4), WaTagCount("entity", "milo", "Milo", 2))),
        )
        state.onInput("mi")
        advance(TAGS_SUGGEST_MS + 1)
        assertEquals(listOf("Milo"), state.suggestions.map { it.label })
    }

    @Test
    fun `picking a suggestion adds it as must have, empties the field and re-runs`() {
        state.start()
        repo.suggestResult = ApiResult.Ok(WaTagSuggestResponse(tags = listOf(WaTagCount("entity", "mia", "Mia", 4))))
        state.onInput("mi")
        advance(TAGS_SUGGEST_MS + 1)

        state.pickSuggestion(state.suggestions.single())
        assertEquals("", state.input)
        assertTrue(state.suggestions.isEmpty())
        assertEquals(TagGroup.ALL, state.selection.groupOf(mia))
        advance(TAGS_QUERY_MS + 1)
        assertEquals(listOf("mia"), repo.tagsQueries.last().all.map { it.value })
    }

    @Test
    fun `the search key takes the first suggestion`() {
        repo.suggestResult = ApiResult.Ok(
            WaTagSuggestResponse(tags = listOf(WaTagCount("entity", "mia", "Mia", 4), WaTagCount("entity", "milo", "Milo", 2))),
        )
        state.onInput("mi")
        advance(TAGS_SUGGEST_MS + 1)
        state.submitInput()
        assertEquals(listOf(mia), state.selection.all)
    }

    @Test
    fun `changing the category asks the type-ahead again at once`() {
        state.onInput("mi")
        advance(TAGS_SUGGEST_MS + 1)
        state.chooseCat("who")
        assertEquals(listOf("" to "mi", "who" to "mi"), repo.suggestCalls.map { it.first to it.second })
    }

    // -- state: the card sheet -------------------------------------------------------------------

    private val detail = WaCardDetail(id = 3, card = WaTopicCard(title = "Full title"))

    @Test
    fun `a card opens by its topic node when it has one`() {
        repo.tagsResult = page(result(1, node = 7))
        repo.cardResult = ApiResult.Ok(detail)
        state.start()

        state.openCard(0)

        assertEquals(listOf(7L), repo.cardRequests)
        assertTrue(repo.cardIdRequests.isEmpty())
        assertEquals(CardPhase.DONE, state.cardSheet!!.phase)
        assertEquals("Full title", state.cardSheet!!.detail!!.card.title)
    }

    @Test
    fun `a card whose topic node is gone opens by its card id`() {
        repo.tagsResult = page(result(5, node = null))
        repo.cardResult = ApiResult.Ok(detail)
        state.start()

        state.openCard(0)

        assertTrue(repo.cardRequests.isEmpty())
        assertEquals(listOf(5L), repo.cardIdRequests)
        assertEquals(CardPhase.DONE, state.cardSheet!!.phase)
    }

    @Test
    fun `the sheet is loading while the card is in flight, missing on 404 and retryable on error`() {
        repo.tagsResult = page(result(5, node = null))
        state.start()
        val gate = CompletableDeferred<Unit>()
        repo.cardGate = gate
        repo.cardResult = ApiResult.Err("nope", 404)

        state.openCard(0)
        assertEquals(CardPhase.LOADING, state.cardSheet!!.phase)
        gate.complete(Unit)
        assertEquals(CardPhase.MISSING, state.cardSheet!!.phase)

        repo.cardGate = null
        repo.cardResult = ApiResult.Err("boom", 502)
        state.retryCard()
        assertEquals(CardPhase.ERROR, state.cardSheet!!.phase)

        repo.cardResult = ApiResult.Ok(detail)
        state.retryCard()
        assertEquals(CardPhase.DONE, state.cardSheet!!.phase)
    }

    @Test
    fun `closing or changing the query drops the sheet and a late card`() {
        repo.tagsResult = page(result(5, node = null))
        state.start()
        val gate = CompletableDeferred<Unit>()
        repo.cardGate = gate
        repo.cardResult = ApiResult.Ok(detail)

        state.openCard(0)
        state.add(holiday)
        gate.complete(Unit)
        assertNull(state.cardSheet)

        state.openCard(7)
        assertNull(state.cardSheet)
    }

    // -- the Search view's mode ------------------------------------------------------------------

    private class MemPrefs(var mode: String? = null, var seen: Boolean = false) : SearchModePrefs {
        override suspend fun load(): String? = mode
        override suspend fun save(key: String) { mode = key }
        override suspend fun tagsGuideSeen(): Boolean = seen
        override suspend fun markTagsGuideSeen() { seen = true }
    }

    private val chatOn = ApiResult.Ok(WaChatSearchStatus(enabled = true, auto = true, nodeVectors = 50))

    private fun search(prefs: SearchModePrefs = MemPrefs()) =
        WhatsAppSearch(repo, CoroutineScope(dispatcher), { null }, {}, {}, prefs)

    @Test
    fun `the switch offers Tags with Conversations and Messages when chat search is on`() {
        repo.chatSearchStatusResult = chatOn
        val s = search()
        assertTrue(s.modes.isEmpty())
        s.viewShown()
        assertEquals(listOf(SearchMode.CONVERSATIONS, SearchMode.MESSAGES, SearchMode.TAGS), s.modes)
        assertEquals(SearchMode.CONVERSATIONS, s.mode)
        s.viewHidden()
    }

    @Test
    fun `Tags needs only chat search on, not indexed vectors`() {
        repo.chatSearchStatusResult = ApiResult.Ok(WaChatSearchStatus(enabled = true, nodeVectors = 0))
        val s = search()
        s.viewShown()
        assertEquals(listOf(SearchMode.MESSAGES, SearchMode.TAGS), s.modes)
        s.setMode(SearchMode.TAGS)
        assertEquals(SearchMode.TAGS, s.mode)
        s.viewHidden()
    }

    @Test
    fun `no mode switch while chat search is off`() {
        repo.chatSearchStatusResult = ApiResult.Ok(WaChatSearchStatus(enabled = false))
        val s = search()
        s.viewShown()
        assertTrue(s.modes.isEmpty())
        s.setMode(SearchMode.TAGS)
        assertEquals(SearchMode.MESSAGES, s.mode)
        s.viewHidden()
    }

    @Test
    fun `picking Tags loads the browse view, shows it and remembers the choice`() {
        repo.chatSearchStatusResult = chatOn
        repo.tagsResult = page(result(1))
        val prefs = MemPrefs()
        val s = search(prefs)
        s.viewShown()

        s.setMode(SearchMode.TAGS)

        assertEquals(SearchMode.TAGS, s.mode)
        assertEquals(1, repo.tagsQueries.size)
        assertEquals(TagsPhase.DONE, s.tags.phase)
        assertEquals("tags", prefs.mode)
        s.viewHidden()
    }

    @Test
    fun `the remembered mode is restored when the status lands`() {
        repo.chatSearchStatusResult = chatOn
        repo.tagsResult = page(result(1))
        val s = search(MemPrefs(mode = "tags"))
        s.viewShown()

        assertEquals(SearchMode.TAGS, s.mode)
        assertEquals(1, repo.tagsQueries.size)
        s.viewHidden()
    }

    @Test
    fun `a remembered Tags falls back when chat search is off`() {
        repo.chatSearchStatusResult = ApiResult.Ok(WaChatSearchStatus(enabled = false))
        val s = search(MemPrefs(mode = "tags"))
        s.viewShown()
        assertEquals(SearchMode.MESSAGES, s.mode)
        assertTrue(repo.tagsQueries.isEmpty())
        s.viewHidden()
    }

    @Test
    fun `a 404 from the tags route drops the mode and falls back`() {
        repo.chatSearchStatusResult = chatOn
        repo.tagsResult = ApiResult.Err("off", 404)
        val s = search()
        s.viewShown()
        s.setMode(SearchMode.TAGS)
        assertEquals(SearchMode.CONVERSATIONS, s.mode)
        assertFalse(s.modes.contains(SearchMode.TAGS))
        s.viewHidden()
    }

    @Test
    fun `typing in Tags mode feeds the type-ahead, not a text search`() {
        repo.chatSearchStatusResult = chatOn
        repo.suggestResult = ApiResult.Ok(WaTagSuggestResponse(tags = listOf(WaTagCount("entity", "mia", "Mia", 4))))
        val s = search()
        s.viewShown()
        s.setMode(SearchMode.TAGS)

        s.onQueryChange("mi")
        advance(TAGS_SUGGEST_MS + 1)

        assertEquals("mi", s.tags.input)
        assertEquals("", s.query)
        assertEquals(1, repo.suggestCalls.size)
        assertTrue(repo.chatSearchQueries.isEmpty())
        s.viewHidden()
    }

    @Test
    fun `a guide example leaves Tags for a text mode and runs there`() {
        repo.chatSearchStatusResult = chatOn
        val s = search()
        s.viewShown()
        s.setMode(SearchMode.TAGS)

        s.openFor("when we planned a trip")

        assertEquals(SearchMode.CONVERSATIONS, s.mode)
        assertEquals(listOf("when we planned a trip"), repo.chatSearchQueries)
        s.viewHidden()
    }

    @Test
    fun `the Tags guide shows expanded until it is folded once`() {
        val prefs = MemPrefs()
        val s = search(prefs)
        assertTrue(s.tagsGuideOpen)
        s.showTagsGuide(false)
        assertFalse(s.tagsGuideOpen)
        assertTrue(prefs.seen)

        val later = search(prefs)
        assertFalse(later.tagsGuideOpen)
        later.showTagsGuide(true)
        assertTrue(later.tagsGuideOpen)
    }

    @Test
    fun `mode keys round trip and unknown keys are ignored`() {
        for (m in SearchMode.entries) assertEquals(m, searchModeOf(m.key))
        assertNull(searchModeOf("nope"))
        assertNull(searchModeOf(null))
    }
}
