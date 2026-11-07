package app.openbill.core.domain.usecase

import app.openbill.core.domain.model.Item
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import app.openbill.core.model.Rate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class BulkItemEditTest {
    private val at = Instant.fromEpochMilliseconds(1_000)
    private val later = Instant.fromEpochMilliseconds(2_000)
    private val item = Item("i", "f", "Soap", unitId = "pcs", categoryId = "c1", salePrice = Money.of(10_000), alternateUnitId = "box", alternateUnitFactor = Quantity.of(12), createdAt = at, updatedAt = at)

    @Test
    fun pricesParseAsSetOrPercent() {
        assertEquals(PriceChange.SetTo(Money.of(12_050)), PriceChange.parse("120.50"))
        assertEquals(PriceChange.ByPercent(1_000), PriceChange.parse("+10%"))
        assertEquals(PriceChange.ByPercent(-250), PriceChange.parse("-2.5 %"))
        assertNull(PriceChange.parse("-5"), "a negative price")
        assertNull(PriceChange.parse("abc"))
        assertNull(PriceChange.parse("-100%"), "would make every price zero")
        assertEquals(Money.of(11_000), PriceChange.ByPercent(1_000).apply(Money.of(10_000)))
        assertEquals(Money.of(3), PriceChange.ByPercent(1_000).apply(Money.of(3)), "3 paise + 10% rounds to 3")
        assertEquals(Money.of(9_667), PriceChange.ByPercent(-333).apply(Money.of(10_000)))
    }

    @Test
    fun applyChangesOnlyWhatIsSet() {
        assertNull(BulkItemEdit(categoryId = "c1").apply(item, later), "nothing changes")
        val moved = BulkItemEdit(categoryId = BulkItemEdit.NO_CATEGORY, taxRate = Rate.ofPercent(5), salePrice = PriceChange.parse("+10%")).apply(item, later)!!
        assertNull(moved.categoryId)
        assertEquals(Rate.ofPercent(5), moved.taxRate)
        assertEquals(Money.of(11_000), moved.salePrice)
        assertEquals("pcs", moved.unitId)
        assertEquals(later, moved.updatedAt)
        val boxed = BulkItemEdit(unitId = "box").apply(item, later)!!
        assertEquals("box", boxed.unitId)
        assertNull(boxed.alternateUnitId, "the alternate unit cannot equal the unit")
        assertNull(boxed.alternateUnitFactor)
    }
}
