package app.openbill.core.domain.service

import app.openbill.core.domain.model.FirmSettings
import app.openbill.core.domain.model.Item
import app.openbill.core.domain.model.StockDirection
import app.openbill.core.domain.model.StockMovement
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.model.Quantity
import app.openbill.core.model.Ulid
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

class StockMovementDerivation(
    private val movementId: (Instant) -> String = {
        Ulid.create(it.toEpochMilliseconds()).toString()
    },
) {
    fun derivePosted(
        voucher: Voucher,
        items: Map<String, Item>,
        settings: FirmSettings,
        at: Instant,
    ): List<StockMovement> = voucher.lines.mapNotNull { line ->
        val itemId = line.itemId ?: return@mapNotNull null
        val item = items[itemId]
            ?: throw IllegalArgumentException("Unknown item: $itemId")
        if (item.isService || !item.trackInventory) return@mapNotNull null

        val direction = when (voucher.type) {
            VoucherType.SALE, VoucherType.DEBIT_NOTE -> StockDirection.OUTWARD
            VoucherType.PURCHASE, VoucherType.CREDIT_NOTE -> StockDirection.INWARD
            VoucherType.STOCK_ADJUSTMENT -> line.stockDirection
                ?: throw IllegalArgumentException(
                    "Stock adjustment line ${line.id} must have a stock direction",
                )

            VoucherType.STOCK_TRANSFER -> return@mapNotNull null
            else -> return@mapNotNull null
        }
        if (line.quantity == Quantity.of(0)) return@mapNotNull null

        val godownId = line.godownId ?: settings.defaultGodownId
            ?: throw IllegalArgumentException(
                "Missing godown for inventory item $itemId on line ${line.id}",
            )
        val signedQuantity = if (direction == StockDirection.INWARD) {
            line.quantity
        } else {
            Quantity.ofScaledUnits(-line.quantity.scaledUnits)
        }

        StockMovement(
            id = movementId(at),
            firmId = voucher.firmId,
            itemId = itemId,
            godownId = godownId,
            batchId = line.batchId,
            voucherId = voucher.id,
            voucherLineId = line.id,
            date = voucher.date,
            quantity = signedQuantity,
            createdAt = at,
        )
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
}
