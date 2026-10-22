package app.openbill.core.domain.repository

import app.openbill.core.domain.model.StockMovement
import app.openbill.core.model.Quantity
import kotlinx.coroutines.flow.Flow

interface StockRepository {
    fun observeBalance(firmId: String, itemId: String, godownId: String): Flow<Quantity>

    suspend fun balance(firmId: String, itemId: String, godownId: String): Quantity

    fun observeMovements(voucherId: String): Flow<List<StockMovement>>
}
