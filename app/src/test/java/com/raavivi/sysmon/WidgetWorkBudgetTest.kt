package com.raavivi.sysmon

import com.raavivi.sysmon.core.net.safeCall
import com.raavivi.sysmon.widget.PlugWidgetActionReceiver
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the load-bearing property of the widget receiver's work budget. The
 * receiver itself needs BroadcastReceiver.goAsync, i.e. an Android framework
 * test, so what this JVM test can honestly pin is the budget's half of the
 * contract: [safeCall] swallows the CancellationException a timeout sends,
 * yet the budget still ends the wait inside the ~10s broadcast deadline.
 */
class WidgetWorkBudgetTest {

    @Test
    fun `the budget stays inside the foreground broadcast deadline`() {
        assertTrue(PlugWidgetActionReceiver.WORK_BUDGET_MS < 10_000L)
    }

    @Test
    fun `a hung network call inside safeCall still ends at the budget`() = runTest {
        // A Retrofit suspend call parks like this until cancelled — the exact
        // shape of a request the server is too slow to answer.
        val hungCall: suspend () -> Unit = { suspendCancellableCoroutine { } }

        assertNull(withTimeoutOrNull(PlugWidgetActionReceiver.WORK_BUDGET_MS) { safeCall(hungCall) })
    }
}
