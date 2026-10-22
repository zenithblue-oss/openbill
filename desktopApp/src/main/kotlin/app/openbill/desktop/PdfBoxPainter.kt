package app.openbill.desktop

import app.openbill.ui.pdf.DrawOp
import app.openbill.ui.pdf.FontMetrics
import app.openbill.ui.pdf.FontRole
import app.openbill.ui.pdf.ImageKind
import app.openbill.ui.pdf.InvoiceLayout
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import org.apache.fontbox.ttf.TTFParser
import org.apache.fontbox.ttf.TrueTypeFont
import org.apache.pdfbox.io.RandomAccessReadBuffer
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer

/**
 * The bundled Noto Sans files (OFL) read from the classpath (core-ui's pdf-assets), with
 * glyph-advance metrics that match what PDFBox draws (PDFBox applies no kerning).
 */
internal object PdfFonts {
    private val bytes = FontRole.entries.associateWith { role ->
        (PdfFonts::class.java.classLoader.getResourceAsStream(role.assetPath)
            ?: error("Missing font resource ${role.assetPath}")).use { it.readBytes() }
    }

    private val ttf: Map<FontRole, TrueTypeFont> = bytes.mapValues { (_, b) -> TTFParser().parse(RandomAccessReadBuffer(b)) }
    private val lock = Any()

    fun fontBytes(role: FontRole): ByteArray = bytes.getValue(role)

    private fun glyph(role: FontRole, cp: Int): Int = ttf.getValue(role).getUnicodeCmapLookup().getGlyphId(cp)

    /** Replaces characters the font cannot draw (e.g. Devanagari) with '?', so nothing is blank or throws. */
    fun sanitize(text: String, role: FontRole): String = synchronized(lock) {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = Character.charCount(cp)
            if (cp == '\n'.code || cp == '\t'.code) sb.append(' ') else if (glyph(role, cp) != 0) sb.appendCodePoint(cp) else sb.append('?')
            i += n
        }
        sb.toString()
    }

    val metrics = FontMetrics { text, role, size ->
        val clean = sanitize(text, role)
        synchronized(lock) {
            val font = ttf.getValue(role)
            val upem = font.unitsPerEm.toFloat()
            var w = 0f
            var i = 0
            while (i < clean.length) {
                val cp = clean.codePointAt(i)
                w += font.getAdvanceWidth(glyph(role, cp))
                i += Character.charCount(cp)
            }
            w * size / upem
        }
    }
}

/** Paints an [InvoiceLayout] with PDFBox: embedded font subsets, vector shapes, PNG/JPEG images. */
internal object PdfBoxPainter {
    fun toPdf(layout: InvoiceLayout): ByteArray = document(layout).use { doc ->
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    fun preview(layout: InvoiceLayout, pageIndex: Int, widthPx: Int): BufferedImage = document(layout, subsetFonts = false).use { doc ->
        val page = layout.pages[pageIndex]
        PDFRenderer(doc).renderImage(pageIndex, widthPx / page.width, ImageType.RGB)
    }

    /**
     * Font subsets are only built when a document is saved, so a document that is rendered or
     * printed directly (never saved) must embed the full fonts ([subsetFonts] = false).
     */
    fun document(layout: InvoiceLayout, subsetFonts: Boolean = true): PDDocument {
        val doc = PDDocument()
        doc.documentInformation.creator = "OpenBill"
        doc.documentInformation.producer = "OpenBill (Apache PDFBox)"
        val fonts = FontRole.entries.associateWith { PDType0Font.load(doc, PdfFonts.fontBytes(it).inputStream(), subsetFonts) }
        val images = layout.images.mapValues { (kind, img) -> PDImageXObject.createFromByteArray(doc, img.bytes, kind.name.lowercase()) }
        layout.pages.forEach { p ->
            val page = PDPage(PDRectangle(p.width, p.height))
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                val h = p.height
                p.ops.forEach { op ->
                    when (op) {
                        is DrawOp.Rect -> rect(cs, op, h)
                        is DrawOp.Line -> {
                            cs.setStrokingColor(Color(op.color, true))
                            cs.setLineWidth(op.width)
                            cs.moveTo(op.x1, h - op.y1)
                            cs.lineTo(op.x2, h - op.y2)
                            cs.stroke()
                        }
                        is DrawOp.Text -> {
                            val text = PdfFonts.sanitize(op.text, op.role)
                            if (text.isNotEmpty()) {
                                cs.beginText()
                                cs.setFont(fonts.getValue(op.role), op.size)
                                cs.setNonStrokingColor(Color(op.color, true))
                                cs.newLineAtOffset(op.x, h - op.y)
                                cs.showText(text)
                                cs.endText()
                            }
                        }
                        is DrawOp.Image -> images[op.kind]?.let { cs.drawImage(it, op.x, h - op.y - op.h, op.w, op.h) }
                    }
                }
            }
        }
        return doc
    }

    private fun rect(cs: PDPageContentStream, r: DrawOp.Rect, pageH: Float) {
        if (r.fill == null && r.stroke == null) return
        val x = r.x
        val y = pageH - r.y - r.h
        val rad = minOf(r.radius, r.w / 2f, r.h / 2f)
        fun path() {
            if (rad <= 0f) {
                cs.addRect(x, y, r.w, r.h)
                return
            }
            val c = rad * 0.5523f
            cs.moveTo(x + rad, y)
            cs.lineTo(x + r.w - rad, y)
            cs.curveTo(x + r.w - rad + c, y, x + r.w, y + rad - c, x + r.w, y + rad)
            cs.lineTo(x + r.w, y + r.h - rad)
            cs.curveTo(x + r.w, y + r.h - rad + c, x + r.w - rad + c, y + r.h, x + r.w - rad, y + r.h)
            cs.lineTo(x + rad, y + r.h)
            cs.curveTo(x + rad - c, y + r.h, x, y + r.h - rad + c, x, y + r.h - rad)
            cs.lineTo(x, y + rad)
            cs.curveTo(x, y + rad - c, x + rad - c, y, x + rad, y)
            cs.closePath()
        }
        r.fill?.let {
            cs.setNonStrokingColor(Color(it, true))
            path()
            cs.fill()
        }
        r.stroke?.let {
            cs.setStrokingColor(Color(it, true))
            cs.setLineWidth(r.strokeWidth)
            path()
            cs.stroke()
        }
    }

    @Suppress("unused")
    private val kinds = ImageKind.entries
}
