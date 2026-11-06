package app.openbill.core.domain.model

import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import app.openbill.core.model.Rounding
import app.openbill.core.model.RoundingMode

fun VoucherType.isOrder(): Boolean = this == VoucherType.SALES_ORDER || this == VoucherType.PURCHASE_ORDER

/** What an order converts into: an invoice for a sales order, a purchase bill for a purchase order. */
fun VoucherType.orderTarget(): VoucherType? = when (this) {
    VoucherType.SALES_ORDER -> VoucherType.SALE
    VoucherType.PURCHASE_ORDER -> VoucherType.PURCHASE
    else -> null
}

/** Order status as users see it. */
enum class OrderStatus(val label: String) {
    DRAFT("Draft"),
    OPEN("Open"),
    PARTIAL("Partially fulfilled"),
    CLOSED("Closed"),
    CANCELLED("Cancelled"),
}

/**
 * Partial conversion of sales/purchase orders. Each invoice or bill line made from an order
 * line carries [VoucherLine.sourceLineId]; posted ones count as delivered.
 */
object Orders {
    fun status(order: Voucher): OrderStatus = when (order.status) {
        VoucherStatus.DRAFT -> OrderStatus.DRAFT
        VoucherStatus.CANCELLED -> OrderStatus.CANCELLED
        VoucherStatus.POSTED -> when (order.fulfilment) {
            OrderFulfilment.NONE -> OrderStatus.OPEN
            OrderFulfilment.PARTIAL -> OrderStatus.PARTIAL
            OrderFulfilment.COMPLETE -> OrderStatus.CLOSED
        }
    }

    /** Billed quantity per order line id over [conversions] that count (posted, or drafts too). */
    fun converted(conversions: List<Voucher>, includeDrafts: Boolean = false): Map<String, Quantity> =
        conversions.asSequence()
            .filter { it.deletedAt == null && (it.status == VoucherStatus.POSTED || (includeDrafts && it.status == VoucherStatus.DRAFT)) }
            .flatMap { it.lines.asSequence() }
            .filter { it.sourceLineId != null }
            .groupBy { it.sourceLineId!! }
            .mapValues { (_, l) -> Quantity.ofScaledUnits(l.sumOf { it.quantity.scaledUnits }) }

    /** Quantity still to convert per order line (never negative); drafts reserve what they hold when [includeDrafts]. */
    fun pending(order: Voucher, conversions: List<Voucher>, includeDrafts: Boolean = false): Map<String, Quantity> {
        val done = converted(conversions, includeDrafts)
        return order.lines.associate { l ->
            l.id to Quantity.ofScaledUnits(maxOf(0L, l.quantity.scaledUnits - (done[l.id]?.scaledUnits ?: 0L)))
        }
    }

    /** Fulfilment from posted conversions: none, some, or every line fully billed. */
    fun fulfilment(order: Voucher, conversions: List<Voucher>): OrderFulfilment {
        val done = converted(conversions)
        if (order.lines.isEmpty() || done.values.all { it.scaledUnits == 0L }) return OrderFulfilment.NONE
        val complete = order.lines.all { l -> (done[l.id]?.scaledUnits ?: 0L) >= l.quantity.scaledUnits }
        return if (complete) OrderFulfilment.COMPLETE else OrderFulfilment.PARTIAL
    }

    /**
     * A draft invoice/bill for what is still pending on [order]: lines keep their price and tax,
     * take the pending quantity, and link back with [VoucherLine.sourceLineId]. Fully converted
     * lines are left out. Null when nothing is pending.
     */
    fun conversionLines(order: Voucher, conversions: List<Voucher>, newId: () -> String): List<VoucherLine>? {
        val pending = pending(order, conversions, includeDrafts = true)
        val lines = order.lines.sortedBy { it.lineNumber }.filter { (pending[it.id]?.scaledUnits ?: 0L) > 0L }
            .mapIndexed { i, l ->
                val qty = pending.getValue(l.id)
                val whole = qty == l.quantity
                l.copy(
                    id = newId(), lineNumber = i + 1, quantity = qty, sourceLineId = l.id,
                    batchId = null, serialNumbers = emptyList(),
                    // A part of the line takes its share of the discount; free units carry over only when the whole line is billed at once (add them by hand otherwise).
                    discount = if (whole) l.discount else Money.of(Rounding.multiplyDivide(l.discount.paise, qty.scaledUnits, l.quantity.scaledUnits, RoundingMode.HALF_UP)),
                    foreignDiscount = l.foreignDiscount?.let { d -> if (whole) d else Money.of(Rounding.multiplyDivide(d.paise, qty.scaledUnits, l.quantity.scaledUnits, RoundingMode.HALF_UP)) },
                    freeQuantity = if (whole) l.freeQuantity else Quantity.of(0),
                )
            }
        return lines.ifEmpty { null }
    }
}
