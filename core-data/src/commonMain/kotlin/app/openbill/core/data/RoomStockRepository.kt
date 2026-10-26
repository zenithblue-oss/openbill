package app.openbill.core.data

import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.domain.model.Batch
import app.openbill.core.domain.model.SerialNumber
import app.openbill.core.domain.model.StockMovement
import app.openbill.core.domain.repository.StockRepository
import app.openbill.core.model.Quantity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomStockRepository(
    private val database: OpenBillDatabase,
) : StockRepository {
    private val movements get() = database.stockMovementDao()

    override fun observeBalance(
        firmId: String,
        itemId: String,
        godownId: String,
    ): Flow<Quantity> = movements
        .observeBalanceForItemGodown(firmId, itemId, godownId)
        .map { Quantity.ofScaledUnits(it) }

    override suspend fun balance(
        firmId: String,
        itemId: String,
        godownId: String,
    ): Quantity = Quantity.ofScaledUnits(
        movements.balanceForItemGodown(firmId, itemId, godownId),
    )

    override fun observeMovements(voucherId: String): Flow<List<StockMovement>> =
        movements.observeMovementsForVoucher(voucherId)
            .map { values -> values.map { it.toDomain() } }

    override fun observeFirmMovements(firmId: String): Flow<List<StockMovement>> =
        movements.observeForFirm(firmId).map { values -> values.map { it.toDomain() } }

    override fun observeItemMovements(firmId: String, itemId: String): Flow<List<StockMovement>> =
        movements.observeForItem(firmId, itemId).map { values -> values.map { it.toDomain() } }

    override fun observeBatches(itemId: String): Flow<List<Batch>> =
        database.batchDao().observeForItem(itemId).map { values -> values.map { it.toDomain() } }

    override fun observeSerials(itemId: String): Flow<List<SerialNumber>> =
        database.serialNumberDao().observeForItem(itemId).map { values -> values.map { it.toDomain() } }
}
