package app.openbill.ui.onboarding

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

private const val TAU = (2.0 * PI).toFloat()

private fun topRounded(l: Float, t: Float, r: Float, b: Float, radius: Float) = RoundRect(
    l, t, r, b,
    topLeftCornerRadius = CornerRadius(radius),
    topRightCornerRadius = CornerRadius(radius),
    bottomRightCornerRadius = CornerRadius.Zero,
    bottomLeftCornerRadius = CornerRadius.Zero,
)

// ---------------------------------------------------------------------------------------------
// 1. Welcome: the logo assembles itself.
// ---------------------------------------------------------------------------------------------

private class Spark(val x: Float, val y: Float, val r: Float, val gold: Boolean, val depth: Float)

private val sparks = listOf(
    Spark(62f, 78f, 7f, true, 0.55f),
    Spark(262f, 64f, 5f, false, -0.25f),
    Spark(280f, 196f, 8f, true, 0.45f),
    Spark(44f, 214f, 5f, false, -0.3f),
    Spark(232f, 268f, 4f, true, 0.6f),
    Spark(96f, 268f, 6f, false, 0.35f),
)

internal fun DrawScope.welcomeScene(f: Frame) {
    val c = f.c
    val center = Offset(160f, 150f)

    // Soft halo rings, slow layer.
    layer(f, 0.35f) {
        val h = f.p(0f, 700f)
        val breathe = 1f + 0.035f * f.wave(5000f, 2400f)
        for (i in 0 until 3) {
            drawCircle(
                c.primaryContainer,
                radius = (78f + i * 30f) * h * breathe,
                center = center,
                alpha = clamp01((0.55f - i * 0.17f) * h),
            )
        }
    }

    // Sparkles pop in after the mark is built, then twinkle.
    sparks.forEachIndexed { i, s ->
        layer(f, s.depth) {
            val pop = f.p(2000f + i * 90f, 420f, ::backOut)
            val tw = if (f.reduced) 1f else 0.65f + 0.35f * cos(TAU * (f.loop(2600f, 2400f) + i * 0.17f))
            sparkle(Offset(s.x, s.y), s.r * pop, if (s.gold) Marigold else c.primary, 0.9f * tw * clamp01(pop))
        }
    }

    // Ground shadow under the floating mark.
    val float = -4f * f.wave(4200f, 2500f)
    val shadowP = f.p(200f, 500f)
    drawOval(
        c.shadow,
        topLeft = Offset(160f - 62f + float, 252f),
        size = Size(124f - 2 * float, 12f),
        alpha = clamp01(shadowP),
    )

    translate(72f, 62f + float) { scale(2.75f, pivot = Offset.Zero) { markAssembly(f) } }
}

