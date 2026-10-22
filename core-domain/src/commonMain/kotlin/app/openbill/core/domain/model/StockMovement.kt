package app.openbill.core.domain.model

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
) {
    init {
        require(id.isNotBlank()) { "StockMovement id cannot be blank" }
        require(firmId.isNotBlank()) { "StockMovement firmId cannot be blank" }
        require(itemId.isNotBlank()) { "StockMovement itemId cannot be blank" }
        require(godownId.isNotBlank()) { "StockMovement godownId cannot be blank" }
        require(voucherId.isNotBlank()) { "StockMovement voucherId cannot be blank" }
    }
}
