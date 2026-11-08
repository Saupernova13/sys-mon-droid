package com.raavivi.sysmon.ui.common.snake

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/** Lifecycle of the transfer the snake illustrates. */
enum class SnakeStatus { Running, Done, Failed, Cancelled }

/** Tuning shared with the web snake (snake-progress.js). */
object SnakeConfig {
    /** Cell edge in dp. */
    const val CELL_DP = 6f
    /** Board coverage that triggers a pull-back. */
    const val ZOOM_AT = 0.38
    /** Progress at which the first pull-back lands. */
    const val FIRST_ZOOM_P = 0.175
    /** Doublings of world length over a full transfer. */
    const val K = 5
    const val ZOOM_MS = 500
    const val TICK_MS = 80
    const val MAX_STEPS_PER_FRAME = 6
    const val MIN_LEN = 3

    /** Busy mode: progress creeps toward this ceiling and never reaches it. */
    const val BUSY_CEILING = 0.9f
    const val BUSY_RATE = 0.06f
    const val BUSY_TICK_MS = 250

    /** Number of whole cells that fit [px] along one axis: even, at least 4. */
    fun cellsFor(px: Float, cellPx: Float): Int = max(4, 2 * floor(px / (2f * cellPx)).toInt())

    /** One busy creep step: closes a fixed share of the gap to the ceiling. */
    fun busyStep(p: Float): Float = p + (BUSY_CEILING - p) * BUSY_RATE

    /** Camera scale during a pull-back, [elapsedMs] after it began: eases 2 -> 1. */
    fun zoomScale(elapsedMs: Float): Float {
        val t = elapsedMs / ZOOM_MS
        return if (t >= 1f) 1f else 1f + (1f - easeOutCubic(t))
    }

    fun easeOutCubic(t: Float): Float {
        val u = 1f - t
        return 1f - u * u * u
    }
}

/**
 * A snake that plays itself on a torus board. Pure Kotlin and deterministic for a
 * given [rng] and call sequence.
 *
 * The snake heads for a food pellet, choosing among its three safe turns by
 * distance to the food and by room left past the move. Progress sets a world
 * length W(p) = MIN_LEN + L0 * (2^(K*p) - 1); the on-board target is W(p) / 2^level.
 * When the body covers more than ZOOM_AT of the board it is compressed toward the
 * centre by 2 (a pull-back), [level] goes up and [scale] eases from 2 to 1.
 *
 * Cell index is `y * cols + x`. The body is a ring buffer so a step allocates nothing.
 * [cols] and [rows] should be even so the centre sits on a cell edge.
 */
class SnakeEngine(val cols: Int, val rows: Int, private val rng: Random) {
    private val cells = cols * rows
    private val cap = cells + 2
    private val ring = IntArray(cap)
    private var headPos = 0

    /** Body length in cells. */
    var length = 0
        private set
    private val occ = BooleanArray(cells)
    private val seen = IntArray(cells)
    private val queue = IntArray(cells)
    private var stamp = 0

    /** Direction index into right, down, left, up. */
    var dir = 0
        private set

    /** Cell index of the food, or -1. */
    var food = -1
        private set
    private var pendingGrow = 0

    /** Pull-backs so far. */
    var level = 0
        private set

    /** Times the snake was re-laid because it was boxed in or a pull-back overlapped. */
    var relays = 0
        private set

    /** Highest progress seen, 0..1. */
    var progress = 0f
        private set

    /** On-board target length for the current progress and level. */
    var targetLen = SnakeConfig.MIN_LEN
        private set

    /** Render scale: 2 right after a pull-back, easing to 1. */
    var scale = 1f
        private set

    /** True while a pull-back is easing. */
    val zooming: Boolean get() = zoomElapsed >= 0f

    /** When true, [advance] creeps progress toward the busy ceiling. */
    var busy = false

    private var zoomElapsed = -1f
    private var acc = 0f
    private var busyAcc = 0f

    private val optDir = IntArray(3)
    private val optCell = IntArray(3)
    private val optDist = IntArray(3)
    private val optStraight = IntArray(3)

    init {
        startBody()
        updateTarget()
    }

