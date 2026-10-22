package app.openbill.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import app.openbill.ui.pdf.DrawOp
import app.openbill.ui.pdf.FontMetrics
import app.openbill.ui.pdf.FontRole
import app.openbill.ui.pdf.ImageKind
import app.openbill.ui.pdf.InvoiceLayout
import app.openbill.ui.pdf.PdfPage
import java.io.OutputStream

/**
 * Paints [InvoiceLayout] pages onto an Android [Canvas]: into a [PdfDocument] for sharing and
 * printing, or into a [Bitmap] for previews, so the preview is the PDF. Fonts are the bundled
 * Noto Sans files in assets/fonts (the same files desktop embeds).
 */
class AndroidPdfPainter(context: Context) {
    private val typefaces: Map<FontRole, Typeface> = FontRole.entries.associateWith { role ->
        runCatching { Typeface.createFromAsset(context.assets, role.assetPath) }.getOrElse {
            if (role == FontRole.BOLD) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    private val measurePaints = ThreadLocal.withInitial { FontRole.entries.associateWith { textPaint(it, 10f, 0) } }

    private fun textPaint(role: FontRole, size: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = typefaces.getValue(role)
        textSize = size
        this.color = color
    }

    /** Measures with the exact Paint configuration used for drawing. */
    val metrics = FontMetrics { text, role, size ->
        val p = measurePaints.get()!!.getValue(role)
        p.textSize = size
        p.measureText(text)
    }

    fun writePdf(layout: InvoiceLayout, out: OutputStream) {
        val pdf = PdfDocument()
        try {
            val images = decodeImages(layout)
            layout.pages.forEachIndexed { i, p ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(Math.round(p.width), Math.round(p.height), i + 1).create())
                paint(page.canvas, p, images)
                pdf.finishPage(page)
            }
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }

    fun renderBitmap(layout: InvoiceLayout, pageIndex: Int, widthPx: Int): Bitmap {
        val p = layout.pages[pageIndex]
        val scale = widthPx / p.width
        val bmp = Bitmap.createBitmap(widthPx, maxOf(1, Math.round(p.height * scale)), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.scale(scale, scale)
        paint(canvas, p, decodeImages(layout))
        return bmp
    }

    private fun decodeImages(layout: InvoiceLayout): Map<ImageKind, Bitmap> =
        layout.images.mapNotNull { (k, v) -> BitmapFactory.decodeByteArray(v.bytes, 0, v.bytes.size)?.let { k to it } }.toMap()

    private fun paint(c: Canvas, page: PdfPage, images: Map<ImageKind, Bitmap>) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val text = FontRole.entries.associateWith { textPaint(it, 10f, 0) }
        page.ops.forEach { op ->
            when (op) {
                is DrawOp.Rect -> {
                    val r = RectF(op.x, op.y, op.x + op.w, op.y + op.h)
                    op.fill?.let {
                        fill.color = it
                        if (op.radius > 0f) c.drawRoundRect(r, op.radius, op.radius, fill) else c.drawRect(r, fill)
                    }
                    op.stroke?.let {
                        stroke.color = it
                        stroke.strokeWidth = op.strokeWidth
                        if (op.radius > 0f) c.drawRoundRect(r, op.radius, op.radius, stroke) else c.drawRect(r, stroke)
                    }
                }
                is DrawOp.Line -> {
                    stroke.color = op.color
                    stroke.strokeWidth = op.width
                    c.drawLine(op.x1, op.y1, op.x2, op.y2, stroke)
                }
                is DrawOp.Text -> {
                    val p = text.getValue(op.role)
                    p.textSize = op.size
                    p.color = op.color
                    c.drawText(op.text, op.x, op.y, p)
                }
                is DrawOp.Image -> images[op.kind]?.let { c.drawBitmap(it, null, RectF(op.x, op.y, op.x + op.w, op.y + op.h), bitmapPaint) }
            }
        }
    }
}
