package com.raavivi.sysmon.core.term

/**
 * A compact ANSI/VT terminal emulator: enough to render normal interactive shell
 * output (commands, `dir`/`ls`, git, build logs, colours) correctly. It is not a
 * full xterm — fancy full-screen TUIs (vim, htop) using the alternate screen
 * buffer are approximated, not pixel-perfect.
 *
 * Pure Kotlin, no Android/Compose dependency: it exposes styled [TermSpan] runs
 * that the UI layer turns into `AnnotatedString`. Not thread-safe — feed it from
 * a single consumer coroutine.
 */
class TerminalEmulator(
    var cols: Int = 80,
    var rows: Int = 24,
    private val scrollbackLimit: Int = 2000,
) {
    /** A colour run within a line; [fg]/[bg] are ARGB ints, or null for default. */
    data class TermSpan(val text: String, val fg: Int?, val bg: Int?, val bold: Boolean)

    private class Cell(
        var ch: Char = ' ',
        var fg: Int? = null,
        var bg: Int? = null,
        var bold: Boolean = false,
        var inverse: Boolean = false,
    ) {
        fun reset() { ch = ' '; fg = null; bg = null; bold = false; inverse = false }
    }

    private var screen: Array<Array<Cell>> = blankScreen(rows, cols)
    private val scrollback = ArrayDeque<List<TermSpan>>()

    private var curRow = 0
    private var curCol = 0
    private var savedRow = 0
    private var savedCol = 0

    private var scrollTop = 0
    private var scrollBottom = rows - 1

    // Current pen attributes applied to newly written cells.
    private var penFg: Int? = null
    private var penBg: Int? = null
    private var penBold = false
    private var penInverse = false

    // Parser state.
    private enum class State { GROUND, ESC, CSI, OSC }
    private var state = State.GROUND
    private val csiParams = StringBuilder()
    private var csiPrivate = false

    private fun blankScreen(r: Int, c: Int) = Array(r) { Array(c) { Cell() } }

    /** Resize the grid, preserving as much content as fits. */
    fun resize(newRows: Int, newCols: Int) {
        if (newRows <= 0 || newCols <= 0 || (newRows == rows && newCols == cols)) return
        val old = screen
        val ns = blankScreen(newRows, newCols)
        val copyRows = minOf(rows, newRows)
        val copyCols = minOf(cols, newCols)
        for (r in 0 until copyRows) for (c in 0 until copyCols) {
            ns[r][c] = old[r][c]
        }
        screen = ns
        rows = newRows
        cols = newCols
        scrollTop = 0
        scrollBottom = rows - 1
        curRow = curRow.coerceIn(0, rows - 1)
        curCol = curCol.coerceIn(0, cols - 1)
    }

    fun feed(text: String) {
        for (ch in text) feedChar(ch)
    }

    private fun feedChar(ch: Char) {
        when (state) {
            State.GROUND -> ground(ch)
            State.ESC -> esc(ch)
            State.CSI -> csi(ch)
            State.OSC -> osc(ch)
        }
    }

    private fun ground(ch: Char) {
        when (ch) {
            '\u001B' -> state = State.ESC
            '\r' -> curCol = 0
            '\n' -> lineFeed()
            '\b' -> { if (curCol > 0) curCol-- }
            '\t' -> { curCol = ((curCol / 8) + 1) * 8; if (curCol >= cols) curCol = cols - 1 }
            '\u0007' -> {} // BEL
            '\u000E', '\u000F' -> {} // shift in/out
            else -> if (ch >= ' ') putChar(ch)
        }
    }

    private fun esc(ch: Char) {
        when (ch) {
            '[' -> { state = State.CSI; csiParams.setLength(0); csiPrivate = false }
            ']' -> state = State.OSC
            '7' -> { savedRow = curRow; savedCol = curCol; state = State.GROUND }
            '8' -> { curRow = savedRow; curCol = savedCol; state = State.GROUND }
            'M' -> { reverseIndex(); state = State.GROUND }
            'c' -> { hardReset(); state = State.GROUND }
            else -> state = State.GROUND // charset selects etc. — consume and ignore
        }
    }

    private fun osc(ch: Char) {
        // OSC … terminated by BEL or ST (ESC \). We don't act on titles, just consume.
        if (ch == '\u0007' || ch == '\u001B' || ch == '\\') state = State.GROUND
    }

    private fun csi(ch: Char) {
        when {
            ch == '?' -> csiPrivate = true
            ch in '0'..'9' || ch == ';' -> csiParams.append(ch)
            ch in '@'..'~' -> { dispatchCsi(ch); state = State.GROUND }
            else -> state = State.GROUND
        }
    }

    private fun params(): List<Int> =
        if (csiParams.isEmpty()) emptyList()
        else csiParams.split(';').map { it.toIntOrNull() ?: 0 }

    private fun p(index: Int, default: Int): Int = params().getOrNull(index)?.takeIf { it != 0 } ?: default

    private fun dispatchCsi(final: Char) {
        val ps = params()
        when (final) {
            'A' -> curRow = (curRow - p(0, 1)).coerceAtLeast(0)
            'B' -> curRow = (curRow + p(0, 1)).coerceAtMost(rows - 1)
            'C' -> curCol = (curCol + p(0, 1)).coerceAtMost(cols - 1)
            'D' -> curCol = (curCol - p(0, 1)).coerceAtLeast(0)
            'E' -> { curRow = (curRow + p(0, 1)).coerceAtMost(rows - 1); curCol = 0 }
            'F' -> { curRow = (curRow - p(0, 1)).coerceAtLeast(0); curCol = 0 }
            'G', '`' -> curCol = (p(0, 1) - 1).coerceIn(0, cols - 1)
            'd' -> curRow = (p(0, 1) - 1).coerceIn(0, rows - 1)
            'H', 'f' -> {
                curRow = (p(0, 1) - 1).coerceIn(0, rows - 1)
                curCol = (p(1, 1) - 1).coerceIn(0, cols - 1)
            }
            'J' -> eraseDisplay(ps.getOrElse(0) { 0 })
            'K' -> eraseLine(ps.getOrElse(0) { 0 })
            'L' -> insertLines(p(0, 1))
            'M' -> deleteLines(p(0, 1))
            'P' -> deleteChars(p(0, 1))
            '@' -> insertChars(p(0, 1))
            'X' -> eraseChars(p(0, 1))
            'S' -> scrollUp(p(0, 1))
            'T' -> scrollDown(p(0, 1))
            'm' -> applySgr(ps)
            'r' -> {
                scrollTop = (p(0, 1) - 1).coerceIn(0, rows - 1)
                scrollBottom = (params().getOrNull(1)?.takeIf { it != 0 } ?: rows)
                    .let { (it - 1).coerceIn(scrollTop, rows - 1) }
                curRow = 0; curCol = 0
            }
            's' -> { savedRow = curRow; savedCol = curCol }
            'u' -> { curRow = savedRow; curCol = savedCol }
            'h', 'l' -> if (csiPrivate && (ps.contains(1049) || ps.contains(47) || ps.contains(1047))) {
                // Alternate screen enter/leave — approximate by clearing.
                clearAll(); curRow = 0; curCol = 0
            }
            else -> {}
        }
    }

    // ── writing ────────────────────────────────────────────────────────────────

    private fun putChar(ch: Char) {
        if (curCol >= cols) { curCol = 0; lineFeed() }
        val cell = screen[curRow][curCol]
        cell.ch = ch
        cell.fg = penFg; cell.bg = penBg; cell.bold = penBold; cell.inverse = penInverse
        curCol++
    }

    private fun lineFeed() {
        if (curRow == scrollBottom) scrollRegionUp() else if (curRow < rows - 1) curRow++
    }

    private fun reverseIndex() {
        if (curRow == scrollTop) scrollRegionDown() else if (curRow > 0) curRow--
    }

    /** Scroll the active region up by one, pushing the top line into scrollback. */
    private fun scrollRegionUp() {
        if (scrollTop == 0) pushScrollback(screen[scrollTop])
        for (r in scrollTop until scrollBottom) screen[r] = screen[r + 1]
        screen[scrollBottom] = Array(cols) { Cell() }
    }

    private fun scrollRegionDown() {
        for (r in scrollBottom downTo scrollTop + 1) screen[r] = screen[r - 1]
        screen[scrollTop] = Array(cols) { Cell() }
    }

    private fun scrollUp(n: Int) = repeat(n) { scrollRegionUp() }
    private fun scrollDown(n: Int) = repeat(n) { scrollRegionDown() }

    private fun pushScrollback(line: Array<Cell>) {
        scrollback.addLast(toSpans(line))
        while (scrollback.size > scrollbackLimit) scrollback.removeFirst()
    }

    private fun insertLines(n: Int) {
        if (curRow < scrollTop || curRow > scrollBottom) return
        repeat(n.coerceAtMost(scrollBottom - curRow + 1)) {
            for (r in scrollBottom downTo curRow + 1) screen[r] = screen[r - 1]
            screen[curRow] = Array(cols) { Cell() }
        }
    }

    private fun deleteLines(n: Int) {
        if (curRow < scrollTop || curRow > scrollBottom) return
        repeat(n.coerceAtMost(scrollBottom - curRow + 1)) {
            for (r in curRow until scrollBottom) screen[r] = screen[r + 1]
            screen[scrollBottom] = Array(cols) { Cell() }
        }
    }

    private fun insertChars(n: Int) {
        val row = screen[curRow]
        val count = n.coerceAtMost(cols - curCol)
        for (c in cols - 1 downTo curCol + count) row[c] = row[c - count]
        for (c in curCol until curCol + count) row[c] = Cell()
    }

    private fun deleteChars(n: Int) {
        val row = screen[curRow]
        val count = n.coerceAtMost(cols - curCol)
        for (c in curCol until cols - count) row[c] = row[c + count]
        for (c in cols - count until cols) row[c] = Cell()
    }

    private fun eraseChars(n: Int) {
        val row = screen[curRow]
        for (c in curCol until (curCol + n).coerceAtMost(cols)) row[c].reset()
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                for (c in curCol until cols) screen[curRow][c].reset()
                for (r in curRow + 1 until rows) for (c in 0 until cols) screen[r][c].reset()
            }
            1 -> {
                for (r in 0 until curRow) for (c in 0 until cols) screen[r][c].reset()
                for (c in 0..curCol.coerceAtMost(cols - 1)) screen[curRow][c].reset()
            }
            2 -> clearAll()
            3 -> { clearAll(); scrollback.clear() }
        }
    }

    private fun eraseLine(mode: Int) {
        val row = screen[curRow]
        when (mode) {
            0 -> for (c in curCol until cols) row[c].reset()
            1 -> for (c in 0..curCol.coerceAtMost(cols - 1)) row[c].reset()
            2 -> for (c in 0 until cols) row[c].reset()
        }
    }

    private fun clearAll() { for (r in 0 until rows) for (c in 0 until cols) screen[r][c].reset() }

    private fun hardReset() {
        screen = blankScreen(rows, cols)
        scrollback.clear()
        curRow = 0; curCol = 0; scrollTop = 0; scrollBottom = rows - 1
        penFg = null; penBg = null; penBold = false; penInverse = false
    }

    // ── SGR (colours / styles) ───────────────────────────────────────────────────

    private fun applySgr(ps: List<Int>) {
        if (ps.isEmpty()) { resetPen(); return }
        var i = 0
        while (i < ps.size) {
            when (val code = ps[i]) {
                0 -> resetPen()
                1 -> penBold = true
                22 -> penBold = false
                7 -> penInverse = true
                27 -> penInverse = false
                in 30..37 -> penFg = ANSI16[code - 30]
                39 -> penFg = null
                in 40..47 -> penBg = ANSI16[code - 40]
                49 -> penBg = null
                in 90..97 -> penFg = ANSI16[8 + code - 90]
                in 100..107 -> penBg = ANSI16[8 + code - 100]
                38 -> i = parseExtColor(ps, i) { penFg = it }
                48 -> i = parseExtColor(ps, i) { penBg = it }
            }
            i++
        }
    }

    private inline fun parseExtColor(ps: List<Int>, start: Int, set: (Int) -> Unit): Int {
        return when (ps.getOrNull(start + 1)) {
            5 -> { ps.getOrNull(start + 2)?.let { set(xterm256(it)) }; start + 2 }
            2 -> {
                val r = ps.getOrNull(start + 2) ?: 0
                val g = ps.getOrNull(start + 3) ?: 0
                val b = ps.getOrNull(start + 4) ?: 0
                set(0xFF000000.toInt() or (r shl 16) or (g shl 8) or b)
                start + 4
            }
            else -> start
        }
    }

    private fun resetPen() { penFg = null; penBg = null; penBold = false; penInverse = false }

    // ── rendering ────────────────────────────────────────────────────────────────

    private fun toSpans(line: Array<Cell>): List<TermSpan> {
        // Trim trailing blank, default-styled cells.
        var end = line.size
        while (end > 0 && line[end - 1].ch == ' ' && line[end - 1].fg == null &&
            line[end - 1].bg == null && !line[end - 1].bold && !line[end - 1].inverse
        ) end--
        if (end == 0) return emptyList()

        val spans = ArrayList<TermSpan>()
        val sb = StringBuilder()
        var fg: Int? = null; var bg: Int? = null; var bold = false; var first = true
        for (c in 0 until end) {
            val cell = line[c]
            val efg = if (cell.inverse) (cell.bg ?: DEFAULT_BG) else cell.fg
            val ebg = if (cell.inverse) (cell.fg ?: DEFAULT_FG) else cell.bg
            if (!first && (efg != fg || ebg != bg || cell.bold != bold)) {
                spans.add(TermSpan(sb.toString(), fg, bg, bold)); sb.setLength(0)
            }
            fg = efg; bg = ebg; bold = cell.bold; first = false
            sb.append(cell.ch)
        }
        if (sb.isNotEmpty()) spans.add(TermSpan(sb.toString(), fg, bg, bold))
        return spans
    }

    /** All scrollback + current screen lines, ready for rendering. */
    fun render(): List<List<TermSpan>> {
        val out = ArrayList<List<TermSpan>>(scrollback.size + rows)
        out.addAll(scrollback)
        for (r in 0 until rows) out.add(toSpans(screen[r]))
        // Trim trailing fully-blank screen lines so the view doesn't show dead space.
        while (out.size > 1 && out.last().isEmpty()) out.removeAt(out.size - 1)
        return out
    }

    companion object {
        private const val DEFAULT_FG = 0xFFD7DAE0.toInt()
        private const val DEFAULT_BG = 0xFF0E1116.toInt()

        // Standard 16-colour ANSI palette tuned for a dark background.
        private val ANSI16 = intArrayOf(
            0xFF2E3440.toInt(), 0xFFE06C75.toInt(), 0xFF98C379.toInt(), 0xFFE5C07B.toInt(),
            0xFF61AFEF.toInt(), 0xFFC678DD.toInt(), 0xFF56B6C2.toInt(), 0xFFABB2BF.toInt(),
            0xFF5C6370.toInt(), 0xFFE06C75.toInt(), 0xFF98C379.toInt(), 0xFFE5C07B.toInt(),
            0xFF61AFEF.toInt(), 0xFFC678DD.toInt(), 0xFF56B6C2.toInt(), 0xFFFFFFFF.toInt(),
        )

        private fun xterm256(n: Int): Int = when {
            n < 16 -> ANSI16[n]
            n in 16..231 -> {
                val v = n - 16
                val r = (v / 36) * 51
                val g = ((v % 36) / 6) * 51
                val b = (v % 6) * 51
                0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
            }
            else -> {
                val g = 8 + (n - 232) * 10
                0xFF000000.toInt() or (g shl 16) or (g shl 8) or g
            }
        }
    }
}
