package app.openbill.ui

import app.openbill.core.domain.model.Item
import app.openbill.core.domain.service.ItemValuation
import app.openbill.core.domain.service.StockPosition
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import app.openbill.ui.pos.posStockLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class PosStockLabelTest {
    private val at = Instant.fromEpochSeconds(0)
    private val item = Item("i", "f", "Soap", unitId = "u", lowStockLevel = Quantity.of(5), createdAt = at, updatedAt = at)
    private fun pos(vararg g: Pair<String, Int>) =
        StockPosition(g.associate { (k, v) -> k to Quantity.of(v.toLong()) }, ItemValuation(Quantity.of(0), Money.ZERO, Money.ZERO))

    @Test
    fun usesTheBillGodownOrAllGodowns() {
        val p = pos("G1" to 10, "G2" to 3)
        assertEquals("In stock: 10 pcs" to false, posStockLabel(item, p, "G1", "pcs"))
        assertEquals("Low stock: 3 pcs" to true, posStockLabel(item, p, "G2", "pcs"))
        assertEquals("In stock: 13 pcs" to false, posStockLabel(item, p, null, "pcs"))
    }

    @Test
    fun outOfStockAndUntrackedItems() {
        assertEquals("Out of stock" to true, posStockLabel(item, null, "G1", "pcs"))
        assertEquals("Out of stock (-2 pcs)" to true, posStockLabel(item, pos("G1" to -2), "G1", "pcs"))
        assertEquals(null, posStockLabel(item.copy(isService = true), null, null, null))
        assertEquals(null, posStockLabel(item.copy(trackInventory = false), null, null, null))
    }
}
