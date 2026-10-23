package app.openbill.ui.onboarding

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import app.openbill.ui.theme.Motion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/*
 * A tiny motion-graphics kit for the introduction. Every illustration is drawn on a Canvas in a
 * 320 x 320 unit "art box" and driven by one clock (milliseconds since its page became current).
 * Each element derives its own progress from that clock with [Frame.p], so a whole scene is one
 * state read in the draw phase: swiping and animating never recompose the page.
 */

/** Art box edge in units. */
internal const val ART = 320f

/** Clock value used for still frames (reduced motion): every entrance has finished. */
internal const val SETTLED = 6000f

/** Brand colours, fixed in both themes (docs/13-brand.md). */
internal val Peacock = Color(0xFF00677A)
internal val Marigold = Color(0xFFE8A317)

/** Paper for PDF and receipt silhouettes: printed documents stay white in dark theme too. */
internal val PdfPaper = Color(0xFFFCFDFD)
internal val PdfInk = Color(0xFF26302F)
internal val PdfRule = Color(0xFFC3CDD0)
internal val PdfTint = Color(0xFFDDEFF3)

@Immutable
internal class ArtColors(
    val paper: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val tertiary: Color,
    val tertiaryContainer: Color,
    val secondaryContainer: Color,
    val surfaceHigh: Color,
    val shadow: Color,
    val dark: Boolean,
)

@Composable
internal fun rememberArtColors(): ArtColors {
    val cs = MaterialTheme.colorScheme
    return remember(cs) {
        val dark = cs.surface.luminance() < 0.5f
        ArtColors(
            paper = cs.surfaceContainerLowest,
            ink = cs.onSurface,
            muted = cs.onSurfaceVariant,
            line = cs.outlineVariant,
            primary = cs.primary,
            onPrimary = cs.onPrimary,
            primaryContainer = cs.primaryContainer,
            onPrimaryContainer = cs.onPrimaryContainer,
            tertiary = cs.tertiary,
            tertiaryContainer = cs.tertiaryContainer,
            secondaryContainer = cs.secondaryContainer,
            surfaceHigh = cs.surfaceContainerHigh,
            shadow = if (dark) Color.Black.copy(alpha = 0.45f) else Color(0xFF00313A).copy(alpha = 0.10f),
            dark = dark,
        )
    }
}

/** Reusable scratch objects so path trimming does not allocate every frame. */
internal class ArtScratch {
    val measure = PathMeasure()
    val path = Path()
}

/** One frame of a scene: the clock, pager offset and drawing resources. */
internal class Frame(
    /** Milliseconds since the page became current ([SETTLED] under reduced motion). */
    val ms: Float,
    /** Page position relative to the viewport: 0 centred, +1 one page to the left. */
    val offset: Float,
    val reduced: Boolean,
    val c: ArtColors,
    val measurer: TextMeasurer,
    val scratch: ArtScratch,
    /** Page width in art units, for parallax. */
    val span: Float,
) {
    /** Eased 0..1 progress of an element that starts at [start] ms and runs [dur] ms. */
    fun p(start: Float, dur: Float, ease: (Float) -> Float = ::easeOut): Float =
        if (reduced) 1f else ease(seg(ms, start, dur))

    /** 0..1 saw-tooth with [period] ms, running from [startAt]; 0 (the rest pose) when reduced. */
    fun loop(period: Float, startAt: Float = 0f): Float =
        if (reduced || ms < startAt) 0f else ((ms - startAt) % period) / period

    /** Gentle sine for idle loops, faded in after [startAt]. */
    fun wave(period: Float, startAt: Float = 0f, phase: Float = 0f): Float {
        if (reduced || ms < startAt) return 0f
        return sin(2f * PI.toFloat() * (loop(period, startAt) + phase)) * seg(ms, startAt, 600f)
    }
}

internal fun clamp01(x: Float): Float = x.coerceIn(0f, 1f)

internal fun seg(ms: Float, start: Float, dur: Float): Float = clamp01((ms - start) / dur)

internal fun easeOut(x: Float): Float = Motion.EmphasizedDecelerate.transform(clamp01(x))

internal fun easeInOut(x: Float): Float = FastOutSlowInEasing.transform(clamp01(x))

