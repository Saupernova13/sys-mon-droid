package com.raavivi.sysmon

import com.raavivi.sysmon.core.data.autofix.AutoFixRepository
import com.raavivi.sysmon.core.model.AutoFixResponse
import com.raavivi.sysmon.core.model.AutoFixTurn
import com.raavivi.sysmon.core.model.FeaturesResponse
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.SysMonJson
import com.raavivi.sysmon.ui.health.AUTOFIX_POLL_MS
import com.raavivi.sysmon.ui.health.AutoFixPhase
import com.raavivi.sysmon.ui.health.AutoFixState
import com.raavivi.sysmon.ui.health.AutoFixText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A scriptable [AutoFixRepository]: status repeats its last answer, start and reply pop theirs. Made-up data only. */
private class FakeAutoFixRepository : AutoFixRepository {
    val statusAnswers = ArrayDeque<ApiResult<AutoFixResponse>>()
    val startAnswers = ArrayDeque<ApiResult<AutoFixResponse>>()
    val replyAnswers = ArrayDeque<ApiResult<AutoFixResponse>>()
    var statusCalls = 0
    var startCalls = 0
    val replies = mutableListOf<String>()

    override suspend fun status(): ApiResult<AutoFixResponse> {
        statusCalls++
        return if (statusAnswers.size > 1) statusAnswers.removeFirst() else statusAnswers.first()
    }

    override suspend fun start(): ApiResult<AutoFixResponse> {
        startCalls++
        return startAnswers.removeFirst()
    }

