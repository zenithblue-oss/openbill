package app.openbill.ui.pdf

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import org.apache.fontbox.ttf.TTFParser
import org.apache.fontbox.ttf.TrueTypeFont
import org.apache.fontbox.ttf.gsub.GsubWorkerFactory
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
 * The bundled Noto Sans and Noto Sans Devanagari files (OFL) read from the classpath
 * (core-printing pdf-assets), with glyph-advance metrics that match what PDFBox draws: no
 * kerning, and Devanagari measured after the same GSUB shaping PDFBox applies when it shows text.
 */
object PdfFonts {
    private fun resource(path: String) = (PdfFonts::class.java.classLoader.getResourceAsStream(path)
        ?: error("Missing font resource $path")).use { it.readBytes() }

    private val bytes = FontRole.entries.associateWith { resource(it.assetPath) }
    private val devaBytes = FontRole.entries.associateWith { resource(it.devanagariAsset) }

    private val ttf: Map<FontRole, TrueTypeFont> = bytes.mapValues { (_, b) -> TTFParser().parse(RandomAccessReadBuffer(b)) }
    private val devaTtf: Map<FontRole, TrueTypeFont> = devaBytes.mapValues { (_, b) -> TTFParser().parse(RandomAccessReadBuffer(b)) }
    private val devaGsub = devaTtf.mapValues { (_, f) -> GsubWorkerFactory().getGsubWorker(f.getUnicodeCmapLookup(), f.getGsubData()) }
    private val lock = Any()

    fun fontBytes(role: FontRole): ByteArray = bytes.getValue(role)
    fun devanagariBytes(role: FontRole): ByteArray = devaBytes.getValue(role)

    private fun glyph(font: TrueTypeFont, cp: Int): Int = font.getUnicodeCmapLookup().getGlyphId(cp)

    /** A run of [text] drawn with one font: the Devanagari font when [devanagari]. */
    data class Run(val text: String, val devanagari: Boolean)

    /**
     * [text] split into Latin and Devanagari runs; characters neither font can draw become '?',
     * so nothing is blank or throws. Spaces and punctuation stay with the run they sit in.
     */
    fun runs(text: String, role: FontRole): List<Run> = synchronized(lock) {
        val latin = ttf.getValue(role)
        val deva = devaTtf.getValue(role)
        val out = mutableListOf<Run>()
        val sb = StringBuilder()
        var current: Boolean? = null
        var i = 0
        while (i < text.length) {
            var cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == '\n'.code || cp == '\t'.code) cp = ' '.code
            val inDeva = glyph(deva, cp) != 0
            val inLatin = glyph(latin, cp) != 0
            val useDeva = when {
                isDevanagari(cp) && inDeva -> true
                inLatin && inDeva && current == true && !cp.toChar().isLetter() -> true // keep spaces and danda-like marks in a Hindi run
                inLatin -> false
                inDeva -> true
                else -> { cp = '?'.code; current ?: false }
            }
            if (current != null && useDeva != current) { out += Run(sb.toString(), current); sb.clear() }
            current = useDeva
            sb.appendCodePoint(cp)
        }
        if (current != null) out += Run(sb.toString(), current)
        out
    }

    /** The text as it will be drawn, unsupported characters as '?'. */
    fun sanitize(text: String, role: FontRole): String = runs(text, role).joinToString("") { it.text }

    val metrics = FontMetrics { text, role, size ->
        val parts = runs(text, role)
        synchronized(lock) {
            parts.sumOf { run ->
                val font = if (run.devanagari) devaTtf.getValue(role) else ttf.getValue(role)
                var ids = run.text.codePoints().toArray().map { glyph(font, it) }
                if (run.devanagari) ids = devaGsub.getValue(role).applyTransforms(ids)
                (ids.sumOf { font.getAdvanceWidth(it).toDouble() } * size / font.unitsPerEm).toDouble()
            }.toFloat()
        }
    }
}

/** Paints an [InvoiceLayout] with PDFBox: embedded font subsets, vector shapes, PNG/JPEG images. */
object PdfBoxPainter {
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
        // Loaded only when a page has Hindi text, so English invoices stay as small as before.
        val deva = mutableMapOf<FontRole, PDType0Font>()
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
                            val runs = PdfFonts.runs(op.text, op.role)
                            if (runs.isNotEmpty()) {
                                cs.beginText()
                                cs.setNonStrokingColor(Color(op.color, true))
                                cs.newLineAtOffset(op.x, h - op.y)
                                // Each showText advances the text position, so runs follow each other.
                                runs.forEach { run ->
                                    val font = if (run.devanagari) {
                                        deva.getOrPut(op.role) { PDType0Font.load(doc, PdfFonts.devanagariBytes(op.role).inputStream(), subsetFonts) }
                                    } else fonts.getValue(op.role)
                                    cs.setFont(font, op.size)
                                    cs.showText(run.text)
                                }
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
