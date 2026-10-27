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

/** Per-godown balances and the item-wise valuation of one item, without the item. */
data class StockPosition(
    val byGodown: Map<String, Quantity>,
    val valuation: ItemValuation,
)

/**
 * One item's stock over a report period: quantities before, into and out of the period, the
 * valuation through its end, and the value just before it starts (P&L opening stock).
 */
data class StockPeriodTotals(
    val opening: Quantity,
    val inward: Quantity,
    val outward: Quantity,
    val closing: ItemValuation,
    val valueBeforeFrom: Money,
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

    fun value(movements: List<StockMovement>): ItemValuation = walk(movements) { _, _ -> }

    /**
     * Value change of each movement (paise, signed) under the same costing walk; an outward
     * movement's negated value is its cost of goods. Keys are movement ids.
     */
    fun movementValues(movements: List<StockMovement>): Map<String, Money> {
        val out = HashMap<String, Money>()
        walk(movements) { m, delta -> out[m.id] = Money.of(delta) }
        return out
    }

    private inline fun walk(movements: List<StockMovement>, onEach: (StockMovement, Long) -> Unit): ItemValuation {
        val walker = Walker()
        val ordered = movements.sortedWith(compareBy<StockMovement> { it.date }.thenBy { it.createdAt }.thenBy { it.id })
        for (m in ordered) onEach(m, walker.add(m.quantity.scaledUnits, m.unitCost?.paise))
        return walker.result()
    }

    /**
     * The costing walk of one item, fed one movement at a time in movement order: scaled
     * quantity and unit cost in paise (null = at the running average). Lets the data layer
     * value millions of movements from a cursor without loading them.
     */
    class Walker {
        private var quantity = 0L
        private var value = 0L
        private var average = 0L
        val valuePaise: Long get() = value

        /** Applies one movement; returns its value change in paise. */
        fun add(scaledQuantity: Long, unitCostPaise: Long?): Long {
            val before = value
            value += Rounding.multiplyDivide(scaledQuantity, unitCostPaise ?: average, UNIT, RoundingMode.HALF_UP)
            quantity += scaledQuantity
            if (quantity <= 0L) {
                value = 0L
            } else {
                average = Rounding.multiplyDivide(value, UNIT, quantity, RoundingMode.HALF_UP)
            }
            return value - before
        }

        fun result() = ItemValuation(Quantity.ofScaledUnits(quantity), Money.of(value), Money.of(average))
    }

    /** [StockPeriodTotals] of one item's movements for [from]..[to]. */
    fun periodTotals(movements: List<StockMovement>, from: kotlinx.datetime.LocalDate, to: kotlinx.datetime.LocalDate): StockPeriodTotals {
        val acc = PeriodWalk(from, to)
        movements.sortedWith(compareBy<StockMovement> { it.date }.thenBy { it.createdAt }.thenBy { it.id })
            .forEach { acc.add(it.date, it.quantity.scaledUnits, it.unitCost?.paise) }
        return acc.result()
    }

    /** [periodTotals] fed one movement at a time in movement order (cursor streaming). */
    class PeriodWalk(private val from: kotlinx.datetime.LocalDate, private val to: kotlinx.datetime.LocalDate) {
        private val walker = Walker()
        private var opening = 0L
        private var inward = 0L
        private var outward = 0L
        private var before = 0L

        fun add(date: kotlinx.datetime.LocalDate, scaledQuantity: Long, unitCostPaise: Long?) {
            if (date <= to) add(date < from, scaledQuantity, unitCostPaise)
        }

        /** [add] for a movement already known to be dated through `to`; [beforeFrom] = dated before `from`. */
        fun add(beforeFrom: Boolean, scaledQuantity: Long, unitCostPaise: Long?) {
            if (beforeFrom) opening += scaledQuantity else if (scaledQuantity > 0) inward += scaledQuantity else outward -= scaledQuantity
            walker.add(scaledQuantity, unitCostPaise)
            if (beforeFrom) before = walker.valuePaise
        }

        fun result() = StockPeriodTotals(
            Quantity.ofScaledUnits(opening), Quantity.ofScaledUnits(inward), Quantity.ofScaledUnits(outward), walker.result(), Money.of(before),
        )
    }

    /** One item's per-godown balances and valuation from all its movements. */
    fun position(movements: List<StockMovement>): StockPosition = StockPosition(
        byGodown = movements.groupBy { it.godownId }.mapValues { (_, list) -> Quantity.ofScaledUnits(list.sumOf { it.quantity.scaledUnits }) },
        valuation = value(movements),
    )

    /** Stock positions for [items] (inventory items only), from all of the firm's movements. */
    fun summarize(items: List<Item>, movements: List<StockMovement>): List<ItemStock> =
        summarize(items, movements.groupBy { it.itemId }.mapValues { (_, own) -> position(own) })

    /** Stock positions for [items] (inventory items only) from per-item [positions] (absent = no movements). */
    fun summarize(items: List<Item>, positions: Map<String, StockPosition>): List<ItemStock> =
        items.filter { it.trackInventory && !it.isService }.map { item ->
            val p = positions[item.id] ?: position(emptyList())
            ItemStock(item = item, byGodown = p.byGodown, valuation = p.valuation)
        }
}
