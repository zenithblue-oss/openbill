package app.openbill.ui.pdf

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import org.apache.fontbox.ttf.TTFParser
import org.apache.fontbox.ttf.TrueTypeFont
import org.apache.pdfbox.io.RandomAccessReadBuffer
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject
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
    private val lock = Any()

    // Devanagari is shaped by the JDK's OpenType engine (HarfBuzz: full GSUB reordering and GPOS
    // mark placement), not PDFBox's GSUB worker, which misorders reph + i-matra ("र्ति").
    // Sized at units-per-em so glyph positions come out in font units.
    private val devaAwt = devaBytes.mapValues { (r, b) ->
        java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, b.inputStream()).deriveFont(devaTtf.getValue(r).unitsPerEm.toFloat())
    }
    private val frc = java.awt.font.FontRenderContext(null, false, true)

    /** A shaped Devanagari run: glyph ids with pen positions (font units, y up) and the total advance. */
    class Shaped(val glyphs: IntArray, val x: FloatArray, val y: FloatArray, val advance: Float, val unitsPerEm: Int)

    fun shape(text: String, role: FontRole): Shaped {
        val gv = devaAwt.getValue(role).layoutGlyphVector(frc, text.toCharArray(), 0, text.length, java.awt.Font.LAYOUT_LEFT_TO_RIGHT)
        val n = gv.numGlyphs
        val codes = gv.getGlyphCodes(0, n, null)
        val pos = gv.getGlyphPositions(0, n + 1, null)
        // Joiners shape to the JDK's invisible glyph (0xFFFE/0xFFFF), which has nothing to draw.
        val keep = (0 until n).filter { codes[it] in 1 until 0xFFFE }
        return Shaped(
            IntArray(keep.size) { codes[keep[it]] }, FloatArray(keep.size) { pos[2 * keep[it]] }, FloatArray(keep.size) { -pos[2 * keep[it] + 1] },
            pos[2 * n], devaTtf.getValue(role).unitsPerEm,
        )
    }

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

    /** Advance of one run in points, exactly as [PdfBoxPainter] draws it. */
    fun runWidth(run: Run, role: FontRole, size: Float): Float =
        if (run.devanagari) shape(run.text, role).let { it.advance * size / it.unitsPerEm }
        else synchronized(lock) {
            val font = ttf.getValue(role)
            (run.text.codePoints().toArray().sumOf { font.getAdvanceWidth(glyph(font, it)).toDouble() } * size / font.unitsPerEm).toFloat()
        }

    val metrics = FontMetrics { text, role, size -> runs(text, role).sumOf { runWidth(it, role, size).toDouble() }.toFloat() }
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
                            var x = op.x
                            PdfFonts.runs(op.text, op.role).forEach { run ->
                                if (run.devanagari) {
                                    val font = deva.getOrPut(op.role) {
                                        // setFont registers the font for subsetting on save; the glyphs themselves go in a form.
                                        PDType0Font.load(doc, PdfFonts.devanagariBytes(op.role).inputStream(), subsetFonts).also { cs.setFont(it, op.size) }
                                    }
                                    cs.drawForm(shapedText(doc, page, font, PdfFonts.shape(run.text, op.role), run.text, x, h - op.y, op.size, op.color))
                                } else {
                                    cs.beginText()
                                    cs.setNonStrokingColor(Color(op.color, true))
                                    cs.setFont(fonts.getValue(op.role), op.size)
                                    cs.newLineAtOffset(x, h - op.y)
                                    cs.showText(run.text)
                                    cs.endText()
                                }
                                x += PdfFonts.runWidth(run, op.role, op.size)
                            }
                        }
                        is DrawOp.Image -> images[op.kind]?.let { cs.drawImage(it, op.x, h - op.y - op.h, op.w, op.h) }
                    }
                }
            }
        }
        return doc
    }

    /**
     * A shaped Devanagari run as a form XObject: each glyph id set at its shaped position
     * (PDFBox's showText would re-shape the text itself). ActualText keeps the run copyable
     * and searchable as the original Unicode, ligature glyphs included.
     */
    private fun shapedText(
        doc: PDDocument, page: PDPage, font: PDType0Font, s: PdfFonts.Shaped, text: String,
        x: Float, y: Float, size: Float, color: Argb,
    ): PDFormXObject {
        if (font.willBeSubset()) font.addGlyphsToSubset(s.glyphs.toSet())
        val form = PDFormXObject(doc)
        form.bBox = page.mediaBox
        form.resources = PDResources()
        val name = form.resources.add(font).name
        val scale = size / s.unitsPerEm
        fun f(v: Float) = String.format(java.util.Locale.ROOT, "%.3f", v)
        fun hex(b: ByteArray) = b.joinToString("") { String.format("%02X", it.toInt() and 0xFF) }
        val c = Color(color, true)
        val sb = StringBuilder()
        sb.append("/Span <</ActualText <FEFF").append(hex(text.toByteArray(Charsets.UTF_16BE))).append(">>> BDC\n")
        sb.append("BT /").append(name).append(' ').append(f(size)).append(" Tf ")
        sb.append(f(c.red / 255f)).append(' ').append(f(c.green / 255f)).append(' ').append(f(c.blue / 255f)).append(" rg\n")
        s.glyphs.forEachIndexed { i, g ->
            sb.append("1 0 0 1 ").append(f(x + s.x[i] * scale)).append(' ').append(f(y + s.y[i] * scale)).append(" Tm <")
                .append(hex(font.encodeGlyphId(g))).append("> Tj\n")
        }
        sb.append("ET\nEMC\n")
        form.contentStream.createOutputStream().use { it.write(sb.toString().toByteArray(Charsets.US_ASCII)) }
        return form
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
