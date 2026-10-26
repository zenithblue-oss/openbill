package app.openbill.ui

import app.openbill.ui.pdf.BarcodeLabel
import app.openbill.ui.pdf.BarcodeLabels
import app.openbill.ui.pdf.DrawOp
import app.openbill.ui.pdf.FontMetrics
import app.openbill.ui.pdf.Ink
import app.openbill.ui.pdf.Paper
import app.openbill.ui.pdf.encodeCode128
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BarcodeLabelsTest {
    private val metrics = FontMetrics { text, _, size -> text.length * size * 0.5f }

    @Test
    fun code128EncodesWithStartAndStopPatterns() {
        val bars = assertNotNull(encodeCode128("OB-000123"))
        assertTrue(bars.first() && bars.last(), "starts and ends with a bar")
        // Code 128: 11 modules per symbol and a 13-module stop.
        assertEquals(0, (bars.size - 13) % 11)
        assertNull(encodeCode128(""))
        assertNull(encodeCode128("café ₹"))
    }

    @Test
    fun pagesHoldTwentyFourLabelsEachWithOneBarcode() {
        val codes = listOf("8901234567890", "SKU-42", "A1")
        val labels = List(50) { BarcodeLabel("Item $it", codes[it % codes.size], if (it % 2 == 0) "₹10.00" else null) }
        val layout = BarcodeLabels.layout(labels, metrics)
        assertEquals(3, layout.pages.size)
        layout.pages.forEach { assertEquals(Paper.A4.width, it.width); assertEquals(Paper.A4.height, it.height) }
        val runs = codes.associateWith { c -> encodeCode128(c)!!.let { b -> b.indices.count { b[it] && (it == 0 || !b[it - 1]) } } }
        val ops = layout.pages.flatMap { it.ops }
        val bars = ops.filterIsInstance<DrawOp.Rect>().count { it.fill == Ink.BLACK }
        assertEquals(labels.sumOf { runs.getValue(it.code) }, bars)
        val texts = ops.filterIsInstance<DrawOp.Text>().map { it.text }
        codes.forEach { c -> assertEquals(labels.count { it.code == c }, texts.count { it == c }) }
        assertEquals(25, texts.count { it == "₹10.00" })
        assertEquals(listOf(24, 24, 2), layout.pages.map { p -> p.ops.count { it is DrawOp.Rect && it.stroke != null } })
    }

    @Test
    fun emptyListStillGivesOnePage() {
        assertEquals(1, BarcodeLabels.layout(emptyList(), metrics).pages.size)
    }
}