    // ---- accessors ----

    /** Cell index of the i-th segment, head first. */
    fun cellAt(i: Int): Int = ring[(headPos + i) % cap]

    fun cellX(cell: Int): Int = cell % cols
    fun cellY(cell: Int): Int = cell / cols

    // ---- geometry ----

    private fun worldLen(): Double = SnakeConfig.MIN_LEN +
        SnakeConfig.ZOOM_AT * cells / (2.0.pow(SnakeConfig.K * SnakeConfig.FIRST_ZOOM_P) - 1) *
        (2.0.pow(SnakeConfig.K * progress.toDouble()) - 1)

    private fun onBoardTarget(lv: Int): Int =
        max(SnakeConfig.MIN_LEN, floor(worldLen() / 2.0.pow(lv) + 0.5).toInt())

    private fun wrapX(x: Int) = (x + cols) % cols
    private fun wrapY(y: Int) = (y + rows) % rows

    private fun pushFront(cell: Int) {
        headPos = (headPos - 1 + cap) % cap
        ring[headPos] = cell
        length++
        occ[cell] = true
    }

    private fun popTail(): Int {
        val gone = ring[(headPos + length - 1) % cap]
        length--
        occ[gone] = false
        return gone
    }

    private fun clearBody() {
        java.util.Arrays.fill(occ, false)
        headPos = 0
        length = 0
    }

    private fun startBody() {
        clearBody()
        val y = rows / 2
        val x = cols / 2
        // Tail first, so the head ends up in front.
        for (i in SnakeConfig.MIN_LEN - 1 downTo 0) pushFront(wrapY(y) * cols + wrapX(x - i))
        dir = 0
        pendingGrow = 0
        spawnFood()
    }

    /** Every other row, joined at the ends: a long snake that is clearly a snake. */
    private fun layoutBody(len: Int) {
        val path = IntArray(min(len, cells))
        var n = 0
        var y = 0
        var d = 1
        while (y < rows && n < len) {
            var i = 0
            while (i < cols && n < len) {
                path[n++] = y * cols + (if (d > 0) i else cols - 1 - i)
                i++
            }
            if (y + 1 < rows && n < len) path[n++] = (y + 1) * cols + (if (d > 0) cols - 1 else 0)
            y += 2
            d = -d
        }
        clearBody()
        for (k in 0 until n) pushFront(path[k])  // last path cell becomes the head
        dir = 0
        if (length >= 2) {
            val a = cellAt(0)
            val b = cellAt(1)
            val dx = cellX(a) - cellX(b)
            val dy = cellY(a) - cellY(b)
            for (k in 0..3) if (DX[k] == dx && DY[k] == dy) { dir = k; break }
        }
        pendingGrow = 0
        spawnFood()
    }

    // ---- play ----

    private fun deficit(): Int = targetLen - length - pendingGrow

    private fun spawnFood() {
        food = -1
        if (length >= cells) return
        if (length > 0 && deficit() > 0) {
            // Behind the target: keep the pellet close so catching up is quick.
            val head = cellAt(0)
            val hx = cellX(head)
            val hy = cellY(head)
            repeat(24) {
                val x = wrapX(hx + rng.nextInt(11) - 5)
                val y = wrapY(hy + rng.nextInt(11) - 5)
                if (!occ[y * cols + x]) { food = y * cols + x; return }
            }
        }
        repeat(48) {
            val c = rng.nextInt(cols) + rng.nextInt(rows) * cols
            if (!occ[c]) { food = c; return }
        }
        for (i in 0 until cells) if (!occ[i]) { food = i; return }
    }

    private fun torusDist(a: Int, b: Int): Int {
        val dx = abs(cellX(a) - cellX(b))
        val dy = abs(cellY(a) - cellY(b))
        return min(dx, cols - dx) + min(dy, rows - dy)
    }

