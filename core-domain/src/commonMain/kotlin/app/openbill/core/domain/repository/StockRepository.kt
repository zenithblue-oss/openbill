package app.openbill.core.domain.repository

import app.openbill.core.domain.model.Batch
import app.openbill.core.domain.model.SerialNumber
import app.openbill.core.domain.model.StockMovement
import app.openbill.core.model.Quantity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import app.openbill.core.domain.service.StockPeriodTotals
import app.openbill.core.domain.service.StockPosition
import app.openbill.core.domain.service.StockValuation

interface StockRepository {
    fun observeBalance(firmId: String, itemId: String, godownId: String): Flow<Quantity>

    suspend fun balance(firmId: String, itemId: String, godownId: String): Quantity

    fun observeMovements(voucherId: String): Flow<List<StockMovement>>

    /** Every movement of the firm (stock summary, valuation, low-stock alerts). */
    fun observeFirmMovements(firmId: String): Flow<List<StockMovement>>

    /** Every item's position (per-godown balances, moving-average valuation), keyed by item id. */
    fun observeStockPositions(firmId: String): Flow<Map<String, StockPosition>> =
        observeFirmMovements(firmId).map { all -> all.groupBy { it.itemId }.mapValues { (_, own) -> StockValuation.position(own) } }

    /** Per-item [StockPeriodTotals] for [from]..[to]; the Room version streams instead of loading movements. */
    suspend fun stockPeriodTotals(firmId: String, from: LocalDate, to: LocalDate): Map<String, StockPeriodTotals> =
        observeFirmMovements(firmId).first().groupBy { it.itemId }.mapValues { (_, own) -> StockValuation.periodTotals(own, from, to) }

    /** One item's movements, newest first (the item timeline). */
    fun observeItemMovements(firmId: String, itemId: String): Flow<List<StockMovement>>

    fun observeBatches(itemId: String): Flow<List<Batch>>

    fun observeSerials(itemId: String): Flow<List<SerialNumber>>
}
