package com.raavivi.sysmon

import com.raavivi.sysmon.ui.common.snake.SnakeConfig
import com.raavivi.sysmon.ui.common.snake.SnakeEngine
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnakeEngineTest {
    private fun engine(seed: Int = 1, cols: Int = 40, rows: Int = 20) =
        SnakeEngine(cols, rows, Random(seed))

    private fun snapshot(e: SnakeEngine) = (0 until e.length).map { e.cellAt(it) } + listOf(e.food, e.level)

    private fun torusAdjacent(e: SnakeEngine, a: Int, b: Int): Boolean {
        val dx = Math.abs(e.cellX(a) - e.cellX(b))
        val dy = Math.abs(e.cellY(a) - e.cellY(b))
        return Math.min(dx, e.cols - dx) + Math.min(dy, e.rows - dy) == 1
    }

    @Test
    fun neverReverses() {
        val e = engine(seed = 7)
        var p = 0f
        repeat(4000) { i ->
            if (i % 40 == 0) { p = (p + 0.01f).coerceAtMost(1f); e.setProgress(p) }
            val neck = if (e.length >= 2) e.cellAt(1) else -1
            val level = e.level
            val relays = e.relays
            val dir = e.dir
            e.step()
            if (e.level == level && e.relays == relays) {
                assertTrue("reversed", e.dir != (dir + 2) % 4)
                assertTrue("stepped onto neck", e.cellAt(1) != e.cellAt(0) && e.cellAt(0) != neck)
            }
        }
    }

    @Test
    fun lengthTracksTarget() {
        val e = engine(seed = 3)
        e.setProgress(0.1f)
        repeat(6000) { e.step() }
        assertEquals(0, e.level)
        assertEquals(e.targetLen, e.length)
        assertTrue(e.targetLen > SnakeConfig.MIN_LEN)
    }

    @Test
    fun pullBackHalvesCoverage() {
        val e = engine(seed = 5)
        e.setProgress(1f)
        var before = 0
        var guard = 0
        while (e.level == 0 && guard++ < 20000) {
            before = e.length
            e.step()
        }
        assertEquals(1, e.level)
        assertEquals(2f, e.scale, 0f)
        assertTrue("before=$before after=${e.length}", e.length <= before * 0.65)
        assertTrue("before=$before after=${e.length}", e.length >= before * 0.3)
    }

    @Test
    fun pullBackEasesScaleToOne() {
        val e = engine(seed = 5)
        e.setProgress(1f)
        var guard = 0
        while (!e.zooming && guard++ < 20000) e.step()
        assertTrue(e.zooming)
        e.advance(50, running = false)
        assertTrue(e.scale < 2f && e.scale > 1f)
        repeat(10) { e.advance(100, running = false) }
        assertEquals(1f, e.scale, 0f)
        assertTrue(!e.zooming)
    }

    @Test
    fun wrapAroundMovesStayOnBoard() {
        val e = engine(seed = 11, cols = 12, rows = 8)
        var wrapped = false
        var p = 0f
        repeat(3000) { i ->
            if (i % 30 == 0) { p = (p + 0.01f).coerceAtMost(1f); e.setProgress(p) }
            val prev = e.cellAt(0)
            val level = e.level
            val relays = e.relays
            e.step()
            for (k in 0 until e.length) {
                val c = e.cellAt(k)
                assertTrue(c in 0 until e.cols * e.rows)
            }
            if (e.level == level && e.relays == relays) {
                val head = e.cellAt(0)
                assertTrue("head jumped", torusAdjacent(e, prev, head))
                if (Math.abs(e.cellX(prev) - e.cellX(head)) > 1 || Math.abs(e.cellY(prev) - e.cellY(head)) > 1) {
                    wrapped = true
                }
            }
        }
        assertTrue("never crossed an edge", wrapped)
    }

    @Test
    fun seededRunsRepeat() {
        fun run(seed: Int): List<Int> {
            val e = engine(seed)
            e.setProgress(0.6f)
            repeat(1500) { e.advance(80, running = true) }
            return snapshot(e)
        }
        assertEquals(run(42), run(42))
    }

    @Test
    fun differentSeedsDiffer() {
        fun run(seed: Int): List<Int> {
            val e = engine(seed)
            e.setProgress(0.3f)
            repeat(300) { e.step() }
            return snapshot(e)
        }
        assertTrue(run(1) != run(2))
    }

    @Test
    fun staticLayoutFitsBoardAndHasNoFood() {
        val e = engine()
        e.setProgress(1f)
        e.layoutStatic()
        assertEquals(e.targetLen, e.length)
        assertTrue(e.length <= SnakeConfig.ZOOM_AT * e.cols * e.rows + 1)
        assertEquals(-1, e.food)
        assertTrue(e.level > 0)
    }

    @Test
    fun busyModeKeepsGrowingBelowCeiling() {
        val e = engine()
        e.busy = true
        repeat(400) { e.advance(80, running = true) }
        assertTrue(e.progress > 0.5f)
        assertTrue(e.progress < SnakeConfig.BUSY_CEILING)
    }

    @Test
    fun busyStepApproachesCeiling() {
        var p = 0f
        repeat(500) { p = SnakeConfig.busyStep(p) }
        assertTrue(p < SnakeConfig.BUSY_CEILING)
        assertTrue(p > SnakeConfig.BUSY_CEILING - 0.001f)
    }

    @Test
    fun cellsForIsEvenAndAtLeastFour() {
        assertEquals(4, SnakeConfig.cellsFor(5f, 6f))
        assertEquals(10, SnakeConfig.cellsFor(67f, 6f))
    }
}
