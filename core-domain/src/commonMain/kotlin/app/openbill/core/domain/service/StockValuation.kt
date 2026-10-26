package app.openbill.core.domain.service

import app.openbill.core.domain.model.Item
import app.openbill.core.domain.model.StockMovement
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import app.openbill.core.model.Rounding
import app.openbill.core.model.RoundingMode

/** On-hand quantity and value of one item (all godowns) under moving weighted-average cost. */
data class ItemValuation(
    val quantity: Quantity,
    val value: Money,
    val averageCost: Money,
)

/** One item's stock position: per-godown balances plus the item-wise valuation. */
data class ItemStock(
    val item: Item,
    val byGodown: Map<String, Quantity>,
    val valuation: ItemValuation,
) {
    val quantity: Quantity get() = valuation.quantity
    val isLow: Boolean get() = item.lowStockLevel?.let { quantity <= it } ?: false
}

/**
 * Costing method: moving weighted average, per item across godowns, in movement order
 * (date, then creation time). An inward movement with a unit cost adds quantity x cost; one
 * without (a sales return, a transfer leg) comes in at the running average. Outward movements
 * leave at the running average, so transfers never change value. A reversal of an inward
 * movement (negative quantity with a cost) removes value at that same cost. When the quantity
 * reaches zero or below the value resets to zero and the last average is kept for later
 * cost-less inwards.
 */
object StockValuation {
    private const val UNIT = 10_000L

    fun value(movements: List<StockMovement>): ItemValuation {
        var quantity = 0L
        var value = 0L
        var average = 0L
        val ordered = movements.sortedWith(compareBy<StockMovement> { it.date }.thenBy { it.createdAt }.thenBy { it.id })
        for (m in ordered) {
            val q = m.quantity.scaledUnits
            val cost = m.unitCost?.paise ?: average
            value += Rounding.multiplyDivide(q, cost, UNIT, RoundingMode.HALF_UP)
            quantity += q
            if (quantity <= 0L) {
                value = 0L
            } else {
                average = Rounding.multiplyDivide(value, UNIT, quantity, RoundingMode.HALF_UP)
            }
        }
        return ItemValuation(Quantity.ofScaledUnits(quantity), Money.of(value), Money.of(average))
    }

    /** Stock positions for [items] (inventory items only), from all of the firm's movements. */
    fun summarize(items: List<Item>, movements: List<StockMovement>): List<ItemStock> {
        val byItem = movements.groupBy { it.itemId }
        return items.filter { it.trackInventory && !it.isService }.map { item ->
            val own = byItem[item.id].orEmpty()
            ItemStock(
                item = item,
                byGodown = own.groupBy { it.godownId }
                    .mapValues { (_, list) -> Quantity.ofScaledUnits(list.sumOf { it.quantity.scaledUnits }) },
                valuation = value(own),
            )
        }
    }
}