/** The mark built step by step, in its 64-unit grid. */
private fun DrawScope.markAssembly(f: Frame) {
    val g = MarkGeometry

    // Tile pops in.
    val tile = f.p(0f, 520f, ::backOut)
    val tileAlpha = f.p(0f, 200f, ::linear)
    scale(0.55f + 0.45f * tile, pivot = Offset(32f, 32f)) {
        drawPath(g.tile, Peacock, alpha = tileAlpha)
    }

    // Receipt outline draws itself, then fills.
    val trace = f.p(350f, 800f, ::easeInOut)
    val fill = f.p(1050f, 300f, ::linear)
    if (fill < 1f) trimmedPath(f, g.receipt, trace, Color.White, 1.5f)
    drawPath(g.receipt, Color.White, alpha = fill)

    // The corner folds over its hinge: its free point travels from the paper corner to the fold.
    if (fill > 0f) {
        val flip = f.p(1250f, 520f, ::backOut)
        val v = lerpO(g.foldOpen, g.foldClosed, flip)
        val fold = Path().apply {
            moveTo(g.foldHinge1.x, g.foldHinge1.y)
            lineTo(v.x, v.y)
            lineTo(g.foldHinge2.x, g.foldHinge2.y)
            close()
        }
        // Paper side while it lifts, Marigold back once it crosses the hinge.
        val color = if (flip < 0.5f) mix(Color.White, Color(0xFFD9E3E6), flip * 2f) else Marigold
        drawPath(fold, color, alpha = fill)
    }

    // The ring spins closed (but stays open: the "open" in OpenBill).
    val ring = f.p(1500f, 760f)
    if (ring > 0f) {
        val r = g.RING_RADIUS
        drawArc(
            Peacock,
            startAngle = g.RING_START - 220f * (1f - ring),
            sweepAngle = g.RING_SWEEP * ring,
            useCenter = false,
            topLeft = g.ringCenter - Offset(r, r),
            size = Size(2 * r, 2 * r),
            style = Stroke(g.RING_WIDTH),
            alpha = f.p(1500f, 120f, ::linear),
        )
    }

    // Total bar slides in from the left.
    val bar = f.p(2000f, 480f, ::backOut)
    if (bar > 0f) drawRect(Peacock, Offset(22.4f, 41.6f), Size(19.2f * bar, 3.2f))

    // A light sweep across the tile, repeating every few seconds.
    if (!f.reduced && f.ms > 2550f) {
        val sweep = seg((f.ms - 2550f) % 6000f, 0f, 950f)
        if (sweep in 0.001f..0.999f) {
            val x = lerpF(-24f, 92f, easeInOut(sweep))
            clipPath(g.tile) {
                val band = Path().apply {
                    moveTo(x, -4f); lineTo(x + 12f, -4f); lineTo(x - 6f, 68f); lineTo(x - 18f, 68f); close()
                }
                drawPath(band, Color.White, alpha = 0.22f)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 2. GST invoice builds line by line; the tax split toggles; the total rolls up.
// ---------------------------------------------------------------------------------------------

private class Line(val name: String, val amount: String)

private val invoiceLines = listOf(
    Line("Basmati rice, 25 kg", "₹2,400.00"),
    Line("Steel tiffin box × 2", "₹1,150.00"),
    Line("Notebook pack", "₹450.00"),
)

/** 0 = CGST + SGST (intra-state), 1 = IGST (inter-state); toggles in the idle loop. */
private fun taxSplit(f: Frame): Float {
    if (f.reduced || f.ms < 3000f) return 0f
    val ph = (f.ms - 3000f) % 4400f
    return when {
        ph < 2200f -> easeInOut(ph / 450f)
        else -> 1f - easeInOut((ph - 2200f) / 450f)
    }
}

internal fun DrawScope.invoiceScene(f: Frame) {
    val c = f.c
    val cardX = 50f
    val cardY = 22f
    val cardW = 220f
    val cardH = 272f
    val right = cardX + cardW - 14f
    val left = cardX + 14f

    // Background confetti of tiny rupee coins, slow layer.
    layer(f, 0.45f) {
        val a = f.p(200f, 600f)
        for (i in 0 until 4) {
            val y = 60f + i * 64f + 4f * f.wave(3600f, 2000f, i * 0.25f)
            val x = if (i % 2 == 0) 22f else 298f
            drawCircle(if (i % 2 == 0) c.tertiaryContainer else c.secondaryContainer, 9f, Offset(x, y), alpha = a)
        }
    }

    val inP = f.p(0f, 520f)
    translate(top = 34f * (1f - inP)) {
        roundRect(c.shadow, cardX + 3f, cardY + 8f, cardW, cardH, 16f, alpha = inP)
        roundRect(c.paper, cardX, cardY, cardW, cardH, 16f, alpha = inP)
        roundRect(c.line, cardX, cardY, cardW, cardH, 16f, alpha = inP, stroke = 1f)

        // Header band sweeps open.
        val head = f.p(250f, 480f)
        val headClip = Path().apply {
            addRoundRect(topRounded(cardX, cardY, cardX + cardW, cardY + 48f, 16f))
        }
        clipPath(headClip) { drawRect(c.primary, Offset(cardX, cardY), Size(cardW * head, 48f)) }
        text(f, "TAX INVOICE", left, cardY + 17f, 10f, c.onPrimary, bold = true, alpha = head)
        text(f, "INV-0042", right, cardY + 17f, 10f, c.onPrimary, bold = true, align = 1f, alpha = head)
        text(f, "22 Oct 2026", right, cardY + 33f, 8.5f, c.onPrimary, align = 1f, alpha = head * 0.85f)
        text(f, "Kavya Traders", left, cardY + 33f, 8.5f, c.onPrimary, alpha = head * 0.85f)

        // Lines slide in one after another, each with its rule drawing underneath.
        invoiceLines.forEachIndexed { i, line ->
            val rp = f.p(560f + i * 170f, 460f)
            val dx = 26f * (1f - rp)
            val y = cardY + 72f + i * 26f
            text(f, line.name, left + dx, y, 9.5f, c.ink, alpha = rp)
            text(f, line.amount, right + dx, y, 9.5f, c.ink, align = 1f, alpha = rp)
            trimmedLine(Offset(left, y + 12f), Offset(right, y + 12f), rp, c.line, 0.8f)
        }

        // Tax chips pop in; a selection pill glides between them.
        val sel = taxSplit(f)
        val chipY = cardY + 160f
        val chipA = floatArrayOf(left, 100f)
        val chipB = floatArrayOf(left + 108f, 54f)
        val chipsIn = f.p(1250f, 460f, ::backOut)
        val chipsIn2 = f.p(1360f, 460f, ::backOut)
        val pillX = lerpF(chipA[0], chipB[0], sel)
        val pillW = lerpF(chipA[1], chipB[1], sel)
        scale(chipsIn, pivot = Offset(chipA[0] + chipA[1] / 2f, chipY)) {
            roundRect(c.line, chipA[0], chipY - 11f, chipA[1], 22f, 11f, stroke = 1f, alpha = chipsIn)
        }
        scale(chipsIn2, pivot = Offset(chipB[0] + chipB[1] / 2f, chipY)) {
            roundRect(c.line, chipB[0], chipY - 11f, chipB[1], 22f, 11f, stroke = 1f, alpha = chipsIn2)
        }
        roundRect(c.primaryContainer, pillX, chipY - 11f, pillW, 22f, 11f, alpha = clamp01(chipsIn))
        text(f, "CGST + SGST", chipA[0] + chipA[1] / 2f, chipY, 9f, mix(c.onPrimaryContainer, c.muted, sel), bold = sel < 0.5f, align = 0.5f, alpha = chipsIn)
        text(f, "IGST", chipB[0] + chipB[1] / 2f, chipY, 9f, mix(c.muted, c.onPrimaryContainer, sel), bold = sel >= 0.5f, align = 0.5f, alpha = chipsIn2)

        // Tap ripple on the chip being chosen.
        if (!f.reduced && f.ms > 3000f) {
            val ph = (f.ms - 3000f) % 4400f
            val (rp, cx) = when {
                ph < 600f -> ph / 600f to chipB[0] + chipB[1] / 2f
                ph in 2200f..2800f -> (ph - 2200f) / 600f to chipA[0] + chipA[1] / 2f
                else -> -1f to 0f
            }
            if (rp in 0f..1f) drawCircle(c.primary, 6f + 26f * easeOut(rp), Offset(cx, chipY), alpha = 0.28f * (1f - rp))
        }

        // Tax rows cross-fade between the two splits; the total stays the same.
        val taxIn = f.p(1500f, 420f)
        val ty = chipY + 26f
        text(f, "CGST 9%", left, ty - 6f * sel, 9f, c.muted, alpha = taxIn * (1f - sel))
        text(f, "₹360.00", right, ty - 6f * sel, 9f, c.muted, align = 1f, alpha = taxIn * (1f - sel))
        text(f, "SGST 9%", left, ty + 16f - 6f * sel, 9f, c.muted, alpha = taxIn * (1f - sel))
        text(f, "₹360.00", right, ty + 16f - 6f * sel, 9f, c.muted, align = 1f, alpha = taxIn * (1f - sel))
        text(f, "IGST 18%", left, ty + 8f + 6f * (1f - sel) - 8f, 9f, c.muted, alpha = taxIn * sel)
        text(f, "₹720.00", right, ty + 8f + 6f * (1f - sel) - 8f, 9f, c.muted, align = 1f, alpha = taxIn * sel)

        val totalY = cardY + cardH - 22f
        trimmedLine(Offset(left, totalY - 17f), Offset(right, totalY - 17f), f.p(1700f, 420f), c.ink, 1.2f, alpha = 0.7f)
        text(f, "Total", left, totalY, 12f, c.ink, bold = true, alpha = f.p(1750f, 300f, ::linear))
        rollingAmount(f, "₹4,720.00", right, totalY, 13f, c.primary, start = 1850f)
    }

    // "GST ready" badge pops onto the corner and bobs.
    layer(f, -0.3f) {
        val pop = f.p(2500f, 520f, ::springOut)
        val bob = 3f * f.wave(3000f, 3000f)
        val ctr = Offset(cardX + cardW + 2f, cardY - 8f + bob)
        if (pop > 0f) {
            drawCircle(Marigold, 17f * pop, ctr)
            val check = Path().apply {
                moveTo(ctr.x - 7f, ctr.y + 0.5f); lineTo(ctr.x - 2f, ctr.y + 5.5f); lineTo(ctr.x + 7.5f, ctr.y - 5f)
            }
            trimmedPath(f, check, f.p(2750f, 320f), Color(0xFF3A2700), 3f)
        }
    }
}

/** Odometer-style amount: each digit rolls up to its value, right to left, staggered. */
private fun DrawScope.rollingAmount(f: Frame, s: String, rightX: Float, cy: Float, size: Float, color: Color, start: Float) {
    val digit = measure(f, "0", size, bold = true)
    val cell = digit.size.width.toFloat()
    val h = digit.size.height.toFloat()
    val widths = s.map { ch -> if (ch.isDigit()) cell else measure(f, ch.toString(), size, bold = true).size.width.toFloat() }
    var x = rightX - widths.sum()
    var fromRight = s.count { it.isDigit() }
    s.forEachIndexed { i, ch ->
        val w = widths[i]
        if (ch.isDigit()) {
            fromRight--
            val d = ch - '0'
            val e = f.p(start + fromRight * 70f, 900f)
            val shown = f.p(start + fromRight * 70f, 160f, ::linear)
            val v = (d + 10) * e
            val base = floor(v).toInt()
            val frac = v - base
            clipRect(x, cy - h / 2f, x + w, cy + h / 2f) {
                text(f, (base % 10).toString(), x, cy - frac * h, size, color, bold = true, alpha = shown)
                if (frac > 0f) text(f, ((base + 1) % 10).toString(), x, cy + (1f - frac) * h, size, color, bold = true, alpha = shown)
            }
        } else {
            text(f, ch.toString(), x, cy, size, color, bold = true, alpha = f.p(start, 300f, ::linear))
        }
        x += w
    }
}

// ---------------------------------------------------------------------------------------------
// 3. PDF templates fan out and shuffle; a logo stamp drops in; share and print pop.
// ---------------------------------------------------------------------------------------------

private val templateNames = listOf("GST Detailed", "Modern", "Receipt")
private const val CARD_W = 118f
private const val CARD_H = 166f

private fun shufflePhase(f: Frame): Float {
    if (f.reduced || f.ms < 2900f) return 0f
    val base = f.ms - 2900f
    val step = floor(base / 2900f)
    return step + easeInOut(((base % 2900f) / 760f))
}

internal fun DrawScope.templatesScene(f: Frame) {
    val c = f.c
    val fan = f.p(620f, 760f, ::backOut)
    val phase = shufflePhase(f)
    val cy = 136f

    // Cards on a carousel ring: angle from the shuffle phase, depth from its cosine.
    val order = (0 until 3).map { k ->
        val theta = TAU * (k - phase) / 3f
        k to theta
    }.sortedBy { cos(it.second) }

    for ((k, theta) in order) {
        val depth = cos(theta)
        val drop = f.p(k * 130f, 520f)
        val x = 160f + sin(theta) * 84f * fan
        val rot = sin(theta) * 11f * fan
        val s = 0.8f + 0.2f * (depth + 1f) / 2f
        layer(f, (1f - depth) * 0.22f) {
            withTransform({
                translate(x - CARD_W / 2f, cy - CARD_H / 2f - 46f * (1f - drop))
                rotate(rot, pivot = Offset(CARD_W / 2f, CARD_H / 2f))
                scale(s, s, pivot = Offset(CARD_W / 2f, CARD_H / 2f))
            }) {
                roundRect(c.shadow, 2f, 6f, CARD_W, CARD_H, 8f, alpha = drop)
                when (k) {
                    0 -> gstDetailedCard(f, drop)
                    1 -> modernCard(drop)
                    else -> receiptCard(drop)
                }
                // Cards further back sit in a little shade.
                roundRect(Color.Black, 0f, 0f, CARD_W, CARD_H, 8f, alpha = drop * (1f - depth) * 0.07f)
            }
        }
    }

    // Name of the front card, cross-fading as the deck turns.
    val nearest = phase.roundToInt()
    val front = ((nearest % 3) + 3) % 3
    val labelA = clamp01(1f - 2.2f * abs(phase - nearest)) * f.p(1300f, 400f, ::linear)
    text(f, templateNames[front], 160f, 238f, 11f, c.muted, bold = true, align = 0.5f, alpha = labelA)

    // Share and print buttons pop in; share pulses now and then.
    layer(f, -0.3f) {
        actionButton(f, Offset(138f, 278f), 2100f, c.primary, c.onPrimary, share = true)
        actionButton(f, Offset(182f, 278f), 2230f, c.tertiaryContainer, c.tertiary, share = false)
    }
}

private fun DrawScope.actionButton(f: Frame, at: Offset, start: Float, bg: Color, fg: Color, share: Boolean) {
    val pop = f.p(start, 520f, ::springOut)
    if (pop <= 0f) return
    if (share && !f.reduced && f.ms > 3200f) {
        val lp = f.loop(3000f, 3200f)
        drawCircle(bg, 18f + 14f * easeOut(lp), at, alpha = 0.3f * (1f - lp))
    }
    scale(pop, pivot = at) {
        drawCircle(bg, 18f, at)
        if (share) {
            val a = at + Offset(-5f, 0f)
            val b = at + Offset(5f, -6f)
            val d = at + Offset(5f, 6f)
            drawLine(fg, a, b, 1.6f)
            drawLine(fg, a, d, 1.6f)
            drawCircle(fg, 2.8f, a); drawCircle(fg, 2.8f, b); drawCircle(fg, 2.8f, d)
        } else {
            roundRect(fg, at.x - 5f, at.y - 9f, 10f, 5f, 1f, stroke = 1.4f)
            roundRect(fg, at.x - 8.5f, at.y - 4f, 17f, 8f, 2f)
            roundRect(bg, at.x - 5f, at.y + 1f, 10f, 1f, 0.5f)
            roundRect(fg, at.x - 5f, at.y + 2.5f, 10f, 6.5f, 1f, stroke = 1.4f)
        }
    }
}

private fun DrawScope.bar(x: Float, y: Float, w: Float, h: Float = 3f, color: Color = PdfRule) = roundRect(color, x, y, w, h, h / 2f)

private fun DrawScope.gstDetailedCard(f: Frame, a: Float) {
    roundRect(PdfPaper, 0f, 0f, CARD_W, CARD_H, 8f, alpha = a)
    if (a < 0.05f) return
    roundRect(PdfRule, 6f, 6f, CARD_W - 12f, CARD_H - 12f, 2f, stroke = 0.8f)
    // Logo spot, filled by the stamp.
    roundRect(Color(0xFFE8EEF0), 10f, 10f, 18f, 18f, 4f)
    bar(32f, 12f, 48f, 4f, PdfInk)
    bar(32f, 20f, 36f)
    bar(40f, 33f, 38f, 3f, PdfInk)
    roundRect(PdfRule, 10f, 41f, 47f, 22f, 2f, stroke = 0.8f)
    roundRect(PdfRule, 61f, 41f, 47f, 22f, 2f, stroke = 0.8f)
    bar(13f, 45f, 26f, 2.5f, PdfInk); bar(13f, 51f, 36f, 2f); bar(13f, 56f, 30f, 2f)
    bar(64f, 45f, 22f, 2.5f, PdfInk); bar(64f, 51f, 36f, 2f); bar(64f, 56f, 28f, 2f)
    roundRect(PdfTint, 10f, 68f, 98f, 8f, 1f)
    for (i in 0 until 5) drawLine(PdfRule, Offset(10f, 84f + i * 8f), Offset(108f, 84f + i * 8f), 0.6f)
    for (vx in floatArrayOf(22f, 62f, 78f, 92f)) drawLine(PdfRule, Offset(vx, 68f), Offset(vx, 116f), 0.6f)
    roundRect(PdfRule, 10f, 68f, 98f, 48f, 1f, stroke = 0.8f)
    roundRect(PdfRule, 10f, 122f, 48f, 22f, 1f, stroke = 0.8f)
    for (i in 1 until 3) drawLine(PdfRule, Offset(10f, 122f + i * 7.3f), Offset(58f, 122f + i * 7.3f), 0.6f)
    roundRect(PdfRule, 62f, 122f, 46f, 22f, 1f, stroke = 0.8f)
    bar(66f, 126f, 20f, 2f); bar(66f, 132f, 26f, 2f); bar(66f, 138f, 38f, 3f, Peacock)
    bar(78f, 152f, 28f, 1.2f, PdfInk)

    // Logo stamp drops onto the spot with a little shock ring.
    val drop = seg(f.ms, 1400f, 460f).let { if (f.reduced) 1f else it }
    if (drop > 0f) {
        val sc = 1f + 1.8f * (1f - backOut(drop))
        val ctr = Offset(19f, 19f)
        scale(sc, pivot = ctr) { drawMarkAt(Offset(10f, 10f), 18f, alpha = clamp01(drop * 2f)) }
        val ring = f.p(1680f, 480f, ::linear)
        if (ring in 0.001f..0.999f) drawCircle(Marigold, 10f + 16f * easeOut(ring), ctr, alpha = 0.7f * (1f - ring), style = Stroke(1.5f))
    }
}

private fun DrawScope.modernCard(a: Float) {
    roundRect(PdfPaper, 0f, 0f, CARD_W, CARD_H, 8f, alpha = a)
    if (a < 0.05f) return
    val band = Path().apply {
        addRoundRect(topRounded(0f, 0f, CARD_W, 34f, 8f))
    }
    drawPath(band, Peacock)
    drawMarkAt(Offset(10f, 8f), 18f)
    bar(34f, 11f, 40f, 4f, Color.White)
    bar(34f, 19f, 28f, 2.5f, Color.White.copy(alpha = 0.7f))
    bar(10f, 40f, 22f, 2.5f, Marigold)
    bar(10f, 47f, 40f, 3f, PdfInk); bar(10f, 54f, 30f, 2.2f)
    bar(72f, 47f, 36f, 3f, PdfInk); bar(80f, 54f, 28f, 2.2f)
    for (i in 0 until 6) {
        val y = 66f + i * 9f
        if (i % 2 == 0) roundRect(PdfTint, 8f, y - 2f, 102f, 9f, 2f, alpha = 0.7f)
        bar(12f, y + 1f, 44f, 2.5f, PdfInk.copy(alpha = 0.75f))
        bar(90f, y + 1f, 16f, 2.5f, PdfInk.copy(alpha = 0.75f))
    }
    roundRect(Peacock, 58f, 124f, 52f, 22f, 5f)
    bar(63f, 129f, 18f, 2f, Color.White.copy(alpha = 0.75f))
    bar(63f, 135f, 40f, 4f, Color.White)
    bar(10f, 128f, 36f, 2f); bar(10f, 134f, 28f, 2f)
}

private fun DrawScope.receiptCard(a: Float) {
    if (a < 0.05f) return
    val x0 = 24f
    val x1 = CARD_W - 24f
    val path = Path().apply {
        moveTo(x0, 4f); lineTo(x1, 4f); lineTo(x1, 150f)
        var x = x1
        var up = true
        while (x > x0 + 0.1f) {
            x -= 7f
            lineTo(maxOf(x, x0), if (up) 156f else 150f)
            up = !up
        }
        lineTo(x0, 4f); close()
    }
    drawPath(path, PdfPaper, alpha = a)
    val cx = CARD_W / 2f
    drawMarkAt(Offset(cx - 8f, 10f), 16f)
    bar(cx - 22f, 31f, 44f, 3.5f, PdfInk); bar(cx - 16f, 38f, 32f, 2f)
    fun dashed(y: Float) {
        var x = x0 + 4f
        while (x < x1 - 4f) { drawLine(PdfRule, Offset(x, y), Offset(minOf(x + 3f, x1 - 4f), y), 0.8f); x += 5f }
    }
    dashed(46f)
    for (i in 0 until 5) {
        val y = 53f + i * 9f
        bar(x0 + 5f, y, 30f, 2.4f, PdfInk.copy(alpha = 0.7f))
        bar(x1 - 19f, y, 14f, 2.4f, PdfInk.copy(alpha = 0.7f))
    }
    dashed(100f)
    bar(x0 + 5f, 107f, 24f, 4f, PdfInk); bar(x1 - 27f, 107f, 22f, 4f, PdfInk)
    dashed(118f)
    // A small code-like block of squares.
    for (r in 0 until 3) for (q in 0 until 3) if ((r + q) % 2 == 0 || r == 1) roundRect(PdfInk, cx - 8f + q * 6f, 124f + r * 6f, 4.5f, 4.5f, 0.6f)
}

// ---------------------------------------------------------------------------------------------
// 4. Parties and items fly into a list; the search bar types and filters.
// ---------------------------------------------------------------------------------------------

private class Entry(
    val name: String,
    val sub: String,
    val trailing: String,
    val party: Boolean,
    val from: Offset,
    val ctrl: Offset,
    val match: Boolean,
    val filteredSlot: Int,
)

private val entries = listOf(
    Entry("Kavya Traders", "Customer · Pune", "₹12,400", true, Offset(-40f, 40f), Offset(20f, -20f), true, 0),
    Entry("Basmati rice", "HSN 1006 · 5%", "₹96/kg", false, Offset(370f, 70f), Offset(300f, 10f), false, 2),
    Entry("Sharma Stores", "Supplier · Indore", "₹3,150", true, Offset(-50f, 330f), Offset(-20f, 200f), false, 3),
    Entry("Kadai, steel", "HSN 7323 · 12%", "₹450/pc", false, Offset(380f, 340f), Offset(330f, 220f), true, 1),
)

private const val QUERY = "Ka"
private const val TYPE_AT = 2000f
private const val SEARCH_CYCLE = 5400f

/** Characters typed and the filter progress for the self-typing search. */
private fun searchState(f: Frame): Pair<Int, Float> {
    if (f.reduced) return 2 to 1f
    if (f.ms < TYPE_AT) return 0 to 0f
    val ph = (f.ms - TYPE_AT) % SEARCH_CYCLE
    val typed = when {
        ph < 200f -> 1
        ph < 3300f -> 2
        ph < 3440f -> 1
        else -> 0
    }
    val filter = if (ph < 3300f) easeInOut((ph - 280f) / 460f) else 1f - easeInOut((ph - 3300f) / 460f)
    return typed to filter
}

internal fun DrawScope.partiesScene(f: Frame) {
    val c = f.c
    val (typed, fl) = searchState(f)

    // Search bar drops in.
    val sIn = f.p(0f, 480f)
    translate(top = -24f * (1f - sIn)) {
        roundRect(c.surfaceHigh, 30f, 20f, 260f, 42f, 21f, alpha = sIn)
        val ic = Offset(52f, 40f)
        drawCircle(c.muted, 5.5f, ic, style = Stroke(1.8f), alpha = sIn)
        drawLine(c.muted, ic + Offset(4f, 4f), ic + Offset(8.5f, 8.5f), 1.8f, cap = StrokeCap.Round, alpha = sIn)
        val q = QUERY.take(typed)
        val tx = 68f
        if (q.isEmpty()) {
            text(f, "Search parties and items", tx, 41f, 10.5f, c.muted, alpha = sIn * 0.85f)
        } else {
            text(f, q, tx, 41f, 11f, c.ink, bold = true, alpha = sIn)
        }
        // Blinking caret after the text.
        val caretOn = f.reduced || f.ms < TYPE_AT - 400f || (f.ms % 1000f) < 560f
        if (sIn > 0.9f && caretOn && f.ms > 500f) {
            val cx = if (q.isEmpty()) tx - 2f else tx + measure(f, q, 11f, bold = true).size.width + 1.5f
            drawLine(c.primary, Offset(cx, 33f), Offset(cx, 49f), 1.5f)
        }
        text(f, "2 found", 276f, 41f, 9f, c.primary, bold = true, align = 1f, alpha = fl * sIn)
    }

    val rowStep = 54f
    fun slotY(slot: Float) = 80f + slot * rowStep
    val e = easeInOut(fl)
    // Non-matches first, so the matching rows glide over them.
    val drawOrder = entries.indices.sortedBy { if (entries[it].match) 1 else 0 }
    for (i in drawOrder) {
        val en = entries[i]
        val start = 320f + i * 160f
        val fly = f.p(start, 640f)
        val bodyP = f.p(start + 520f, 380f)
        val y = slotY(lerpF(i.toFloat(), en.filteredSlot.toFloat(), e))
        val dim = if (en.match) 1f else 1f - 0.72f * fl
        val target = Offset(55f, y + 23f)

        // Row body grows out of the avatar once it lands.
        val w = 46f + 214f * bodyP
        roundRect(c.paper, 30f, y, w, 46f, 14f, alpha = bodyP * dim)
        roundRect(if (en.match && fl > 0f) mix(c.line, c.primary, fl) else c.line, 30f, y, w, 46f, 14f, alpha = bodyP * dim, stroke = 1f)

        if (bodyP > 0f) {
            clipRect(30f, y, 30f + w, y + 46f) {
                val nx = 80f
                if (en.match && fl > 0f) {
                    val hl = measure(f, en.name.take(2), 11f, bold = true)
                    roundRect(c.primaryContainer, nx - 1.5f, y + 9f, hl.size.width + 3f, 15f, 3f, alpha = fl)
                }
                text(f, en.name, nx, y + 17f, 11f, c.ink, bold = true, alpha = bodyP * dim)
                text(f, en.sub, nx, y + 32f, 8.5f, c.muted, alpha = bodyP * dim)
                text(f, en.trailing, 278f, y + 23f, 10f, if (en.party) c.primary else c.ink, bold = en.party, align = 1f, alpha = bodyP * dim)
            }
        }

        // Avatar (party) or item tile flies in along a curve, overshooting into place.
        if (fly > 0f) {
            val t = fly
            val u = 1f - t
            val pos = Offset(
                u * u * en.from.x + 2 * u * t * en.ctrl.x + t * t * target.x,
                u * u * en.from.y + 2 * u * t * en.ctrl.y + t * t * target.y,
            )
            val sc = 0.6f + 0.4f * f.p(start + 300f, 500f, ::springOut)
            scale(sc, pivot = pos) {
                if (en.party) {
                    drawCircle(if (i == 0) c.primaryContainer else c.tertiaryContainer, 15f, pos, alpha = dim)
                    text(f, en.name.take(1), pos.x, pos.y, 12f, if (i == 0) c.onPrimaryContainer else c.tertiary, bold = true, align = 0.5f, alpha = dim)
                } else {
                    roundRect(c.secondaryContainer, pos.x - 15f, pos.y - 15f, 30f, 30f, 8f, alpha = dim)
                    // Little box icon.
                    roundRect(c.muted, pos.x - 7f, pos.y - 5f, 14f, 11f, 2f, stroke = 1.5f, alpha = dim)
                    drawLine(c.muted, Offset(pos.x - 7f, pos.y - 1f), Offset(pos.x + 7f, pos.y - 1f), 1.5f, alpha = dim)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 5. Private and offline: devices lock, the cloud is gently crossed out, the shield pulses.
// ---------------------------------------------------------------------------------------------

private val shieldPath: Path by lazy {
    Path().apply {
        moveTo(0f, -32f)
        lineTo(26f, -22f)
        lineTo(26f, 0f)
        cubicTo(26f, 18f, 13f, 29f, 0f, 35f)
        cubicTo(-13f, 29f, -26f, 18f, -26f, 0f)
        lineTo(-26f, -22f)
        close()
    }
}

internal fun DrawScope.privacyScene(f: Frame) {
    val c = f.c
    val shieldC = Offset(160f, 74f)

    // Pulse rings around the shield.
    if (!f.reduced && f.ms > 1900f) {
        for (k in 0 until 2) {
            val lp = (f.loop(2800f, 1900f) + k * 0.5f) % 1f
            drawCircle(c.primary, 40f + 50f * lp, shieldC, alpha = 0.22f * (1f - lp) * seg(f.ms, 1900f, 600f), style = Stroke(2f))
        }
    }

    // Phone, sliding in from the left.
    layer(f, 0.18f) {
        val inP = f.p(0f, 620f)
        translate(left = -70f * (1f - inP)) {
            device(f, 36f, 124f, 82f, 146f, 14f, inP, lockAt = 820f, lockC = Offset(77f, 197f))
        }
    }
    // Laptop, sliding in from the right.
    layer(f, -0.18f) {
        val inP = f.p(120f, 620f)
        translate(left = 70f * (1f - inP)) {
            device(f, 146f, 142f, 148f, 92f, 8f, inP, lockAt = 980f, lockC = Offset(220f, 188f))
            roundRect(c.muted, 132f, 234f, 176f, 9f, 4.5f, alpha = inP * 0.85f)
            roundRect(c.paper, 206f, 234f, 28f, 3f, 1.5f, alpha = inP * 0.6f)
        }
    }

    // Cloud, faded and gently crossed out.
    layer(f, 0.4f) {
        val cloudIn = f.p(500f, 420f, ::linear)
        val strike = f.p(1300f, 520f)
        val dimTo = 0.72f - 0.42f * strike
        val cc = c.muted
        val a = cloudIn * dimTo
        drawCircle(cc, 12f, Offset(250f, 56f), alpha = a)
        drawCircle(cc, 16f, Offset(266f, 47f), alpha = a)
        drawCircle(cc, 11f, Offset(282f, 57f), alpha = a)
        roundRect(cc, 240f, 54f, 52f, 14f, 7f, alpha = a)
        trimmedLine(Offset(238f, 30f), Offset(294f, 80f), strike, c.ink, 2.5f, alpha = 0.65f)
    }

    // Shield pops in and its check draws itself, then it breathes.
    val pop = f.p(1350f, 560f, ::springOut)
    if (pop > 0f) {
        val breathe = 1f + 0.03f * f.wave(2800f, 2200f)
        translate(shieldC.x, shieldC.y) {
            scale(pop * breathe, pivot = Offset.Zero) {
                drawPath(shieldPath, c.primary)
                val check = Path().apply { moveTo(-10f, 1f); lineTo(-3f, 9f); lineTo(12f, -8f) }
                trimmedPath(f, check, f.p(1650f, 380f), c.onPrimary, 4f)
            }
        }
        // Three data dots orbit the shield: they go round, never away.
        if (!f.reduced) {
            for (k in 0 until 3) {
                val ang = TAU * (f.loop(6000f, 2000f) + k / 3f)
                val pos = shieldC + Offset(cos(ang) * 46f, sin(ang) * 20f)
                drawCircle(if (k == 0) Marigold else c.primary, 3f, pos, alpha = 0.8f * seg(f.ms, 2000f, 500f))
            }
        }
    }
}

/** A phone or laptop screen with list rows and a lock that clicks shut. */
private fun DrawScope.device(
    f: Frame,
    x: Float, y: Float, w: Float, h: Float, r: Float,
    a: Float,
    lockAt: Float,
    lockC: Offset,
) {
    val c = f.c
    roundRect(c.ink, x, y, w, h, r, alpha = a * 0.9f)
    roundRect(c.paper, x + 5f, y + 6f, w - 10f, h - 12f, r - 4f, alpha = a)
    roundRect(c.primary, x + 5f, y + 6f, w - 10f, 10f, 3f, alpha = a * 0.85f)
    var ry = y + 24f
    var i = 0
    while (ry < y + h - 14f) {
        val shimmer = if (f.reduced) 0f else 0.5f + 0.5f * sin(TAU * (f.loop(3000f, 1500f) - i * 0.15f))
        roundRect(c.line, x + 11f, ry, (w - 22f) * (if (i % 2 == 0) 0.8f else 0.6f), 4f, 2f, alpha = a * (0.6f + 0.4f * shimmer))
        ry += 11f
        i++
    }
    // Lock: body, then the shackle drops into it with a bounce.
    val lp = f.p(lockAt, 460f, ::springOut)
    val lockIn = f.p(lockAt - 220f, 260f, ::backOut)
    if (lockIn <= 0f) return
    scale(lockIn, pivot = lockC) {
        drawCircle(c.primaryContainer, 22f, lockC, alpha = 0.95f)
        val shY = -9f * (1f - lp)
        drawArc(
            c.onPrimaryContainer, 180f, 180f, false,
            topLeft = Offset(lockC.x - 6f, lockC.y - 12f + shY), size = Size(12f, 12f), style = Stroke(2.6f),
        )
        drawLine(c.onPrimaryContainer, Offset(lockC.x - 6f, lockC.y - 6f + shY), Offset(lockC.x - 6f, lockC.y - 1f + shY), 2.6f)
        drawLine(c.onPrimaryContainer, Offset(lockC.x + 6f, lockC.y - 6f + shY), Offset(lockC.x + 6f, lockC.y - 1f), 2.6f)
        roundRect(c.onPrimaryContainer, lockC.x - 9f, lockC.y - 2f, 18f, 14f, 3f)
        drawCircle(c.primaryContainer, 1.8f, Offset(lockC.x, lockC.y + 4.5f))
    }
    val click = f.p(lockAt + 300f, 420f, ::linear)
    if (click in 0.001f..0.999f) drawCircle(c.primary, 22f + 14f * click, lockC, alpha = 0.35f * (1f - click), style = Stroke(1.5f))
}

// ---------------------------------------------------------------------------------------------
// 6. Everywhere: Android, Windows and Linux devices slide in, each running OpenBill.
// ---------------------------------------------------------------------------------------------

internal fun DrawScope.platformsScene(f: Frame) {
    val c = f.c

    // Monitor (back layer).
    layer(f, 0.25f) {
        val inP = f.p(0f, 640f, ::backOut)
        val bob = 2.5f * f.wave(4200f, 2000f, 0.1f)
        translate(top = 80f * (1f - inP) + bob) {
            val a = clamp01(f.p(0f, 300f, ::linear))
            drawOval(c.shadow, Offset(124f, 168f), Size(84f, 8f), alpha = a)
            roundRect(c.muted, 157f, 146f, 18f, 18f, 2f, alpha = a)
            roundRect(c.muted, 134f, 162f, 64f, 7f, 3.5f, alpha = a)
            roundRect(c.ink, 92f, 46f, 148f, 102f, 9f, alpha = a * 0.92f)
            miniApp(f, 98f, 52f, 136f, 90f, a, 0f)
            text(f, "Windows", 166f, 182f, 10f, c.muted, bold = true, align = 0.5f, alpha = f.p(1200f, 400f, ::linear))
        }
    }
    // Phone (front left).
    layer(f, -0.12f) {
        val inP = f.p(180f, 640f, ::backOut)
        val bob = 2.5f * f.wave(4200f, 2000f, 0.4f)
        translate(top = 80f * (1f - inP) + bob) {
            val a = clamp01(f.p(180f, 300f, ::linear))
            drawOval(c.shadow, Offset(32f, 248f), Size(60f, 8f), alpha = a)
            roundRect(c.ink, 30f, 118f, 62f, 124f, 12f, alpha = a * 0.92f)
            miniApp(f, 34f, 126f, 54f, 108f, a, 0.33f)
            text(f, "Android", 61f, 266f, 10f, c.muted, bold = true, align = 0.5f, alpha = f.p(1350f, 400f, ::linear))
        }
    }
    // Laptop (front right).
    layer(f, -0.3f) {
        val inP = f.p(360f, 640f, ::backOut)
        val bob = 2.5f * f.wave(4200f, 2000f, 0.7f)
        translate(top = 80f * (1f - inP) + bob) {
            val a = clamp01(f.p(360f, 300f, ::linear))
            drawOval(c.shadow, Offset(190f, 226f), Size(120f, 9f), alpha = a)
            roundRect(c.ink, 198f, 146f, 104f, 72f, 7f, alpha = a * 0.92f)
            miniApp(f, 203f, 151f, 94f, 62f, a, 0.66f)
            roundRect(c.muted, 186f, 218f, 128f, 7f, 3.5f, alpha = a)
            text(f, "Linux", 250f, 242f, 10f, c.muted, bold = true, align = 0.5f, alpha = f.p(1500f, 400f, ::linear))
        }
    }
}

/** A tiny OpenBill window: brand bar with the mark, then rows with one highlighted in turn. */
private fun DrawScope.miniApp(f: Frame, x: Float, y: Float, w: Float, h: Float, a: Float, phase: Float) {
    val c = f.c
    roundRect(c.paper, x, y, w, h, 4f, alpha = a)
    val barH = minOf(16f, h * 0.18f)
    roundRect(Peacock, x, y, w, barH, 4f, alpha = a)
    drawMarkAt(Offset(x + 3f, y + 2f), barH - 4f, alpha = a)
    roundRect(Color.White, x + barH + 2f, y + barH / 2f - 1.5f, w * 0.3f, 3f, 1.5f, alpha = a * 0.85f)
    val rows = ((h - barH - 6f) / 13f).toInt().coerceAtLeast(1)
    val hi = if (f.reduced || f.ms < 1600f) -1 else ((f.loop(rows * 900f, 1600f) + phase) * rows).toInt() % rows
    for (i in 0 until rows) {
        val ry = y + barH + 5f + i * 13f
        val rowIn = f.p(700f + i * 90f + phase * 300f, 320f)
        if (i == hi) roundRect(c.primaryContainer, x + 3f, ry - 2f, w - 6f, 12f, 3f, alpha = a * rowIn)
        drawCircle(if (i % 2 == 0) c.tertiaryContainer else c.secondaryContainer, 3.5f, Offset(x + 9f, ry + 4f), alpha = a * rowIn)
        roundRect(c.line, x + 16f, ry + 2f, (w - 40f) * (if (i % 2 == 0) 0.9f else 0.65f), 4f, 2f, alpha = a * rowIn)
        roundRect(c.primary, x + w - 18f, ry + 2f, 12f, 4f, 2f, alpha = a * rowIn * 0.8f)
    }
}
