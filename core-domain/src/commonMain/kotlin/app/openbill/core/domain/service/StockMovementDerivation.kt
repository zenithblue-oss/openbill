package app.openbill.core.domain.service

import app.openbill.core.domain.model.FirmSettings
import app.openbill.core.domain.model.Item
import app.openbill.core.domain.model.StockDirection
import app.openbill.core.domain.model.StockMovement
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherLine
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import app.openbill.core.model.Rounding
import app.openbill.core.model.RoundingMode
import app.openbill.core.model.Ulid
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

class StockMovementDerivation(
    private val movementId: (Instant) -> String = {
        Ulid.create(it.toEpochMilliseconds()).toString()
    },
) {
    /**
     * Derives the posted voucher's movements. A transfer line yields an outward movement from
     * its godown and an inward one into [VoucherLine.toGodownId]. [batchIdFor] resolves a
     * line's batch row for a godown (the data layer creates batch rows on demand).
     */
    fun derivePosted(
        voucher: Voucher,
        items: Map<String, Item>,
        settings: FirmSettings,
        at: Instant,
        batchIdFor: (line: VoucherLine, godownId: String) -> String? = { line, _ -> line.batchId },
    ): List<StockMovement> {
        InventoryRules.validate(voucher, items)
        return voucher.lines.flatMap { line ->
            val itemId = line.itemId ?: return@flatMap emptyList()
            val item = items[itemId]
                ?: throw IllegalArgumentException("Unknown item: $itemId")
            if (item.isService || !item.trackInventory) return@flatMap emptyList()
            if (line.quantity == Quantity.of(0)) return@flatMap emptyList()
            val direction = directionOf(voucher.type, line) ?: return@flatMap emptyList()
            val godownId = line.godownId ?: settings.defaultGodownId
                ?: throw IllegalArgumentException(
                    "Missing godown for inventory item $itemId on line ${line.id}",
                )

            fun movement(godown: String, inward: Boolean, cost: Money?) = StockMovement(
                id = movementId(at),
                firmId = voucher.firmId,
                itemId = itemId,
                godownId = godown,
                batchId = batchIdFor(line, godown),
                voucherId = voucher.id,
                voucherLineId = line.id,
                date = voucher.date,
                quantity = if (inward) line.quantity else Quantity.ofScaledUnits(-line.quantity.scaledUnits),
                createdAt = at,
                unitCost = cost,
            )
            when (direction) {
                LineDirection.INWARD -> listOf(movement(godownId, true, inwardCost(voucher.type, line)))
                LineDirection.OUTWARD -> listOf(movement(godownId, false, null))
                LineDirection.TRANSFER -> listOf(
                    movement(godownId, false, null),
                    movement(line.toGodownId!!, true, null),
                )
            }
        }
    }

    fun deriveCancellation(
        voucher: Voucher,
        originalMovements: List<StockMovement>,
        at: Instant,
    ): List<StockMovement> {
        val cancellationDate = at.toLocalDateTime(TimeZone.UTC).date
        return originalMovements
            .filter { it.voucherId == voucher.id }
            .map { movement ->
                movement.copy(
                    id = movementId(at),
                    date = cancellationDate,
                    quantity = Quantity.ofScaledUnits(-movement.quantity.scaledUnits),
                    createdAt = at,
                )
            }
    }

    enum class LineDirection { INWARD, OUTWARD, TRANSFER }

    companion object {
        /** How a line of [type] moves stock, or null when the type never touches stock. */
        fun directionOf(type: VoucherType, line: VoucherLine): LineDirection? = when (type) {
            VoucherType.SALE, VoucherType.DEBIT_NOTE -> LineDirection.OUTWARD
            VoucherType.PURCHASE, VoucherType.CREDIT_NOTE -> LineDirection.INWARD
            VoucherType.STOCK_ADJUSTMENT -> when (
                line.stockDirection ?: throw IllegalArgumentException(
                    "Stock adjustment line ${line.id} must have a stock direction",
                )
            ) {
                StockDirection.INWARD -> LineDirection.INWARD
                StockDirection.OUTWARD -> LineDirection.OUTWARD
            }
            VoucherType.STOCK_TRANSFER -> LineDirection.TRANSFER
            else -> null
        }

        /**
         * Cost of an inward unit: a purchase's taxable value per unit (GST is input credit, not
         * cost); an adjustment's or opening stock's rate. Returns (credit notes) are valued at
         * the running average.
         */
        fun inwardCost(type: VoucherType, line: VoucherLine): Money? = when (type) {
            VoucherType.PURCHASE -> Money.of(
                Rounding.multiplyDivide(line.taxable.paise, 10_000L, line.quantity.scaledUnits, RoundingMode.HALF_UP),
            )
            VoucherType.STOCK_ADJUSTMENT -> line.rate.takeIf { it > Money.ZERO }
            else -> null
        }
    }
}