    override suspend fun reply(text: String): ApiResult<AutoFixResponse> {
        replies += text
        return replyAnswers.removeFirst()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AutoFixStateTest {

    private val sched = TestCoroutineScheduler()
    private val repo = FakeAutoFixRepository()
    private val state = AutoFixState(repo, CoroutineScope(StandardTestDispatcher(sched)))

    private fun ok(
        s: String,
        followUp: Boolean = false,
        turns: List<AutoFixTurn> = emptyList(),
        error: String? = null,
    ): ApiResult<AutoFixResponse> =
        ApiResult.Ok(AutoFixResponse(state = s, turns = turns, followUpAllowed = followUp, error = error, startedAt = 100.0))

    private fun err(code: Int, message: String = "nope"): ApiResult<AutoFixResponse> = ApiResult.Err(message, code)

    private fun run() = sched.runCurrent()

    private fun advance(ms: Long) {
        sched.advanceTimeBy(ms)
        sched.runCurrent()
    }

    @Test
    fun `attach reads the idle state`() {
        repo.statusAnswers += ok("idle")
        state.attach()
        run()
        assertEquals(AutoFixPhase.Idle, state.phase)
        assertFalse(state.hidden)
    }

    @Test
    fun `404 hides the card and stops everything`() {
        repo.statusAnswers += err(404)
        state.attach()
        run()
        assertTrue(state.hidden)
        advance(AUTOFIX_POLL_MS * 3)
        assertEquals(1, repo.statusCalls)
    }

    @Test
    fun `enabled false hides the card`() {
        repo.statusAnswers += ApiResult.Ok(AutoFixResponse(enabled = false))
        state.attach()
        run()
        assertTrue(state.hidden)
    }

    @Test
    fun `start then poll every 3 s until done`() {
        repo.statusAnswers += ok("idle")
        state.attach()
        run()
        repo.startAnswers += ok("running")
        repo.statusAnswers.clear()
        repo.statusAnswers += ok("running")
        repo.statusAnswers += ok("done", followUp = true, turns = listOf(AutoFixTurn("agent", "all good", 200.0)))
        val before = repo.statusCalls
        state.start()
        run()
        assertEquals(AutoFixPhase.Running, state.phase)
        advance(AUTOFIX_POLL_MS)
        assertEquals(before + 1, repo.statusCalls)
        assertEquals(AutoFixPhase.Running, state.phase)
        advance(AUTOFIX_POLL_MS)
        assertEquals(AutoFixPhase.Done, state.phase)
        assertTrue(state.finished)
        assertTrue(state.canFollowUp)
        // Polling stops once done.
        val after = repo.statusCalls
        advance(AUTOFIX_POLL_MS * 5)
        assertEquals(after, repo.statusCalls)
        state.consumeFinished()
        assertFalse(state.finished)
    }

    @Test
    fun `start 409 picks up the run that is already going`() {
        repo.statusAnswers += ok("running")
        repo.startAnswers += err(409)
        state.attach()
        run()
        state.start()
        run()
        assertEquals(AutoFixPhase.Running, state.phase)
        assertNull(state.error)
    }

    @Test
    fun `detach stops polling`() {
        repo.statusAnswers += ok("running")
        state.attach()
        run()
        advance(AUTOFIX_POLL_MS)
        val calls = repo.statusCalls
        state.detach()
        advance(AUTOFIX_POLL_MS * 5)
        assertEquals(calls, repo.statusCalls)
    }

    @Test
    fun `reply sends once then the server stops allowing more`() {
        repo.statusAnswers += ok("done", followUp = true)
        state.attach()
        run()
        assertTrue(state.canFollowUp)
        repo.replyAnswers += ok("running")
        state.reply("  the plug is on the second floor  ")
        run()
        assertEquals(listOf("the plug is on the second floor"), repo.replies)
        assertEquals(AutoFixPhase.Running, state.phase)
        repo.statusAnswers.clear()
        repo.statusAnswers += ok(
            "done",
            followUp = false,
            turns = listOf(
                AutoFixTurn("agent", "second", 3.0),
                AutoFixTurn("agent", "first", 1.0),
                AutoFixTurn("user", "detail", 2.0),
            ),
        )
        advance(AUTOFIX_POLL_MS)
        assertEquals(AutoFixPhase.Done, state.phase)
        assertFalse(state.canFollowUp)
        assertEquals(listOf("first", "detail", "second"), state.turns.map { it.text })
    }

    @Test
    fun `empty and over long replies never reach the server`() {
        repo.statusAnswers += ok("done", followUp = true)
        state.attach()
        run()
        state.reply("   ")
        state.reply("x".repeat(501))
        run()
        assertTrue(repo.replies.isEmpty())
        repo.replyAnswers += ok("running")
        state.reply("x".repeat(500))
        run()
        assertEquals(1, repo.replies.size)
    }

    @Test
    fun `reply 409 refreshes and shows a plain message`() {
        repo.statusAnswers += ok("done", followUp = false)
        repo.replyAnswers += err(409, "conflict")
        state.reply("more detail")
        run()
        assertEquals("That is not available right now", state.error)
        assertFalse(state.canFollowUp)
    }

    @Test
    fun `failed run keeps the error kind for plain wording`() {
        repo.statusAnswers += ok("failed", error = "timeout")
        state.attach()
        run()
        assertEquals(AutoFixPhase.Failed, state.phase)
        assertEquals("The agent ran out of time.", AutoFixText.failure(state.data?.error))
    }

    @Test
    fun `five failed polls give up with a message`() {
        repo.statusAnswers += ok("running")
        state.attach()
        run()
        repo.statusAnswers.clear()
        repo.statusAnswers += err(503, "down")
        advance(AUTOFIX_POLL_MS * 6)
        assertEquals("Lost contact with the server", state.error)
    }

    @Test
    fun `text helpers`() {
        assertEquals("2:05", AutoFixText.clock(125))
        assertEquals("0:00", AutoFixText.clock(-4))
        assertEquals(30L, AutoFixText.elapsedSeconds(70.0, 100))
        assertEquals(0L, AutoFixText.elapsedSeconds(500.0, 100))
        assertEquals(20L, AutoFixText.tookSeconds(100.0, 120.0))
        assertNull(AutoFixText.tookSeconds(null, 120.0))
        assertEquals("The agent could not finish.", AutoFixText.failure(null))
        assertEquals("The agent stopped: some odd kind.", AutoFixText.failure("some_odd_kind"))
    }

    @Test
    fun `decodes leniently`() {
        val full = SysMonJson.decodeFromString(
            AutoFixResponse.serializer(),
            """{"enabled":true,"state":"done","started_at":1700000000,"finished_at":1700000100.5,"turns":[{"role":"agent","text":"fine","at":1700000100}],"follow_up_allowed":true,"error":null,"extra":1}""",
        )
        assertEquals("done", full.state)
        assertTrue(full.followUpAllowed)
        assertEquals(1, full.turns.size)
        val bare = SysMonJson.decodeFromString(AutoFixResponse.serializer(), "{}")
        assertEquals("idle", bare.state)
        assertTrue(bare.turns.isEmpty())
    }

    @Test
    fun `features flag defaults off`() {
        assertFalse(FeaturesResponse().autofix)
        assertTrue(SysMonJson.decodeFromString(FeaturesResponse.serializer(), """{"autofix":true}""").autofix)
    }
}
