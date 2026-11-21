package com.raavivi.sysmon.ui.health

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.raavivi.sysmon.core.data.autofix.AutoFixRepository
import com.raavivi.sysmon.core.log.AppLog
import com.raavivi.sysmon.core.model.AutoFixResponse
import com.raavivi.sysmon.core.model.AutoFixStates
import com.raavivi.sysmon.core.model.AutoFixTurn
import com.raavivi.sysmon.core.net.ApiResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How often a running fix is polled while the Health screen is showing. */
const val AUTOFIX_POLL_MS = 3_000L

/** The follow-up limit the server enforces. */
const val AUTOFIX_MAX_REPLY = 500

private const val MAX_POLL_FAILURES = 5

/**
 * The Auto fix card's whole behaviour: start, the single follow-up, and polling while a run is going. The
 * screen calls [attach] when it is on top and [detach] when it leaves; polling only runs in between.
 * Reply and follow-up text is never logged, only status codes.
 */
class AutoFixState(
    private val repo: AutoFixRepository,
    private val scope: CoroutineScope,
) {
    var data by mutableStateOf<AutoFixResponse?>(null)
        private set

    /** The server answered 404 (or `enabled: false`): the card is not shown at all. */
    var hidden by mutableStateOf(false)
        private set

    /** A start or reply request is in flight. */
    var busy by mutableStateOf(false)
        private set

    /** The last request failed; plain words, cleared by the next action. */
    var error by mutableStateOf<String?>(null)
        private set

    /** Set when a run flips to `done` while attached; the screen taps the haptic and calls [consumeFinished]. */
    var finished by mutableStateOf(false)
        private set

    private var attached = false
    private var poller: Job? = null

    val phase: AutoFixPhase
        get() = when (data?.state) {
            AutoFixStates.RUNNING -> AutoFixPhase.Running
            AutoFixStates.DONE -> AutoFixPhase.Done
            AutoFixStates.FAILED -> AutoFixPhase.Failed
            else -> AutoFixPhase.Idle
        }

    /** Replies in time order, newest last. */
    val turns: List<AutoFixTurn>
        get() = data?.turns.orEmpty().sortedBy { it.at }

    /** One follow-up is allowed after a run ends, and only while the server says so. */
    val canFollowUp: Boolean
        get() = data?.followUpAllowed == true && (phase == AutoFixPhase.Done || phase == AutoFixPhase.Failed)

    fun attach() {
        attached = true
        scope.launch { refresh() }
    }

    fun detach() {
        attached = false
        poller?.cancel()
        poller = null
    }

    fun consumeFinished() {
        finished = false
    }

    fun start() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            val r = repo.start()
            busy = false
            when (r) {
                is ApiResult.Ok -> apply(r.value)
                is ApiResult.Err -> {
                    AppLog.w("AutoFix", "start code=${r.code}")
                    when (r.code) {
                        409 -> refresh() // already running: pick it up and follow it
                        else -> fail(r)
                    }
                }
            }
        }
    }

    /** Sends the one follow-up. Empty or over-long text never reaches the server. */
    fun reply(text: String) {
        val trimmed = text.trim()
        if (busy || trimmed.isEmpty() || trimmed.length > AUTOFIX_MAX_REPLY) return
        busy = true
        error = null
        scope.launch {
            val r = repo.reply(trimmed)
            busy = false
            when (r) {
                is ApiResult.Ok -> apply(r.value)
                is ApiResult.Err -> {
                    AppLog.w("AutoFix", "reply code=${r.code}")
                    if (r.code == 409) refresh() // not allowed (any more): show what the server has
                    fail(r)
                }
            }
        }
    }

    /** One status read; also the entry point of polling when the run is still going. */
    private suspend fun refresh() {
        when (val r = repo.status()) {
            is ApiResult.Ok -> apply(r.value)
            is ApiResult.Err -> {
                AppLog.w("AutoFix", "status code=${r.code}")
                if (r.code == 404) hidden = true
            }
        }
    }

    private fun apply(next: AutoFixResponse) {
        val was = data?.state
        if (!next.enabled) {
            hidden = true
            return
        }
        hidden = false
        data = next
        if (next.state == AutoFixStates.DONE && was == AutoFixStates.RUNNING && attached) finished = true
        if (next.state == AutoFixStates.RUNNING) pollWhileRunning() else poller?.cancel()
    }

    private fun fail(r: ApiResult.Err) {
        if (r.code == 404) {
            hidden = true
            return
        }
        error = when (r.code) {
            409 -> "That is not available right now"
            400 -> r.message
            else -> r.message.ifBlank { "Something went wrong" }
        }
    }

    private fun pollWhileRunning() {
        if (!attached || poller?.isActive == true) return
        poller = scope.launch {
            var failures = 0
            while (data?.state == AutoFixStates.RUNNING) {
                delay(AUTOFIX_POLL_MS)
                when (val r = repo.status()) {
                    is ApiResult.Ok -> {
                        failures = 0
                        apply(r.value)
                        if (r.value.state != AutoFixStates.RUNNING) return@launch
                    }
                    is ApiResult.Err -> {
                        AppLog.w("AutoFix", "poll code=${r.code}")
                        if (r.code == 404) {
                            hidden = true
                            return@launch
                        }
                        if (++failures >= MAX_POLL_FAILURES) {
                            error = "Lost contact with the server"
                            return@launch
                        }
                    }
                }
            }
        }
    }
}

enum class AutoFixPhase { Idle, Running, Done, Failed }

/** Wording and clock helpers for the card; pure so they can be tested. */
object AutoFixText {
    /** "2:05" for 125 s; negative becomes 0. */
    fun clock(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    /**
     * Seconds the run has been going. The phone and server clocks can disagree, so a start time that is in the
     * future or more than a day old counts as unknown (0) rather than showing nonsense.
     */
    fun elapsedSeconds(startedAt: Double?, nowSeconds: Long): Long {
        val started = startedAt?.toLong() ?: return 0
        val d = nowSeconds - started
        return if (d in 0..86_400) d else 0
    }

    /** How long a finished run took, from the server's own two timestamps. */
    fun tookSeconds(startedAt: Double?, finishedAt: Double?): Long? {
        if (startedAt == null || finishedAt == null) return null
        val d = (finishedAt - startedAt).toLong()
        return d.takeIf { it >= 0 }
    }

    /** The server's failure kind in plain words. */
    fun failure(error: String?): String {
        val kind = error?.trim()?.lowercase().orEmpty()
        return when {
            kind.isEmpty() -> "The agent could not finish."
            "time" in kind -> "The agent ran out of time."
            "unavailable" in kind || "spawn" in kind || "not_found" in kind || "not found" in kind ->
                "The agent could not be started."
            "auth" in kind || "login" in kind -> "The agent is not signed in."
            else -> "The agent stopped: ${kind.replace('_', ' ').take(60)}."
        }
    }
}
