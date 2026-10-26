package app.openbill.core.domain.repository

import app.openbill.core.domain.model.Batch
import app.openbill.core.domain.model.SerialNumber
import app.openbill.core.domain.model.StockMovement
import app.openbill.core.model.Quantity
import kotlinx.coroutines.flow.Flow

interface StockRepository {
    fun observeBalance(firmId: String, itemId: String, godownId: String): Flow<Quantity>

    suspend fun balance(firmId: String, itemId: String, godownId: String): Quantity

    fun observeMovements(voucherId: String): Flow<List<StockMovement>>

    /** Every movement of the firm (stock summary, valuation, low-stock alerts). */
    fun observeFirmMovements(firmId: String): Flow<List<StockMovement>>

    /** One item's movements, newest first (the item timeline). */
    fun observeItemMovements(firmId: String, itemId: String): Flow<List<StockMovement>>

    fun observeBatches(itemId: String): Flow<List<Batch>>

    fun observeSerials(itemId: String): Flow<List<SerialNumber>>
}
