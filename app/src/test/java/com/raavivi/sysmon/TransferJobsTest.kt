package com.raavivi.sysmon

import com.raavivi.sysmon.core.model.FsJob
import com.raavivi.sysmon.core.model.FsJobError
import com.raavivi.sysmon.ui.files.CANCEL_RETRY_MS
import com.raavivi.sysmon.ui.files.CancelEvent
import com.raavivi.sysmon.ui.files.CancelState
import com.raavivi.sysmon.ui.files.PollKind
import com.raavivi.sysmon.ui.files.cancelView
import com.raavivi.sysmon.ui.files.classifyPoll
import com.raavivi.sysmon.ui.files.etaSeconds
import com.raavivi.sysmon.ui.files.finishMessage
import com.raavivi.sysmon.ui.files.formatSeconds
import com.raavivi.sysmon.ui.files.lostJob
import com.raavivi.sysmon.ui.files.nextCancel
import com.raavivi.sysmon.ui.files.progressFraction
import com.raavivi.sysmon.ui.files.shouldAutoClose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferJobsTest {
    private fun job(
        op: String = "copy",
        state: String = "running",
        sources: List<String> = listOf("a", "b"),
        filesTotal: Long? = 10,
        filesDone: Long? = 5,
        bytesTotal: Long? = 1000,
        bytesDone: Long? = 250,
        speed: Double? = 50.0,
        elapsed: Double? = 10.0,
        errors: List<FsJobError> = emptyList(),
    ) = FsJob(
        id = "j", op = op, state = state, sources = sources,
        filesTotal = filesTotal, filesDone = filesDone,
        bytesTotal = bytesTotal, bytesDone = bytesDone,
        speed = speed, elapsed = elapsed, errors = errors,
    )

    @Test fun fractionUsesBytesExceptForDelete() {
        assertEquals(0.25f, progressFraction(job()), 0.0001f)
        assertEquals(0.5f, progressFraction(job(op = "delete")), 0.0001f)
        // No byte total yet: falls back to files.
        assertEquals(0.5f, progressFraction(job(bytesTotal = 0)), 0.0001f)
        assertEquals(0f, progressFraction(job(state = "scanning")), 0f)
        assertEquals(1f, progressFraction(job(state = "done")), 0f)
        assertEquals(0f, progressFraction(job(filesTotal = null, bytesTotal = null)), 0f)
    }

    @Test fun etaFromSpeedOrItems() {
        assertEquals(15.0, etaSeconds(job())!!, 0.001)
        // Delete: items rate, 5 left at 0.5 files/s.
        assertEquals(10.0, etaSeconds(job(op = "delete"))!!, 0.001)
        assertNull(etaSeconds(job(state = "scanning")))
        assertNull(etaSeconds(job(state = "done")))
        assertNull(etaSeconds(job(speed = null, filesDone = 0)))
    }

    @Test fun durationFormat() {
        assertEquals("42s", formatSeconds(42.0))
        assertEquals("3m 05s", formatSeconds(185.0))
        assertEquals("1h 02m", formatSeconds(3720.0))
        assertEquals("—", formatSeconds(null))
    }

    @Test fun lostAfterSixConsecutiveFailures() {
        var failures = 0
        for (i in 1..5) {
            val o = classifyPoll(null, "Network error", failures)
            assertEquals(PollKind.Retry, o.kind)
            failures = o.failures
        }
        assertEquals(PollKind.Lost, classifyPoll(null, "Network error", failures).kind)
    }

    @Test fun snapshotResetsFailures() {
        val o = classifyPoll(job(), null, 4)
        assertEquals(PollKind.Snapshot, o.kind)
        assertEquals(0, o.failures)
    }

    @Test fun unknownJobIsLostImmediately() {
        assertEquals(PollKind.Lost, classifyPoll(null, "No such job", 0).kind)
        assertEquals("lost", lostJob(job()).state)
    }

    @Test fun cancelRearmsAfterGraceOrFailure() {
        assertEquals(8000L, CANCEL_RETRY_MS)
        var s = CancelState.Idle
        s = nextCancel(s, CancelEvent.Click)
        assertEquals(CancelState.Requested, s)
        assertFalse(cancelView(s).enabled)
        assertEquals("Cancelling…", cancelView(s).label)
        assertEquals(CancelState.Idle, nextCancel(s, CancelEvent.GraceElapsed))
        assertEquals(CancelState.Idle, nextCancel(s, CancelEvent.RequestFailed))
        assertTrue(cancelView(CancelState.Idle).enabled)
    }

    @Test fun cancelRetiresAndStaysRetired() {
        val r = nextCancel(CancelState.Requested, CancelEvent.Settled)
        assertEquals(CancelState.Retired, r)
        assertEquals(CancelState.Retired, nextCancel(r, CancelEvent.Click))
        assertEquals(CancelState.Retired, nextCancel(r, CancelEvent.GraceElapsed))
        // A grace timer firing while idle changes nothing.
        assertEquals(CancelState.Idle, nextCancel(CancelState.Idle, CancelEvent.GraceElapsed))
    }

    @Test fun autoCloseOnlyForCleanDoneInCompactView() {
        assertTrue(shouldAutoClose(job(state = "done"), detailsView = false))
        assertFalse(shouldAutoClose(job(state = "done"), detailsView = true))
        assertFalse(shouldAutoClose(job(state = "done", errors = listOf(FsJobError("p", "m"))), false))
        assertFalse(shouldAutoClose(job(state = "failed"), false))
        assertFalse(shouldAutoClose(job(state = "running"), false))
    }

    @Test fun finishMessages() {
        assertEquals("Copied 2 items", finishMessage(job(state = "done")))
        assertEquals("Moved 1 item", finishMessage(job(op = "move", state = "done", sources = listOf("a"))))
        assertEquals("Deleted 2 items", finishMessage(job(op = "delete", state = "done")))
        assertEquals(
            "Finished with 2 errors",
            finishMessage(job(state = "done", errors = listOf(FsJobError("a", "x"), FsJobError("b", "y")))),
        )
    }
}
