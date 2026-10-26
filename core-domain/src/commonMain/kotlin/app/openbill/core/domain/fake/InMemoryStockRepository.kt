package app.openbill.core.domain.fake

import app.openbill.core.domain.model.Batch
import app.openbill.core.domain.model.SerialNumber
import app.openbill.core.domain.model.StockMovement
import app.openbill.core.domain.repository.StockRepository
import app.openbill.core.model.Quantity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryStockRepository(
    initialMovements: List<StockMovement> = emptyList(),
) : StockRepository {
    private val movements = MutableStateFlow(initialMovements)
    private val mutex = Mutex()

    override fun observeBalance(
        firmId: String,
        itemId: String,
        godownId: String,
    ): Flow<Quantity> = movements.map { sum(it, firmId, itemId, godownId) }

    override suspend fun balance(
        firmId: String,
        itemId: String,
        godownId: String,
    ): Quantity = sum(movements.value, firmId, itemId, godownId)

    private fun sum(values: List<StockMovement>, firmId: String, itemId: String, godownId: String) =
        Quantity.ofScaledUnits(
            values
                .filter { it.firmId == firmId && it.itemId == itemId && it.godownId == godownId }
                .sumOf { it.quantity.scaledUnits },
        )

    override fun observeMovements(voucherId: String): Flow<List<StockMovement>> =
        movements.map { values ->
            values.filter { it.voucherId == voucherId }
                .sortedWith(
                    compareBy<StockMovement> { it.date }
                        .thenBy { it.itemId }
                        .thenBy { it.id },
                )
        }

    override fun observeFirmMovements(firmId: String): Flow<List<StockMovement>> =
        movements.map { values -> values.filter { it.firmId == firmId } }

    override fun observeItemMovements(firmId: String, itemId: String): Flow<List<StockMovement>> =
        movements.map { values ->
            values.filter { it.firmId == firmId && it.itemId == itemId }
                .sortedWith(compareByDescending<StockMovement> { it.date }.thenByDescending { it.createdAt })
        }

    override fun observeBatches(itemId: String): Flow<List<Batch>> = flowOf(emptyList())

    override fun observeSerials(itemId: String): Flow<List<SerialNumber>> = flowOf(emptyList())

    suspend fun record(movements: List<StockMovement>) = mutex.withLock {
        this.movements.update { current -> current + movements }
    }
}
