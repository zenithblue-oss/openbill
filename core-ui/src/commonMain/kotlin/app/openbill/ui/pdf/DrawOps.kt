package app.openbill.ui.pdf

/**
 * Platform-neutral drawing model for printable documents. Coordinates are PDF points
 * (1/72 inch) with the origin at the top-left of the page and y growing downwards, which is
 * how both Android's Canvas and Compose draw. The desktop PDFBox painter flips y itself.
 *
 * Layout is resolved completely before painting: text is already wrapped, truncated and
 * positioned (x is always the left edge of the run), so every painter only has to draw.
 */

/** Font weights used by templates. Every platform maps them to the bundled Noto Sans files. */
enum class FontRole(val assetPath: String) {
    REGULAR("fonts/NotoSans-Regular.ttf"),
    BOLD("fonts/NotoSans-Bold.ttf"),
}

/**
 * Text measurement supplied by the platform, using the same font files the painter draws with,
 * so right-aligned amounts and wrapped lines land exactly where the layout put them.
 */
fun interface FontMetrics {
    /** Advance width in points of [text] set in [role] at [size] points. */
    fun width(text: String, role: FontRole, size: Float): Float
}

/** 0xAARRGGBB colour. */
typealias Argb = Int

enum class ImageKind { LOGO, SIGNATURE }

sealed interface DrawOp {
    /** A single line of text. [y] is the baseline. */
    data class Text(
        val x: Float,
        val y: Float,
        val text: String,
        val role: FontRole,
        val size: Float,
        val color: Argb,
    ) : DrawOp

    /** Rectangle at ([x], [y]) of [w] x [h]; filled and/or stroked, optionally rounded. */
    data class Rect(
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val fill: Argb? = null,
        val stroke: Argb? = null,
        val strokeWidth: Float = 0.6f,
        val radius: Float = 0f,
    ) : DrawOp

    data class Line(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val color: Argb,
        val width: Float = 0.6f,
    ) : DrawOp

    /** A firm image (logo or signature), already fitted (aspect ratio kept) into this box. */
    data class Image(val x: Float, val y: Float, val w: Float, val h: Float, val kind: ImageKind = ImageKind.LOGO) : DrawOp
}

class PdfPage(val ops: List<DrawOp>, val width: Float, val height: Float)

/** A laid-out document: what every painter consumes. Thermal pages have a content-sized height. */
class InvoiceLayout(
    val pages: List<PdfPage>,
    val images: Map<ImageKind, LogoImage> = emptyMap(),
)

/** Encoded image (PNG or JPEG) and its pixel size, used for aspect-correct fitting. */
class LogoImage(val bytes: ByteArray, val widthPx: Int, val heightPx: Int) {
    init {
        require(widthPx > 0 && heightPx > 0) { "Image must have a size" }
    }

    /** Largest box with this image's aspect ratio inside [w] x [h], aligned by [alignX] (0 left, 0.5 centre, 1 right). */
    fun fit(x: Float, y: Float, w: Float, h: Float, kind: ImageKind, alignX: Float = 0f): DrawOp.Image {
        val scale = minOf(w / widthPx, h / heightPx)
        val fw = widthPx * scale
        val fh = heightPx * scale
        return DrawOp.Image(x + (w - fw) * alignX, y + (h - fh) / 2f, fw, fh, kind)
    }
}

/** Decodes PNG/JPEG bytes for on-screen display (logo previews). */
expect fun decodeImageBitmap(bytes: ByteArray): androidx.compose.ui.graphics.ImageBitmap?
