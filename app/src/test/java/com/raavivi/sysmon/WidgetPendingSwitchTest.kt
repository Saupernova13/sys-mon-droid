package com.raavivi.sysmon

import com.raavivi.sysmon.widget.PlugSnapshot
import com.raavivi.sysmon.widget.WidgetPlug
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state a widget shows between the tap and the server's answer.
 *
 * Without it the tile is visually identical from the moment it is tapped until
 * two round trips have completed, which is the whole of "the widgets feel
 * unresponsive". With it, the pill has to survive a reading that has not caught
 * up yet (or it flickers back to the old state), and it has to expire on its own
 * (or a plug that goes unreachable mid-switch leaves the pill stuck forever).
 */
class WidgetPendingSwitchTest {

    private fun plug(id: String, on: Boolean, available: Boolean = true) =
        WidgetPlug(
            id = id,
            name = id,
            available = available,
            relayOn = on,
            watts = if (on) 1200.0 else 0.0,
            costPerHour = 0.0,
        )

    private fun snapshot(vararg plugs: WidgetPlug) = PlugSnapshot(plugs = plugs.toList())

    @Test
    fun aTapIsShownImmediatelyAsTheDesiredState() {
        val snap = snapshot(plug("heater", on = false)).markPending("heater", true, atMs = 1_000)

        assertTrue(
            "the pill must read as the state that was asked for",
            snap.shownOn("heater", nowMs = 1_000),
        )
        assertTrue(snap.isPending("heater", nowMs = 1_000))
    }

    @Test
    fun aPlugWithNoPendingSwitchShowsItsRealState() {
        val snap = snapshot(plug("heater", on = true))

        assertTrue(snap.shownOn("heater", nowMs = 1_000))
        assertFalse(snap.isPending("heater", nowMs = 1_000))
    }

    // The server answers from its poller's cache, which can still describe the
    // pre-switch state for a whole poll interval. Dropping the pending mark on
    // that reading would flip the pill back and then flip it again.
    @Test
    fun aReadingThatHasNotCaughtUpKeepsThePendingState() {
        val asked = snapshot(plug("heater", on = false)).markPending("heater", true, atMs = 1_000)
        val stillStale = snapshot(plug("heater", on = false)).withPendingFrom(asked, nowMs = 2_000)

        assertTrue(stillStale.isPending("heater", nowMs = 2_000))
        assertTrue(stillStale.shownOn("heater", nowMs = 2_000))
    }

    @Test
    fun aReadingThatAgreesClearsThePendingState() {
        val asked = snapshot(plug("heater", on = false)).markPending("heater", true, atMs = 1_000)
        val caughtUp = snapshot(plug("heater", on = true)).withPendingFrom(asked, nowMs = 2_000)

        assertFalse(caughtUp.isPending("heater", nowMs = 2_000))
        assertTrue(caughtUp.shownOn("heater", nowMs = 2_000))
    }

    // A plug that stops answering mid-switch would otherwise never agree, and
    // the pill would sit "switching" until someone removed the widget.
    @Test
    fun aPendingSwitchExpiresOnItsOwn() {
        val snap = snapshot(plug("heater", on = false)).markPending("heater", true, atMs = 1_000)
        val late = 1_000 + PlugSnapshot.PENDING_TIMEOUT_MS + 1

        assertFalse(snap.isPending("heater", nowMs = late))
        assertFalse("an expired switch shows the real state again", snap.shownOn("heater", nowMs = late))
    }

    @Test
    fun anUnreachablePlugNeverSettlesAPendingSwitch() {
        val asked = snapshot(plug("heater", on = false)).markPending("heater", true, atMs = 1_000)
        val unreachable = snapshot(plug("heater", on = true, available = false))
            .withPendingFrom(asked, nowMs = 2_000)

        assertTrue(
            "a plug we cannot reach cannot confirm anything",
            unreachable.isPending("heater", nowMs = 2_000),
        )
    }

    @Test
    fun anExpiredSwitchIsDroppedByTheNextReading() {
        val asked = snapshot(plug("heater", on = false)).markPending("heater", true, atMs = 1_000)
        val late = 1_000 + PlugSnapshot.PENDING_TIMEOUT_MS + 1
        val next = snapshot(plug("heater", on = false)).withPendingFrom(asked, nowMs = late)

        assertTrue("an expired mark must not be carried forward", next.pending.isEmpty())
    }

    @Test
    fun aFailedSwitchDropsThePendingState() {
        val snap = snapshot(plug("heater", on = false))
            .markPending("heater", true, atMs = 1_000)
            .clearPending("heater")

        assertFalse(snap.isPending("heater", nowMs = 1_000))
        assertFalse(snap.shownOn("heater", nowMs = 1_000))
        assertNull(snap.pending["heater"])
    }

    @Test
    fun oneTapDoesNotMarkEveryPlugPending() {
        val snap = snapshot(plug("heater", on = false), plug("pc", on = true))
            .markPending("heater", true, atMs = 1_000)

        assertTrue(snap.isPending("heater", nowMs = 1_000))
        assertFalse(snap.isPending("pc", nowMs = 1_000))
        assertEquals(1, snap.pending.size)
    }
}