    /**
     * Free cells reachable from [start], counted up to [need]. [tail] is treated as
     * free when the tail is about to move out of the way (-1 for none).
     */
    private fun reach(start: Int, need: Int, tail: Int): Int {
        stamp++
        queue[0] = start
        seen[start] = stamp
        var qn = 1
        var qi = 0
        var n = 0
        while (qi < qn && n < need) {
            n++
            val cur = queue[qi++]
            val cx = cur % cols
            val cy = cur / cols
            for (d in 0..3) {
                val i = wrapY(cy + DY[d]) * cols + wrapX(cx + DX[d])
                if (seen[i] == stamp) continue
                if (occ[i] && i != tail) continue
                seen[i] = stamp
                queue[qn++] = i
            }
        }
        return n
    }

    /** Advances the snake one cell. */
    fun step() {
        val head = cellAt(0)
        val tail = cellAt(length - 1)
        val tailMoves = pendingGrow == 0 && deficit() <= 0
        val hx = cellX(head)
        val hy = cellY(head)
        var count = 0
        for (t in TURNS) {
            val d = (dir + t) % 4
            val c = wrapY(hy + DY[d]) * cols + wrapX(hx + DX[d])
            if (occ[c] && !(c == tail && tailMoves)) continue
            optDir[count] = d
            optCell[count] = c
            optDist[count] = if (food >= 0) torusDist(c, food) else 0
            optStraight[count] = if (t == 0) 0 else 1
            count++
        }
        if (count == 0) {
            // Boxed in. Re-lay the same snake rather than let it visibly die.
            relays++
            layoutBody(length)
            return
        }
        // Stable insertion sort by distance, then straight first.
        for (i in 1 until count) {
            val d = optDir[i]; val c = optCell[i]; val dist = optDist[i]; val s = optStraight[i]
            var j = i - 1
            while (j >= 0 && (optDist[j] > dist || (optDist[j] == dist && optStraight[j] > s))) {
                optDir[j + 1] = optDir[j]; optCell[j + 1] = optCell[j]
                optDist[j + 1] = optDist[j]; optStraight[j + 1] = optStraight[j]
                j--
            }
            optDir[j + 1] = d; optCell[j + 1] = c; optDist[j + 1] = dist; optStraight[j + 1] = s
        }
        val need = length + 1
        var pick = 0
        var bestReach = -1
        for (i in 0 until count) {
            val r = reach(optCell[i], need, if (tailMoves) tail else -1)
            if (r >= need) { pick = i; break }
            if (r > bestReach) { bestReach = r; pick = i }
        }

        dir = optDir[pick]
        val next = optCell[pick]
        val eats = food >= 0 && next == food
        if (eats) {
            val owed = deficit()
            if (owed > 0) pendingGrow += min(owed, max(1, (owed + 2) / 3))
        }
        if (pendingGrow > 0) pendingGrow-- else popTail()
        pushFront(next)
        if (eats) spawnFood()

        if (length > SnakeConfig.ZOOM_AT * cells) zoomOut()
    }

    /** Signed shortest offset between two coordinates on a ring of size [n]. */
    private fun ringDelta(delta: Int, n: Int): Int {
        val d = ((delta % n) + n) % n
        return if (d > n / 2.0) d - n else d
    }

    /**
     * Compresses the snake toward the board centre by 2, keeping it contiguous.
     * Returns false when the result would overlap itself (caller re-lays it).
     */
    private fun compress(): Boolean {
        val cx = cols / 2.0
        val cy = rows / 2.0
        val n = length
        // Unwrap relative to the head so a snake straddling an edge stays in one piece.
        val px = IntArray(n)
        val py = IntArray(n)
        px[0] = cellX(cellAt(0)); py[0] = cellY(cellAt(0))
        for (i in 1 until n) {
            val a = cellAt(i)
            val b = cellAt(i - 1)
            px[i] = px[i - 1] + ringDelta(cellX(a) - cellX(b), cols)
            py[i] = py[i - 1] + ringDelta(cellY(a) - cellY(b), rows)
        }
        val ox = IntArray(2 * n)
        val oy = IntArray(2 * n)
        var m = 0
        for (i in 0 until n) {
            val qx = floor(cx + (px[i] + 0.5 - cx) / 2).toInt()
            val qy = floor(cy + (py[i] + 0.5 - cy) / 2).toInt()
            if (m > 0 && ox[m - 1] == qx && oy[m - 1] == qy) continue
            if (m > 0 && ox[m - 1] != qx && oy[m - 1] != qy) {
                ox[m] = qx; oy[m] = oy[m - 1]; m++
            }
            ox[m] = qx; oy[m] = qy; m++
        }
        val next = IntArray(m)
        java.util.Arrays.fill(occ, false)
        for (i in 0 until m) {
            val c = (((oy[i] % rows) + rows) % rows) * cols + ((ox[i] % cols) + cols) % cols
            if (occ[c]) return false
            occ[c] = true
            next[i] = c
        }
        clearBody()
        for (i in m - 1 downTo 0) pushFront(next[i])
        return true
    }

