package app.openbill.ui.pdf

import app.openbill.ui.invoice.InvoiceDocument
import kotlin.test.Test
import kotlin.test.assertEquals

class EscPosTest {
    private val doc = InvoiceDocument(
        fileStem = "INV-1",
        title = "Tax Invoice",
        statusNote = null,
        seller = InvoiceDocument.PartyBlock("Sharma Stores", listOf("12 MG Road, Pune"), "27AAAAA0000A1Z5", "Maharashtra", "9800000000"),
        buyer = InvoiceDocument.PartyBlock("Asha Traders", emptyList(), null, null, null),
        number = "INV-1",
        date = "01-04-2026",
        dueDate = null,
        placeOfSupply = "Maharashtra (27)",
        isIntraState = true,
        lines = listOf(
            InvoiceDocument.Line(1, "Basmati rice premium long grain 5 kg bag", "1006", "2 BAG", "₹450.00", "-", "900.00", "5%", "45.00", "₹945.00"),
        ),
        totals = listOf("Taxable" to "₹900.00", "CGST" to "₹22.50", "SGST" to "₹22.50"),
        grandTotal = "₹945.00",
        amountInWords = "Rupees Nine Hundred Forty Five Only",
        notes = null,
        bank = null,
    )

    /** Printable ASCII as is, newline as "\n", every other byte as <XX>. */
    private fun ByteArray.show() = joinToString("") { b ->
        val c = b.toInt() and 0xFF
        when (c) {
            0x0A -> "\n"
            in 0x20..0x7E -> c.toChar().toString()
            else -> "<" + c.toString(16).uppercase().padStart(2, '0') + ">"
        }
    }

    @Test
    fun receipt58MatchesGolden() {
        assertEquals(GOLDEN_58, EscPos.receipt(doc, Paper.THERMAL_58).show())
    }

    @Test
    fun eightyMillimetreUses48Columns() {
        val lines = EscPos.receipt(doc, Paper.THERMAL_80).show().lines()
        assertEquals("-".repeat(48), lines.first { it.startsWith("---") })
        assertEquals("TOTAL" + "Rs.945.00".padStart(43), lines.first { it.contains("TOTAL") }.substringAfterLast("<01>"))
    }

    private companion object {
        val GOLDEN_58 = """
            |<1B>@<1B>a<01><1B>E<01><1D>!<11>Sharma Stores
            |<1D>!<00><1B>E<00>12 MG Road, Pune
            |GSTIN: 27AAAAA0000A1Z5
            |Ph: 9800000000
            |--------------------------------
            |<1B>E<01>TAX INVOICE
            |<1B>E<00><1B>a<00>No. INV-1             01-04-2026
            |To: Asha Traders
            |--------------------------------
            |1. Basmati rice premium long
            |grain 5 kg bag
            |   2 BAG x Rs.450.00   Rs.945.00
            |--------------------------------
            |Taxable                Rs.900.00
            |CGST                    Rs.22.50
            |SGST                    Rs.22.50
            |<1B>E<01><1D>!<01>TOTAL                  Rs.945.00
            |<1D>!<00><1B>E<00>Rupees Nine Hundred Forty Five
            |Only
            |--------------------------------
            |<1B>a<01>Thank you. Please visit again.
            |
            |
            |
            |<1D>VB<00>
        """.trimMargin()
    }
}
