package app.openbill.core.domain.model

import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

data class StockMovement(
    val id: String,
    val firmId: String,
    val itemId: String,
    val godownId: String,
    val batchId: String? = null,
    val voucherId: String,
    val voucherLineId: String? = null,
    val date: LocalDate,
    val quantity: Quantity,
    val createdAt: Instant,
    /** Cost per whole unit of an inward movement; null means "at the running average cost". */
    val unitCost: Money? = null,
) {
    init {
        require(id.isNotBlank()) { "StockMovement id cannot be blank" }
        require(firmId.isNotBlank()) { "StockMovement firmId cannot be blank" }
        require(itemId.isNotBlank()) { "StockMovement itemId cannot be blank" }
        require(godownId.isNotBlank()) { "StockMovement godownId cannot be blank" }
        require(voucherId.isNotBlank()) { "StockMovement voucherId cannot be blank" }
        require(unitCost == null || unitCost >= Money.ZERO) { "StockMovement unitCost cannot be negative" }
    }
}

/** A batch (lot) of an item in one godown. */
data class Batch(
    val id: String,
    val itemId: String,
    val godownId: String,
    val batchNumber: String,
    val expiryDate: LocalDate? = null,
)

enum class SerialStatus {
    IN_STOCK,
    OUT,
}

/** A serial/IMEI number of a serial-tracked item and whether it is currently in stock. */
data class SerialNumber(
    val id: String,
    val itemId: String,
    val serialNumber: String,
    val status: SerialStatus,
    val voucherLineId: String? = null,
)
