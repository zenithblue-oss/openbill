package app.openbill.core.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VoucherNumberingTest {
    @Test
    fun addsSeparatorAfterAlphanumericPrefix() {
        assertEquals("INV-0001", VoucherNumbering.format("INV", 1, 4))
        assertEquals("SALE-001", VoucherNumbering.format("SALE", 1, 3))
    }

    @Test
    fun preservesExplicitSeparatorAndSupportsFinancialYearPrefixes() {
        assertEquals("INV-0001", VoucherNumbering.format("INV-", 1, 4))
        assertEquals("INV/26-27/0001", VoucherNumbering.format("INV/26-27/", 1, 4))
    }

    @Test
    fun rejectsInvalidInputs() {
        assertFailsWith<IllegalArgumentException> { VoucherNumbering.format(" ", 1, 4) }
        assertFailsWith<IllegalArgumentException> { VoucherNumbering.format("INV", 0, 4) }
        assertFailsWith<IllegalArgumentException> { VoucherNumbering.format("INV", 1, 0) }
    }

    @Test
    fun capsNumbersAtGstSixteenCharacters() {
        assertEquals("ABCDEFGHIJK-0001", VoucherNumbering.format("ABCDEFGHIJK", 1, 4))
        assertFailsWith<IllegalArgumentException> { VoucherNumbering.format("ABCDEFGHIJKL", 1, 4) }
        assertFailsWith<IllegalArgumentException> { VoucherNumbering.format("ABCDEFGHIJK", 10_000, 4) }
    }

    @Test
    fun seriesIsAppendedOnlyWhenSynced() {
        assertEquals("INV", VoucherNumbering.seriesPrefix("INV", null))
        assertEquals("INV-B", VoucherNumbering.seriesPrefix("INV ", "B"))
        assertEquals("INV/B", VoucherNumbering.seriesPrefix("INV/", "B"))
    }
}
