package com.raavivi.sysmon

import com.raavivi.sysmon.core.data.whatsapp.HttpWhatsAppRepository
import com.raavivi.sysmon.core.model.WaCardDetail
import com.raavivi.sysmon.core.model.WaChatSearchResponse
import com.raavivi.sysmon.core.model.WaConvCard
import com.raavivi.sysmon.core.model.WaConvResult
import com.raavivi.sysmon.core.model.WaSimilarResponse
import com.raavivi.sysmon.core.net.ApiProvider
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.SysMonJson
import com.raavivi.sysmon.ui.whatsapp.CardPhase
import com.raavivi.sysmon.ui.whatsapp.ConvSearchState
import com.raavivi.sysmon.ui.whatsapp.SIMILAR_FAILED
import com.raavivi.sysmon.ui.whatsapp.SIMILAR_MAX
import com.raavivi.sysmon.ui.whatsapp.SimilarPhase
import com.raavivi.sysmon.ui.whatsapp.foundOffered
import com.raavivi.sysmon.ui.whatsapp.similarListOf
import com.raavivi.sysmon.ui.whatsapp.similarRowMeta
import com.raavivi.sysmon.ui.whatsapp.similarRowTitle
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
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** "More like this": the wire shapes, the repository's reasons, the state's stack and lists, and the row words. Made-up data only. */
@OptIn(ExperimentalCoroutinesApi::class)
class SimilarTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val repo = FakeWhatsAppRepository()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun conv(node: Long, title: String? = null) =
        WaConvResult(nodeId = node, chat = "chat$node@g.us", chatName = "Chat $node", card = title?.let { WaConvCard(title = it) })

    /** A state with two results (nodes 1 and 2) whose first card is open. */
    private fun opened(): ConvSearchState {
        val conv = ConvSearchState(repo, CoroutineScope(dispatcher), { }, { })
        repo.chatSearchResult = ApiResult.Ok(WaChatSearchResponse(results = listOf(conv(1, "Trip"), conv(2, "Film"))))
        conv.run("a night out")
        conv.openCard(0)
        return conv
    }

    private fun similarOf(vararg nodes: Long) = ApiResult.Ok(WaSimilarResponse(of = 1, results = nodes.map { conv(it, "Card $it") }))

    // -- wire --------------------------------------------------------------------------------------

    @Test
    fun `the similar reply decodes its node, rows and time and ignores extra keys`() {
        val raw = """{"of":7,"results":[{"node_id":9,"chat":"x@g.us","card":{"title":"Beach day"},"extra":1}],"took_ms":12,"more":true}"""
        val r = SysMonJson.decodeFromString(WaSimilarResponse.serializer(), raw)
        assertEquals(7L, r.of)
        assertEquals(12L, r.tookMs)
        assertEquals(9L, r.results.single().nodeId)
        assertEquals("Beach day", r.results.single().card?.title)
    }

    @Test
    fun `an empty similar reply decodes to no rows`() {
        val r = SysMonJson.decodeFromString(WaSimilarResponse.serializer(), "{}")
        assertTrue(r.results.isEmpty())
        assertEquals(0L, r.of)
    }

    // -- the repository ------------------------------------------------------------------------------

    /** Answers each request on a local socket with [status] and [body] and records the request lines: the real client, no network. */
    private fun serve(status: String, body: String, call: (HttpWhatsAppRepository) -> Unit): List<String> {
        val lines = mutableListOf<String>()
        ServerSocket(0, 5, InetAddress.getByName("127.0.0.1")).use { server ->
            val t = thread(isDaemon = true) {
                server.accept().use { s ->
                    val reader = s.getInputStream().bufferedReader()
                    lines += reader.readLine()
                    while (reader.readLine().orEmpty().isNotEmpty()) Unit
                    val bytes = body.toByteArray()
                    s.getOutputStream().write(
                        ("HTTP/1.1 $status\r\nContent-Type: text/plain\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n$body").toByteArray(),
                    )
                }
            }
            call(HttpWhatsAppRepository(ApiProvider("http://127.0.0.1:${server.localPort}")))
            t.join(10_000)
        }
        return lines
    }

    @Test
    fun `a 404 carries the server's reason as the message and a 200 decodes`() {
        var result: ApiResult<WaSimilarResponse>? = null
        val lines = serve("404 Not Found", "this conversation has no vector yet") { r -> runBlocking { result = r.chatSearchSimilar(5) } }
        assertTrue(lines.single(), lines.single().contains("api/whatsapp/chat-search/similar") && lines.single().contains("node=5"))
        val err = result as ApiResult.Err
        assertEquals(404, err.code)
        assertEquals("this conversation has no vector yet", err.message)

        serve("200 OK", """{"of":5,"results":[{"node_id":6}],"took_ms":3}""") { r -> runBlocking { result = r.chatSearchSimilar(5) } }
        assertEquals(6L, (result as ApiResult.Ok).value.results.single().nodeId)
    }

    @Test
    fun `a failure without a reason reads as the status`() {
        var result: ApiResult<WaSimilarResponse>? = null
        serve("500 Internal Server Error", "") { r -> runBlocking { result = r.chatSearchSimilar(5) } }
        assertEquals("HTTP 500", (result as ApiResult.Err).message)
    }

    // -- the state: asking -----------------------------------------------------------------------

    @Test
    fun `more like loads then shows the rows`() {
        val conv = opened()
        val gate = CompletableDeferred<Unit>()
        repo.similarGate = gate
        repo.similarResult = similarOf(3, 4)
        assertNull(conv.shownSimilar)

        conv.moreLike()
        assertEquals(SimilarPhase.LOADING, conv.shownSimilar?.phase)
        assertEquals(listOf(1L), repo.similarRequests)

        gate.complete(Unit)
        assertEquals(SimilarPhase.DONE, conv.shownSimilar?.phase)
        assertEquals(listOf(3L, 4L), conv.shownSimilar?.items?.map { it.nodeId })
    }

    @Test
    fun `an empty answer is done with no rows and asking twice sends one request`() {
        val conv = opened()
        conv.moreLike()
        conv.moreLike()
        assertEquals(SimilarPhase.DONE, conv.shownSimilar?.phase)
        assertTrue(conv.shownSimilar!!.items.isEmpty())
        assertEquals(1, repo.similarRequests.size)
    }

    @Test
    fun `at most eight rows are kept`() {
        repo.similarResult = similarOf(*(10L..30L).toList().toLongArray())
        val conv = opened()
        conv.moreLike()
        assertEquals(SIMILAR_MAX, conv.shownSimilar!!.items.size)
    }

    @Test
    fun `an error keeps the server's words and Try again asks again`() {
        val conv = opened()
        repo.similarResult = ApiResult.Err("this conversation has no vector yet", 404)
        conv.moreLike()
        assertEquals(SimilarPhase.ERROR, conv.shownSimilar?.phase)
        assertEquals("this conversation has no vector yet", conv.shownSimilar?.message)

        repo.similarResult = similarOf(3)
        conv.retrySimilar()
        assertEquals(SimilarPhase.DONE, conv.shownSimilar?.phase)
        assertEquals(2, repo.similarRequests.size)
    }

    @Test
    fun `an error with no words reads the general text`() {
        assertEquals(SIMILAR_FAILED, similarListOf(ApiResult.Err("  ", 500)).message)
        assertEquals(SimilarPhase.ERROR, similarListOf(ApiResult.Err("x", 500)).phase)
    }

    @Test
    fun `retry does nothing unless the list failed`() {
        val conv = opened()
        conv.retrySimilar()
        assertTrue(repo.similarRequests.isEmpty())
        conv.moreLike()
        conv.retrySimilar()
        assertEquals(1, repo.similarRequests.size)
    }

    // -- the state: the stack ----------------------------------------------------------------------

    @Test
    fun `opening a row pushes it and loads its card`() {
        val conv = opened()
        repo.similarResult = similarOf(3)
        conv.moreLike()
        repo.cardRequests.clear()
        repo.cardResult = ApiResult.Ok(WaCardDetail(id = 33))

        conv.openSimilar(conv.shownSimilar!!.items.single())

        assertEquals(listOf(3L), conv.similarStack.map { it.conv.nodeId })
        assertEquals(3L, conv.shownConv?.nodeId)
        assertEquals(listOf(3L), repo.cardRequests)
        assertEquals(CardPhase.DONE, conv.cardSheet?.phase)
        assertEquals(33L, conv.cardSheet?.detail?.id)
        assertNull("the pushed card has not asked for its own list", conv.shownSimilar)
    }

    @Test
    fun `back pops to the earlier card and its list without a new request`() {
        val conv = opened()
        repo.similarResult = similarOf(3)
        conv.moreLike()
        conv.openSimilar(conv.shownSimilar!!.items.single())
        repo.cardRequests.clear()
        repo.similarRequests.clear()

        assertTrue(conv.backFromSimilar())

        assertTrue(conv.similarStack.isEmpty())
        assertEquals(1L, conv.shownConv?.nodeId)
        assertEquals(listOf(3L), conv.shownSimilar?.items?.map { it.nodeId })
        assertTrue("the earlier card is kept, not read again", repo.cardRequests.isEmpty())
        assertTrue(repo.similarRequests.isEmpty())
        assertEquals(CardPhase.DONE, conv.cardSheet?.phase)
    }

    @Test
    fun `back with nothing pushed does nothing`() {
        val conv = opened()
        assertFalse(conv.backFromSimilar())
        assertNotNull(conv.cardSheet)
    }

    @Test
    fun `a pushed card can ask for its own list and Back keeps both`() {
        val conv = opened()
        repo.similarResult = similarOf(3)
        conv.moreLike()
        conv.openSimilar(conv.shownSimilar!!.items.single())
        repo.similarResult = similarOf(4, 5)
        conv.moreLike()
        assertEquals(listOf(1L, 3L), repo.similarRequests)
        assertEquals(listOf(4L, 5L), conv.shownSimilar?.items?.map { it.nodeId })

        conv.backFromSimilar()
        assertEquals(listOf(3L), conv.shownSimilar?.items?.map { it.nodeId })
    }

    @Test
    fun `closing the sheet clears the stack and the lists`() {
        val conv = opened()
        repo.similarResult = similarOf(3)
        conv.moreLike()
        conv.openSimilar(conv.shownSimilar!!.items.single())

        conv.closeCard()

        assertTrue(conv.similarStack.isEmpty())
        assertTrue(conv.similar.isEmpty())
        assertNull(conv.shownConv)
        conv.openCard(0)
        assertNull("a reopened sheet starts without a list", conv.shownSimilar)
    }

    @Test
    fun `an answer that lands after the sheet closed is dropped`() {
        val conv = opened()
        val gate = CompletableDeferred<Unit>()
        repo.similarGate = gate
        repo.similarResult = similarOf(3)
        conv.moreLike()
        conv.closeCard()
        conv.openCard(0)

        gate.complete(Unit)

        assertNull(conv.shownSimilar)
        assertTrue(conv.similar.isEmpty())
    }

    @Test
    fun `an answer for a card that was popped is dropped and the card can ask again`() {
        val conv = opened()
        repo.similarResult = similarOf(3)
        conv.moreLike()
        conv.openSimilar(conv.shownSimilar!!.items.single())
        val gate = CompletableDeferred<Unit>()
        repo.similarGate = gate
        repo.similarResult = similarOf(4)
        conv.moreLike()
        assertEquals(SimilarPhase.LOADING, conv.shownSimilar?.phase)

        conv.backFromSimilar()
        gate.complete(Unit)

        assertEquals("only the earlier card's list is left", setOf(1L), conv.similar.keys)
        repo.similarGate = null
        conv.openSimilar(conv.similar.getValue(1L).items.single())
        assertNull(conv.shownSimilar)
        conv.moreLike()
        assertEquals(listOf(4L), conv.shownSimilar?.items?.map { it.nodeId })
    }

    @Test
    fun `a card read that lands after a pop is dropped and the card shown is the earlier one`() {
        val conv = opened()
        repo.similarResult = similarOf(3)
        conv.moreLike()
        val cardGate = CompletableDeferred<Unit>()
        repo.cardGate = cardGate
        repo.cardResult = ApiResult.Ok(WaCardDetail(id = 99))
        conv.openSimilar(conv.shownSimilar!!.items.single())
        assertEquals(CardPhase.LOADING, conv.cardSheet?.phase)

        conv.backFromSimilar()
        cardGate.complete(Unit)

        assertEquals(1L, conv.shownConv?.nodeId)
        assertEquals("the earlier card was loaded before", CardPhase.DONE, conv.cardSheet?.phase)
        assertTrue(conv.cardSheet?.detail?.id != 99L)
    }

    @Test
    fun `a 404 on a pushed card is a missing card and Retry reads that card again`() {
        val conv = opened()
        repo.similarResult = similarOf(3)
        conv.moreLike()
        repo.cardResult = ApiResult.Err("none", 404)
        conv.openSimilar(conv.shownSimilar!!.items.single())
        assertEquals(CardPhase.MISSING, conv.cardSheet?.phase)

        repo.cardRequests.clear()
        repo.cardResult = ApiResult.Err("down", 500)
        conv.retryCard()
        assertEquals(listOf(3L), repo.cardRequests)
        assertEquals(CardPhase.ERROR, conv.cardSheet?.phase)
    }

    // -- the words -----------------------------------------------------------------------------------

    @Test
    fun `a row's title is its card title, or the chat name without a card`() {
        assertEquals("Beach day", similarRowTitle(conv(1, "Beach day"), "Chat 1"))
        assertEquals("Chat 1", similarRowTitle(conv(1), "Chat 1"))
        assertEquals("a blank title falls back", "Chat 1", similarRowTitle(conv(1, "  "), "Chat 1"))
    }

    @Test
    fun `a row's second line is the chat name, a dot and the range`() {
        assertEquals("Chat 1 · Today · 19:02–20:10", similarRowMeta("Chat 1", "Today · 19:02–20:10"))
    }

    @Test
    fun `Found it is offered only for a result, not for a pushed card`() {
        assertTrue(foundOffered(0))
        assertFalse(foundOffered(1))
        assertFalse(foundOffered(3))
    }
}
