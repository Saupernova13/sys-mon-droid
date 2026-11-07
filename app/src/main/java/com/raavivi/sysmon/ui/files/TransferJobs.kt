package com.raavivi.sysmon.ui.files

import com.raavivi.sysmon.core.model.FsJob
import java.util.Locale

/**
 * Pure state logic for transfer jobs; port of sys-mon `transfer-state.js` and the
 * helpers in `transfer-progress.js`. No Compose, no I/O: the view-model owns the
 * polling loop and the requests.
 */

/** Client-only state: contact was lost, the job may still be running on the host. */
const val STATE_LOST = "lost"

const val POLL_ATTACHED_MS = 400L
const val POLL_DETACHED_MS = 2000L
const val MAX_POLL_FAILURES = 6
const val CANCEL_RETRY_MS = 8000L
const val AUTO_CLOSE_MS = 1500L

/** Server-final states: polling can stop. */
fun isFinished(state: String): Boolean = state == "done" || state == "failed" || state == "cancelled"

/** Finished, or contact lost. */
fun isTerminal(state: String): Boolean = isFinished(state) || state == STATE_LOST

// ── Polling ────────────────────────────────────────────────────────────────────

enum class PollKind { Snapshot, Retry, Lost }

data class PollOutcome(val kind: PollKind, val failures: Int)

/**
 * Classify one poll. A snapshot resets the failure count; a failure is retried
 * until [MAX_POLL_FAILURES] in a row, or at once when the host no longer knows the
 * job (it will never come back).
 */
fun classifyPoll(snapshot: FsJob?, errorMessage: String?, failures: Int): PollOutcome {
    if (snapshot != null) return PollOutcome(PollKind.Snapshot, 0)
    val n = failures + 1
    val unknown = errorMessage?.contains("no such job", ignoreCase = true) == true
    return PollOutcome(if (n >= MAX_POLL_FAILURES || unknown) PollKind.Lost else PollKind.Retry, n)
}

/** The last snapshot seen, marked as lost. */
fun lostJob(base: FsJob): FsJob = base.copy(state = STATE_LOST)

// ── Cancel button ──────────────────────────────────────────────────────────────

enum class CancelState { Idle, Requested, Retired }

enum class CancelEvent { Click, RequestFailed, GraceElapsed, Settled }

/**
 * idle -> requested; back to idle if the request fails or the job still runs after
 * [CANCEL_RETRY_MS]; retired for good once the job is finished or lost.
 */
fun nextCancel(state: CancelState, event: CancelEvent): CancelState {
    if (state == CancelState.Retired) return CancelState.Retired
    return when (event) {
        CancelEvent.Click -> if (state == CancelState.Idle) CancelState.Requested else state
        CancelEvent.RequestFailed -> CancelState.Idle
        CancelEvent.GraceElapsed -> if (state == CancelState.Requested) CancelState.Idle else state
        CancelEvent.Settled -> CancelState.Retired
    }
}

data class CancelView(val label: String, val enabled: Boolean)

fun cancelView(state: CancelState): CancelView =
    if (state == CancelState.Requested) CancelView("Cancelling…", false) else CancelView("Cancel", true)

// ── Display ────────────────────────────────────────────────────────────────────

/** 0..1. Deletes count items; the rest count bytes once the total is known. */
fun progressFraction(job: FsJob): Float {
    if (job.state == "done") return 1f
    if (job.state == "scanning") return 0f
    val byBytes = job.op != "delete" && (job.bytesTotal ?: 0) > 0
    val total = (if (byBytes) job.bytesTotal else job.filesTotal) ?: 0
    val done = (if (byBytes) job.bytesDone else job.filesDone) ?: 0
    return if (total > 0) (done.toDouble() / total).coerceIn(0.0, 1.0).toFloat() else 0f
}

/** Seconds left, or null when there is not enough to say. */
fun etaSeconds(job: FsJob): Double? {
    if (isTerminal(job.state) || job.state == "scanning") return null
    val speed = job.speed ?: 0.0
    val bytesTotal = job.bytesTotal ?: 0
    if (job.op != "delete" && bytesTotal > 0 && speed > 0) {
        return maxOf(0L, bytesTotal - (job.bytesDone ?: 0)) / speed
    }
    val filesDone = job.filesDone ?: 0
    val elapsed = job.elapsed ?: 0.0
    if (filesDone > 0 && elapsed > 0) {
        return elapsed * maxOf(0L, (job.filesTotal ?: 0) - filesDone) / filesDone
    }
    return null
}

/** `42s`, `3m 05s`, `1h 02m`; an em dash when unknown. */
fun formatSeconds(secs: Double?): String {
    if (secs == null || !secs.isFinite() || secs < 0) return "—"
    val s = Math.round(secs)
    if (s < 60) return "${s}s"
    val m = s / 60
    if (m < 60) return String.format(Locale.US, "%dm %02ds", m, s % 60)
    return String.format(Locale.US, "%dh %02dm", m / 60, m % 60)
}

/** Auto-close applies only to a clean `done` shown in the compact view. */
fun shouldAutoClose(job: FsJob, detailsView: Boolean): Boolean =
    job.state == "done" && job.errors.isEmpty() && !detailsView

private fun verbOf(op: String): String = when (op) {
    "copy" -> "Copying"
    "move" -> "Moving"
    "delete" -> "Deleting"
    "extract" -> "Extracting"
    "compress" -> "Compressing"
    else -> "Working"
}

private fun verbDoneOf(op: String): String = when (op) {
    "copy" -> "Copied"
    "move" -> "Moved"
    "delete" -> "Deleted"
    "extract" -> "Extracted"
    "compress" -> "Compressed"
    else -> "Done"
}

/** Headline for the progress card, e.g. "Copying 3 items" or "Copied". */
fun jobVerb(job: FsJob): String = when (job.state) {
    "done" -> verbDoneOf(job.op)
    "failed" -> "${verbOf(job.op)} failed"
    "cancelled" -> "Cancelled"
    STATE_LOST -> "Lost track"
    "scanning" -> "Counting…"
    else -> verbOf(job.op)
}

fun itemsLabel(count: Int): String = if (count == 1) "1 item" else "$count items"

/** Status line after a terminal state: "Copied 3 items" or "Finished with 2 errors". */
fun finishMessage(job: FsJob): String {
    val n = job.sources.size
    return when {
        job.state == STATE_LOST -> "Lost track of ${verbOf(job.op).lowercase()} job"
        job.state == "cancelled" -> "Cancelled"
        job.state == "failed" -> "${verbOf(job.op)} failed"
        job.errors.isNotEmpty() ->
            "Finished with ${job.errors.size} ${if (job.errors.size == 1) "error" else "errors"}"
        else -> "${verbDoneOf(job.op)} ${itemsLabel(n)}"
    }
}

/** Last path segment of either separator style. */
fun baseName(path: String): String =
    path.trimEnd('\\', '/').substringAfterLast('\\').substringAfterLast('/').ifEmpty { path }