    private fun zoomOut() {
        val cx = cols / 2.0
        val cy = rows / 2.0
        val old = food
        level++
        val oldLen = length
        if (compress()) {
            if (length >= 2) {
                val a = cellAt(0)
                val b = cellAt(1)
                val dx = ringDelta(cellX(a) - cellX(b), cols)
                val dy = ringDelta(cellY(a) - cellY(b), rows)
                for (k in 0..3) if (DX[k] == dx && DY[k] == dy) { dir = k; break }
            }
            pendingGrow = 0
            food = -1
            if (old >= 0) {
                val fx = (floor(cx + (cellX(old) + 0.5 - cx) / 2).toInt() + cols) % cols
                val fy = (floor(cy + (cellY(old) + 0.5 - cy) / 2).toInt() + rows) % rows
                if (!occ[fy * cols + fx]) food = fy * cols + fx
            }
            if (food < 0) spawnFood()
        } else {
            relays++
            layoutBody(max(SnakeConfig.MIN_LEN, oldLen / 2))
        }
        // Draw the new state at 2x, which matches the frame just before the swap,
        // then ease out to the whole board.
        scale = 2f
        zoomElapsed = 0f
    }

    private fun updateTarget() {
        targetLen = onBoardTarget(level)
    }

    /** Raises progress (never lowers it) to [fraction]. */
    fun setProgress(fraction: Float) {
        val f = if (fraction.isFinite()) fraction else 0f
        progress = min(1f, max(progress, f))
        updateTarget()
    }

    /**
     * Reduced-motion picture: the smallest pull-back level that fits, drawn still at
     * the current on-board target with no food.
     */
    fun layoutStatic() {
        level = 0
        while (onBoardTarget(level) > SnakeConfig.ZOOM_AT * cells) level++
        updateTarget()
        layoutBody(targetLen)
        food = -1
        scale = 1f
        zoomElapsed = -1f
    }

    /**
     * Advances time by [dtMs] (capped at 100). While [running] the snake takes up to
     * MAX_STEPS_PER_FRAME steps at TICK_MS; the pull-back eases either way.
     */
    fun advance(dtMs: Long, running: Boolean) {
        val dt = min(100L, max(0L, dtMs)).toFloat()
        if (zoomElapsed >= 0f) {
            zoomElapsed += dt
            if (zoomElapsed >= SnakeConfig.ZOOM_MS) {
                scale = 1f
                zoomElapsed = -1f
            } else {
                scale = SnakeConfig.zoomScale(zoomElapsed)
            }
        }
        if (!running) { acc = 0f; return }
        if (busy) {
            busyAcc += dt
            while (busyAcc >= SnakeConfig.BUSY_TICK_MS) {
                busyAcc -= SnakeConfig.BUSY_TICK_MS
                setProgress(SnakeConfig.busyStep(progress))
            }
        }
        acc += dt
        var n = 0
        while (acc >= SnakeConfig.TICK_MS && n < SnakeConfig.MAX_STEPS_PER_FRAME) {
            updateTarget()
            step()
            acc -= SnakeConfig.TICK_MS
            n++
        }
        if (acc > SnakeConfig.TICK_MS) acc = 0f
    }

    private companion object {
        // right, down, left, up. A turn is an offset into this ring: 0 straight,
        // 3 left, 1 right; 2 would reverse and is never offered.
        val DX = intArrayOf(1, 0, -1, 0)
        val DY = intArrayOf(0, 1, 0, -1)
        val TURNS = intArrayOf(0, 3, 1)
    }
}
