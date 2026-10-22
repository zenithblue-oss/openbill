package app.openbill.core.domain.fake

import app.openbill.core.domain.model.StockMovement
import app.openbill.core.domain.repository.StockRepository
import app.openbill.core.model.Quantity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
    ): Flow<Quantity> = movements.map { values ->
        Quantity.ofScaledUnits(
            values
                .filter {
                    it.firmId == firmId && it.itemId == itemId && it.godownId == godownId
                }
                .sumOf { it.quantity.scaledUnits },
        )
    }

    override suspend fun balance(
        firmId: String,
        itemId: String,
        godownId: String,
    ): Quantity = Quantity.ofScaledUnits(
        movements.value
            .filter {
                it.firmId == firmId && it.itemId == itemId && it.godownId == godownId
            }
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

    suspend fun record(movements: List<StockMovement>) = mutex.withLock {
        this.movements.update { current -> current + movements }
    }
}