internal fun linear(x: Float): Float = clamp01(x)

/** Back-out: overshoots about 10 % and settles, for "pop" entrances. */
internal fun backOut(x: Float): Float {
    if (x <= 0f) return 0f
    if (x >= 1f) return 1f
    val s = 1.70158f
    val y = x - 1f
    return 1f + (s + 1f) * y * y * y + s * y * y
}

/** Damped spring (about 17 % overshoot, one visible bounce). */
internal fun springOut(x: Float): Float {
    if (x <= 0f) return 0f
    if (x >= 1f) return 1f
    return 1f - exp(-5f * x) * cos(9f * x)
}

internal fun lerpF(a: Float, b: Float, t: Float): Float = a + (b - a) * t

internal fun lerpO(a: Offset, b: Offset, t: Float): Offset = Offset(lerpF(a.x, b.x, t), lerpF(a.y, b.y, t))

internal fun mix(a: Color, b: Color, t: Float): Color = lerp(a, b, clamp01(t))

internal fun parsePath(d: String): Path = PathParser().parsePathString(d).toPath()

/** Measuring in a 1:1 density makes font sizes equal art units. */
private val UnitDensity = Density(1f, 1f)

/**
 * Draws [s] with its vertical centre on [y]; [align] 0 = starts at [x], 0.5 = centred, 1 = ends.
 * Text in illustrations is decorative (the canvas has one content description), so it does not
 * follow the font scale.
 */
internal fun DrawScope.text(
    f: Frame,
    s: String,
    x: Float,
    y: Float,
    size: Float,
    color: Color,
    bold: Boolean = false,
    align: Float = 0f,
    alpha: Float = 1f,
): TextLayoutResult {
    val l = measure(f, s, size, bold)
    if (alpha > 0.003f) {
        drawText(l, color, Offset(x - l.size.width * align, y - l.size.height / 2f), alpha = clamp01(alpha))
    }
    return l
}

internal fun measure(f: Frame, s: String, size: Float, bold: Boolean = false): TextLayoutResult =
    f.measurer.measure(
        s,
        style = TextStyle(fontSize = size.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal),
        density = UnitDensity,
    )

/** Translates [block] by the page offset times [factor]: the parallax depth of a layer. */
internal inline fun DrawScope.layer(f: Frame, factor: Float, block: DrawScope.() -> Unit) {
    translate(left = f.offset * factor * f.span, block = block)
}

/** Draws the first [fraction] of [path] (path-trim, as in motion graphics tools). */
internal fun DrawScope.trimmedPath(f: Frame, path: Path, fraction: Float, color: Color, width: Float, alpha: Float = 1f) {
    if (fraction <= 0f || alpha <= 0f) return
    val stroke = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)
    if (fraction >= 1f) {
        drawPath(path, color, alpha = clamp01(alpha), style = stroke)
        return
    }
    val m = f.scratch.measure
    val dst = f.scratch.path
    m.setPath(path, false)
    dst.reset()
    m.getSegment(0f, m.length * fraction, dst, true)
    drawPath(dst, color, alpha = clamp01(alpha), style = stroke)
}

internal fun DrawScope.trimmedLine(from: Offset, to: Offset, fraction: Float, color: Color, width: Float, alpha: Float = 1f) {
    if (fraction <= 0f || alpha <= 0f) return
    drawLine(color, from, lerpO(from, to, clamp01(fraction)), strokeWidth = width, cap = StrokeCap.Round, alpha = clamp01(alpha))
}

internal fun DrawScope.roundRect(color: Color, x: Float, y: Float, w: Float, h: Float, r: Float, alpha: Float = 1f, stroke: Float = 0f) {
    if (alpha <= 0f || w <= 0f || h <= 0f) return
    drawRoundRect(
        color,
        topLeft = Offset(x, y),
        size = Size(w, h),
        cornerRadius = CornerRadius(r, r),
        alpha = clamp01(alpha),
        style = if (stroke > 0f) Stroke(stroke) else androidx.compose.ui.graphics.drawscope.Fill,
    )
}

