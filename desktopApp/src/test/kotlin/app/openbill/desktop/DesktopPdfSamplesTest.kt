package app.openbill.desktop

import app.openbill.ui.pdf.Accent
import app.openbill.ui.pdf.ImageKind
import app.openbill.ui.pdf.InvoiceLayoutEngine
import app.openbill.ui.pdf.InvoiceStyle
import app.openbill.ui.pdf.InvoiceTemplate
import app.openbill.ui.pdf.LayoutInput
import app.openbill.ui.pdf.LogoImage
import app.openbill.ui.pdf.PrintOptions
import app.openbill.ui.pdf.SampleInvoice
import app.openbill.ui.pdf.encodeQr
import app.openbill.ui.pdf.PdfBoxPainter
import app.openbill.ui.pdf.PdfFonts
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper

/**
 * Renders every template through the real PDFBox painter and checks the result. When the
 * openbill.pdfSamples system property (env OPENBILL_PDF_SAMPLES) is set, the PDFs are also
 * written there for visual review.
 */
class DesktopPdfSamplesTest {
    private fun sampleLogo(): LogoImage {
        val img = BufferedImage(360, 120, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0xB5, 0x4A, 0x1E)
        g.fillOval(4, 4, 112, 112)
        g.color = Color.WHITE
        g.stroke = BasicStroke(8f)
        g.drawArc(30, 30, 60, 60, 30, 300)
        g.color = Color(0x3A, 0x2A, 0x20)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 44)
        g.drawString("Saffron", 130, 62)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 26)
        g.drawString("LOOM", 132, 98)
        g.dispose()
        return DesktopPlatformServices.downscaleToPng(img, 600)
    }

    private fun sampleSignature(): LogoImage {
        val img = BufferedImage(300, 90, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0x1A, 0x2A, 0x6C)
        g.stroke = BasicStroke(3f)
        val xs = intArrayOf(10, 40, 60, 80, 100, 130, 150, 180, 210, 240, 280)
        val ys = intArrayOf(60, 20, 70, 30, 65, 25, 60, 35, 55, 40, 50)
        g.drawPolyline(xs, ys, xs.size)
        g.dispose()
        return DesktopPlatformServices.downscaleToPng(img, 600)
    }

    @Test
    fun rendersEveryTemplate() {
        val out = System.getProperty("openbill.pdfSamples")?.let { File(it).apply { mkdirs() } }
        val logo = sampleLogo()
        val signature = sampleSignature()
        val options = PrintOptions(upiId = "saffronloom@examplebank", terms = "1. Payment due within 15 days.\n2. Interest at 18% p.a. on overdue amounts.")
        val cases = InvoiceTemplate.entries.map { it to 6 } + listOf(InvoiceTemplate.GST_DETAILED to 60, InvoiceTemplate.MODERN to 60, InvoiceTemplate.COUNTER to 60)
        cases.forEach { (t, lines) ->
            val doc = SampleInvoice.document(lineCount = lines)
            val qr = encodeQr(InvoiceLayoutEngine.upiUri(options.upiId, doc.seller.name, doc.grandTotalPaise, doc.number))
            val layout = InvoiceLayoutEngine.layout(LayoutInput(doc, InvoiceStyle(t, Accent.PEACOCK, options), PdfFonts.metrics, logo, signature, qr))
            val bytes = PdfBoxPainter.toPdf(layout)
            Loader.loadPDF(bytes).use { pdf ->
                assertEquals(layout.pages.size, pdf.numberOfPages)
                val text = PDFTextStripper().getText(pdf)
                assertTrue("₹" in text, "${t.id}: rupee sign must be real text")
                assertTrue(doc.number in text, "${t.id}: invoice number")
            }
            assertTrue(layout.images.containsKey(ImageKind.LOGO))
            out?.let { File(it, "${t.id}${if (lines > 6) "-$lines" else ""}.pdf").writeBytes(bytes) }
        }
        out?.let { dir ->
            // One accent variant and a thumbnail through the preview path.
            val doc = SampleInvoice.document()
            val l = InvoiceLayoutEngine.layout(LayoutInput(doc, InvoiceStyle(InvoiceTemplate.MODERN, Accent.WINE, options), PdfFonts.metrics, logo, signature, null))
            File(dir, "modern-wine.pdf").writeBytes(PdfBoxPainter.toPdf(l))
            ImageIO.write(PdfBoxPainter.preview(l, 0, 300), "png", File(dir, "modern-wine-thumb.png"))
        }
    }

    @Test
    fun unsupportedGlyphsBecomeQuestionMarks() {
        assertEquals("Ram ????", PdfFonts.sanitize("Ram राम्", app.openbill.ui.pdf.FontRole.REGULAR).let { it.substring(0, 4) + it.substring(4).replace(Regex("\\?+"), "????") })
        assertTrue(PdfFonts.metrics.width("₹1,000.00", app.openbill.ui.pdf.FontRole.BOLD, 10f) > 30f)
    }
}
