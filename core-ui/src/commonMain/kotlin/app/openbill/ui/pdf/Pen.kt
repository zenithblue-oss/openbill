package app.openbill.ui.pdf

enum class Align { LEFT, CENTER, RIGHT }

/** Neutral colours shared by templates. */
object Ink {
    const val BLACK: Argb = 0xFF181C1E.toInt()
    const val BODY: Argb = 0xFF2A3134.toInt()
    const val MUTED: Argb = 0xFF5B6569.toInt()
    const val FAINT: Argb = 0xFF8A9498.toInt()
    const val RULE: Argb = 0xFFC9D1D4.toInt()
    const val HAIRLINE: Argb = 0xFFDDE3E5.toInt()
    const val SHADE: Argb = 0xFFF2F5F6.toInt()
    const val WHITE: Argb = 0xFFFFFFFF.toInt()
    const val ERROR: Argb = 0xFFBA1A1A.toInt()
    const val MARIGOLD: Argb = 0xFFE8A317.toInt()

    /** Mixes [color] towards white; [amount] 0 keeps the colour, 1 gives white. */
    fun tint(color: Argb, amount: Float): Argb {
        fun ch(shift: Int): Int {
            val c = (color shr shift) and 0xFF
            return (c + (255 - c) * amount).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Mixes [color] towards black. */
    fun shade(color: Argb, amount: Float): Argb {
        fun ch(shift: Int): Int = (((color shr shift) and 0xFF) * (1 - amount)).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/** A text style: font role, size and colour. */
data class TextStyle(val size: Float, val role: FontRole = FontRole.REGULAR, val color: Argb = Ink.BODY) {
    val lineHeight: Float get() = size * 1.32f

    /** Baseline offset from the top of a line box. */
    val ascent: Float get() = size * 0.98f

    fun bold() = copy(role = FontRole.BOLD)
    fun color(c: Argb) = copy(color = c)
    fun sized(s: Float) = copy(size = s)
}

/** Collects draw operations for one page and offers measured text helpers. */
class Pen(val metrics: FontMetrics) {
    val ops = mutableListOf<DrawOp>()

    fun width(text: String, style: TextStyle): Float = metrics.width(text, style.role, style.size)

    /**
     * One line of text anchored at [x] by [align]. When it is wider than [maxWidth] it first
     * shrinks (down to 75 %, so figures stay whole) and then ends with an ellipsis.
     * Returns the drawn width.
     */
    fun text(
        text: String,
        x: Float,
        baseline: Float,
        style: TextStyle,
        align: Align = Align.LEFT,
        maxWidth: Float = Float.MAX_VALUE,
        shrink: Boolean = false,
    ): Float {
        if (text.isEmpty()) return 0f
        var s = style
        var t = text
        var w = width(t, s)
        if (w > maxWidth && shrink) {
            val factor = (maxWidth / w).coerceAtLeast(0.75f)
            s = s.sized(s.size * factor)
            w = width(t, s)
        }
        if (w > maxWidth) {
            t = ellipsize(t, s, maxWidth)
            w = width(t, s)
        }
        val left = when (align) {
            Align.LEFT -> x
            Align.CENTER -> x - w / 2f
            Align.RIGHT -> x - w
        }
        ops += DrawOp.Text(left, baseline, t, s.role, s.size, s.color)
        return w
    }

    fun ellipsize(text: String, style: TextStyle, maxWidth: Float): String =
        if (width(text, style) <= maxWidth) text else forceEllipsis(text, style, maxWidth)

    private fun forceEllipsis(text: String, style: TextStyle, maxWidth: Float): String {
        var end = text.length
        while (end > 0 && width(text.substring(0, end).trimEnd() + "…", style) > maxWidth) end--
        return if (end == 0) "" else text.substring(0, end).trimEnd() + "…"
    }

    /** Word-wraps [text] (honouring newlines) to [maxWidth]; overlong words are broken. */
    fun wrap(text: String, style: TextStyle, maxWidth: Float, maxLines: Int = Int.MAX_VALUE, breakWords: Boolean = true): List<String> {
        val out = mutableListOf<String>()
        for (para in text.split('\n')) {
            if (para.isBlank()) {
                out += ""
                continue
            }
            var cur = ""
            for (word in para.trim().split(Regex(" +"))) {
                val cand = if (cur.isEmpty()) word else "$cur $word"
                if (width(cand, style) <= maxWidth) {
                    cur = cand
                    continue
                }
                if (cur.isNotEmpty()) out += cur
                var w = word
                while (breakWords && width(w, style) > maxWidth && w.length > 1) {
                    var cut = w.length - 1
                    while (cut > 1 && width(w.substring(0, cut), style) > maxWidth) cut--
                    out += w.substring(0, cut)
                    w = w.substring(cut)
                }
                cur = w
            }
            if (cur.isNotEmpty()) out += cur
        }
        if (out.size > maxLines) {
            val kept = out.take(maxLines).toMutableList()
            kept[maxLines - 1] = forceEllipsis(kept[maxLines - 1], style, maxWidth)
            return kept
        }
        return out.ifEmpty { listOf("") }
    }

    /** Draws wrapped lines starting with the line box at [top]; returns the height used. */
    fun paragraph(
        lines: List<String>,
        x: Float,
        top: Float,
        style: TextStyle,
        align: Align = Align.LEFT,
        lineHeight: Float = style.lineHeight,
    ): Float {
        lines.forEachIndexed { i, line -> text(line, x, top + style.ascent + i * lineHeight, style, align) }
        return lines.size * lineHeight
    }

    fun rect(x: Float, y: Float, w: Float, h: Float, fill: Argb? = null, stroke: Argb? = null, strokeWidth: Float = 0.6f, radius: Float = 0f) {
        ops += DrawOp.Rect(x, y, w, h, fill, stroke, strokeWidth, radius)
    }

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Argb = Ink.RULE, width: Float = 0.6f) {
        ops += DrawOp.Line(x1, y1, x2, y2, color, width)
    }

    fun hline(x: Float, y: Float, w: Float, color: Argb = Ink.RULE, width: Float = 0.6f) = line(x, y, x + w, y, color, width)

    fun image(op: DrawOp.Image) {
        ops += op
    }

    /** Draws a QR matrix as merged dark runs (vector, crisp at any zoom). */
    fun qr(matrix: QrMatrix, x: Float, y: Float, size: Float, color: Argb = Ink.BLACK) {
        val quiet = 2
        val n = matrix.size + quiet * 2
        val cell = size / n
        rect(x, y, size, size, fill = Ink.WHITE)
        for (r in 0 until matrix.size) {
            var c = 0
            while (c < matrix.size) {
                if (!matrix[r, c]) {
                    c++
                    continue
                }
                val start = c
                while (c < matrix.size && matrix[r, c]) c++
                // A hair of overlap avoids hairline gaps between modules in some viewers.
                rect(x + (start + quiet) * cell, y + (r + quiet) * cell, (c - start) * cell + 0.05f, cell + 0.05f, fill = color)
            }
        }
    }
}

/** Square QR module matrix (true = dark). */
class QrMatrix(val size: Int, private val bits: BooleanArray) {
    init {
        require(bits.size == size * size)
    }

    operator fun get(row: Int, col: Int): Boolean = bits[row * size + col]
}

/** Locally generated QR code (no network). Returns null if the text cannot be encoded. */
expect fun encodeQr(text: String): QrMatrix?