/** Four-point sparkle. */
internal fun DrawScope.sparkle(center: Offset, r: Float, color: Color, alpha: Float) {
    if (r <= 0f || alpha <= 0f) return
    val p = Path().apply {
        moveTo(center.x, center.y - r)
        quadraticTo(center.x, center.y, center.x + r, center.y)
        quadraticTo(center.x, center.y, center.x, center.y + r)
        quadraticTo(center.x, center.y, center.x - r, center.y)
        quadraticTo(center.x, center.y, center.x, center.y - r)
        close()
    }
    drawPath(p, color, alpha = clamp01(alpha))
}

/** Logo geometry in its 64-unit grid (docs/13-brand.md, BrandLogo.Mark). */
internal object MarkGeometry {
    val receipt: Path by lazy { parsePath("M16 8.8L38.4 8.8L48 18.4L48 48.8L40 53.6L32 48.8L24 53.6L16 48.8Z") }
    val tile: Path by lazy { Path().apply { addRoundRect(RoundRect(0f, 0f, 64f, 64f, CornerRadius(14f, 14f))) } }
    val ringCenter = Offset(32f, 27.2f)
    const val RING_RADIUS = 7.6f
    const val RING_WIDTH = 4f
    const val RING_START = -25f
    const val RING_SWEEP = 320f
    val foldHinge1 = Offset(38.4f, 8.8f)
    val foldHinge2 = Offset(48f, 18.4f)
    val foldOpen = Offset(48f, 8.8f)
    val foldClosed = Offset(38.4f, 18.4f)
}

/** The finished mark at 64 x 64 units; scale the canvas around it. */
internal fun DrawScope.drawMarkStatic(alpha: Float = 1f) {
    if (alpha <= 0f) return
    val a = clamp01(alpha)
    drawPath(MarkGeometry.tile, Peacock, alpha = a)
    drawPath(MarkGeometry.receipt, Color.White, alpha = a)
    val fold = Path().apply {
        moveTo(MarkGeometry.foldHinge1.x, MarkGeometry.foldHinge1.y)
        lineTo(MarkGeometry.foldClosed.x, MarkGeometry.foldClosed.y)
        lineTo(MarkGeometry.foldHinge2.x, MarkGeometry.foldHinge2.y)
        close()
    }
    drawPath(fold, Marigold, alpha = a)
    val r = MarkGeometry.RING_RADIUS
    drawArc(
        Peacock, MarkGeometry.RING_START, MarkGeometry.RING_SWEEP, false,
        topLeft = MarkGeometry.ringCenter - Offset(r, r), size = Size(2 * r, 2 * r),
        style = Stroke(MarkGeometry.RING_WIDTH), alpha = a,
    )
    drawRect(Peacock, Offset(22.4f, 41.6f), Size(19.2f, 3.2f), alpha = a)
}

/** Draws the mark scaled to [size] units with its top-left at [at]. */
internal fun DrawScope.drawMarkAt(at: Offset, size: Float, alpha: Float = 1f) {
    translate(at.x, at.y) { scale(size / 64f, pivot = Offset.Zero) { drawMarkStatic(alpha) } }
}

/**
 * A full-size illustration. [clock] and [pageOffset] are read only while drawing, so the scene
 * animates at the display rate without recomposition.
 */
@Composable
internal fun ArtCanvas(
    description: String,
    clock: () -> Float,
    pageOffset: () -> Float,
    reduced: Boolean,
    modifier: Modifier = Modifier,
    scene: DrawScope.(Frame) -> Unit,
) {
    val colors = rememberArtColors()
    val measurer = rememberTextMeasurer(cacheSize = 96)
    val scratch = remember { ArtScratch() }
    Spacer(
        modifier
            // Parallax layers of neighbouring pages must not spill into this one.
            .clipToBounds()
            .semantics {
                contentDescription = description
                role = Role.Image
            }
            .drawBehind {
                val k = min(size.width, size.height) / ART
                if (k <= 0f) return@drawBehind
                val f = Frame(
                    ms = if (reduced) SETTLED else clock(),
                    offset = if (reduced) 0f else pageOffset(),
                    reduced = reduced,
                    c = colors,
                    measurer = measurer,
                    scratch = scratch,
                    span = size.width / k,
                )
                translate((size.width - ART * k) / 2f, (size.height - ART * k) / 2f) {
                    scale(k, pivot = Offset.Zero) { scene(f) }
                }
            },
    )
}
