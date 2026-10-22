package app.openbill.ui

import app.openbill.ui.pdf.Accent
import app.openbill.ui.pdf.CopyMode
import app.openbill.ui.pdf.DrawOp
import app.openbill.ui.pdf.FontMetrics
import app.openbill.ui.pdf.ImageKind
import app.openbill.ui.pdf.InvoiceLayout
import app.openbill.ui.pdf.InvoiceLayoutEngine
import app.openbill.ui.pdf.InvoiceStyle
import app.openbill.ui.pdf.InvoiceTemplate
import app.openbill.ui.pdf.LayoutInput
import app.openbill.ui.pdf.LogoImage
import app.openbill.ui.pdf.Paper
import app.openbill.ui.pdf.Pen
import app.openbill.ui.pdf.PrintOptions
import app.openbill.ui.pdf.QrMatrix
import app.openbill.ui.pdf.SampleInvoice
import app.openbill.ui.pdf.TextStyle
import app.openbill.ui.settings.InMemorySettingsStore
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InvoiceLayoutTest {
    /** Monospace-ish fake: every character is half an em wide. */
    private val metrics = FontMetrics { text, _, size -> text.length * size * 0.5f }

    private fun layout(
        template: InvoiceTemplate,
        lines: Int = 6,
        options: PrintOptions = PrintOptions(),
        logo: LogoImage? = null,
        qr: QrMatrix? = null,
        interState: Boolean = false,
    ): InvoiceLayout = InvoiceLayoutEngine.layout(
        LayoutInput(SampleInvoice.document(lineCount = lines, interState = interState), InvoiceStyle(template, Accent.PEACOCK, options), metrics, logo, null, qr),
    )

    private fun InvoiceLayout.texts() = pages.flatMap { p -> p.ops.filterIsInstance<DrawOp.Text>() }
    private fun InvoiceLayout.allText() = texts().joinToString("\n") { it.text }

    @Test
    fun everyTemplateShowsMandatoryGstFields() {
        val doc = SampleInvoice.document()
        InvoiceTemplate.entries.forEach { t ->
            val text = layout(t).allText()
            listOf(doc.seller.gstin!!, doc.buyer!!.gstin!!, doc.number, doc.date, doc.grandTotal, "Lakeview Boutique").forEach {
                assertTrue(it in text, "${t.id} is missing $it")
            }
            assertTrue("Karnataka" in text, "${t.id} is missing the place of supply")
            if (!t.paper.thermal) {
                assertTrue("5208" in text, "${t.id} is missing HSN codes")
                assertTrue("Authorised signatory" in text, "${t.id} is missing the signatory")
                assertTrue("CGST" in text && "SGST" in text, "${t.id} is missing tax heads")
            }
        }
    }

    @Test
    fun interStateUsesIgst() {
        val text = layout(InvoiceTemplate.GST_DETAILED, interState = true).allText()
        assertTrue("IGST" in text)
        assertFalse("SGST" in text)
    }

    @Test
    fun sixtyLinesPaginateWithRepeatedHeaderAndPageNumbers() {
        InvoiceTemplate.entries.filterNot { it.paper.thermal }.forEach { t ->
            val l = layout(t, lines = 60)
            assertTrue(l.pages.size >= 2, "${t.id} should need more than one page")
            val n = l.pages.size
            val lastLinePage = l.pages.indexOfLast { p -> p.ops.any { it is DrawOp.Text && it.text == "60" } }
            l.pages.forEachIndexed { i, p ->
                val texts = p.ops.filterIsInstance<DrawOp.Text>().map { it.text }
                assertTrue(texts.any { it.contains("Page ${i + 1} of $n") }, "${t.id} page ${i + 1} lacks its number")
                // The table header (Rate column) is repeated on each page that has lines.
                if (i <= lastLinePage) assertTrue("Rate" in texts, "${t.id} page ${i + 1} lacks the table header")
                if (i < lastLinePage) assertTrue(texts.any { it == "Continued on next page" }, "${t.id} page ${i + 1} lacks the continued note")
                p.ops.forEach { op ->
                    if (op is DrawOp.Text) assertTrue(op.y <= p.height && op.y >= 0f, "${t.id} text outside the page: ${op.text}")
                }
            }
            val all = l.allText()
            (1..60).forEach { assertTrue("\n$it\n" in "\n$all\n", "${t.id} is missing line $it") }
            assertTrue(l.texts().any { it.text.contains("Total") }, "${t.id} lost the totals")
        }
    }

    @Test
    fun receiptIsOneContinuousPage() {
        val l = layout(InvoiceTemplate.RECEIPT_80_TAX, lines = 30)
        assertEquals(1, l.pages.size)
        assertEquals(Paper.THERMAL_80.width, l.pages[0].width)
        assertTrue(l.pages[0].height > 842f)
        assertTrue("GST summary" !in l.allText())
        assertTrue("Rate" in l.texts().map { it.text })
        val short = layout(InvoiceTemplate.RECEIPT_58, lines = 3)
        assertFalse(short.texts().any { it.text == "Taxable value" && it.size < 7f }, "short receipt must not print the tax table")
    }

    @Test
    fun amountsAreRightAlignedExactly() {
        val l = layout(InvoiceTemplate.MODERN)
        val doc = SampleInvoice.document()
        // Every line amount ends at the same x (the Amount column's right padding edge).
        val amounts = doc.lines.map { line -> l.texts().last { it.text == line.amount } }
        val rights = amounts.map { it.x + metrics.width(it.text, it.role, it.size) }
        rights.forEach { assertTrue(abs(it - rights[0]) < 0.01f, "amount ends at $it, expected ${rights[0]}") }
    }

    @Test
    fun longTextWrapsWithinWidthAndEllipsizes() {
        val pen = Pen(metrics)
        val style = TextStyle(10f)
        val lines = pen.wrap("one two three four five six seven eight nine ten", style, 60f)
        assertTrue(lines.size > 1)
        lines.forEach { assertTrue(metrics.width(it, style.role, style.size) <= 60f, "'$it' overflows") }
        val capped = pen.wrap("one two three four five six seven eight nine ten", style, 60f, maxLines = 2)
        assertEquals(2, capped.size)
        assertTrue(capped[1].endsWith("…"))
        val broken = pen.wrap("Supercalifragilisticexpialidocious", style, 40f)
        assertTrue(broken.size > 1)
    }

    @Test
    fun logoBoxOnlyWhenPresentAndEnabled() {
        val logo = LogoImage(byteArrayOf(1), 300, 100)
        InvoiceTemplate.entries.forEach { t ->
            val with = layout(t, logo = logo).pages[0].ops.filterIsInstance<DrawOp.Image>()
            assertEquals(1, with.count { it.kind == ImageKind.LOGO }, "${t.id} logo")
            assertTrue(abs(with[0].w / with[0].h - 3f) < 0.01f, "${t.id} logo aspect")
            assertTrue(layout(t).pages[0].ops.none { it is DrawOp.Image }, "${t.id} without logo")
            assertTrue(layout(t, logo = logo, options = PrintOptions(showLogo = false)).pages[0].ops.none { it is DrawOp.Image })
        }
    }

    @Test
    fun printOptionsRemoveSections() {
        val off = PrintOptions(showHsn = false, showAmountInWords = false, showTaxSummary = false, showBank = false, showNotes = false)
        val text = layout(InvoiceTemplate.GST_DETAILED, options = off).allText()
        assertFalse("5208" in text)
        assertFalse("Amount in words" in text)
        assertFalse("GST summary" in text)
        assertFalse("IFSC" in text)
        assertFalse("exchanged within 7 days" in text)
        val on = layout(InvoiceTemplate.GST_DETAILED, options = PrintOptions(terms = "Interest at 18% after due date.")).allText()
        listOf("Amount in words", "GST summary", "HSN/SAC summary", "IFSC", "Interest at 18%", "Freight").forEach { assertTrue(it in on, it) }
    }

    @Test
    fun allThreeCopiesTripleThePages() {
        val one = layout(InvoiceTemplate.SIMPLE)
        val three = layout(InvoiceTemplate.SIMPLE, options = PrintOptions(copies = CopyMode.ALL_THREE))
        assertEquals(one.pages.size * 3, three.pages.size)
        val text = three.allText()
        listOf("Original for recipient", "Duplicate for transporter", "Triplicate for supplier").forEach { assertTrue(it in text) }
    }

    @Test
    fun upiQrIsDrawnWhenConfigured() {
        val qr = QrMatrix(21, BooleanArray(21 * 21) { it % 3 == 0 })
        val opts = PrintOptions(upiId = "saffron@examplebank")
        val with = layout(InvoiceTemplate.MODERN, options = opts, qr = qr)
        assertTrue("saffron@examplebank" in with.allText())
        val rects = with.pages.sumOf { p -> p.ops.count { it is DrawOp.Rect } }
        val without = layout(InvoiceTemplate.MODERN, options = opts.copy(showUpiQr = false), qr = qr)
        assertTrue(rects > without.pages.sumOf { p -> p.ops.count { it is DrawOp.Rect } } + 20)
        val uri = InvoiceLayoutEngine.upiUri("saffron@examplebank", "Saffron Loom & Co", 123456, "INV/1")
        assertEquals("upi://pay?pa=saffron@examplebank&pn=Saffron%20Loom%20%26%20Co&am=1234.56&cu=INR&tn=Invoice%20INV%2F1", uri)
    }

    @Test
    fun styleRoundTripsThroughSettings() {
        val store = InMemorySettingsStore()
        val style = InvoiceStyle(InvoiceTemplate.LEDGER, Accent.WINE, PrintOptions(showHsn = false, copies = CopyMode.ALL_THREE, upiId = "a@b", terms = "x\ny"))
        InvoiceStyle.write(store, "f1", style)
        assertEquals(style, InvoiceStyle.read(store, "f1"))
        assertEquals(InvoiceStyle(), InvoiceStyle.read(store, "other"))
    }

    @Test
    fun taxSummaryIncludesChargePortion() {
        val doc = SampleInvoice.document()
        val r18 = doc.rateSummary.first { it.label == "GST 18%" }
        // Tailoring (1 x 350) plus freight 400 at 18%.
        assertEquals("750.00", r18.taxable)
        assertEquals("67.50", r18.cgst)
    }
}
