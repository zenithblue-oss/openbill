package app.openbill.core.domain.usecase

import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherLine
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

class DuplicateVoucherTest {
    @Test
    fun copiesLinesIntoAFreshDraft() {
        val at = Instant.fromEpochMilliseconds(0)
        val v = Voucher(
            id = "V1", firmId = "F1", financialYearId = "FY1", type = VoucherType.SALE, number = "INV-1", date = LocalDate(2026, 4, 2),
            partyId = "P1", status = VoucherStatus.CANCELLED, placeOfSupplyStateCode = "27", cancellationReason = "x", cancelledAt = at, postedAt = at,
            lines = listOf(VoucherLine(id = "L1", lineNumber = 1, description = "Bolt", quantity = Quantity.of(2), rate = Money.parse("5.00"), serialNumbers = listOf("S1", "S2"))),
            total = Money.parse("10.00"), createdAt = at, updatedAt = at,
        )
        val d = v.duplicateAsDraft("V2", LocalDate(2026, 10, 1), "FY1", { "N$it" }, at)
        assertEquals(VoucherStatus.DRAFT, d.status)
        assertNull(d.number)
        assertNull(d.cancellationReason)
        assertEquals("N0", d.lines.single().id)
        assertEquals(emptyList(), d.lines.single().serialNumbers)
        assertEquals(v.total, d.total)
        assertEquals("P1", d.partyId)
    }
}
