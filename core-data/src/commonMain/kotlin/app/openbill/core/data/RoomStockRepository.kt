package app.openbill.core.data

import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.domain.model.Batch
import app.openbill.core.domain.model.SerialNumber
import app.openbill.core.domain.model.StockMovement
import app.openbill.core.domain.repository.StockRepository
import app.openbill.core.model.Quantity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import androidx.room.useReaderConnection
import app.openbill.core.domain.service.StockPosition
import app.openbill.core.domain.service.StockPeriodTotals
import kotlinx.datetime.LocalDate
import app.openbill.core.model.OnProgress
import app.openbill.core.model.Progress
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import app.openbill.core.domain.service.StockValuation

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

    /**
     * Streams the firm's movements from a cursor in costing order, one walker per item, so
     * millions of movements are valued without holding them (docs/14-performance.md).
     */
    override fun observeStockPositions(firmId: String): Flow<Map<String, StockPosition>> =
        database.invalidationTracker.createFlow("stock_movements").map { stockPositions(firmId) }.flowOn(Dispatchers.Default)

    private suspend fun stockPositions(firmId: String): Map<String, StockPosition> = database.useReaderConnection { c ->
        c.usePrepared(
            "SELECT itemId, godownId, quantity, unitCost FROM stock_movements WHERE firmId = ? " +
                "ORDER BY itemId, date, createdAt, id",
        ) { st ->
            st.bindText(1, firmId)
            val out = HashMap<String, StockPosition>()
            var item: String? = null
            var walker = StockValuation.Walker()
            var godowns = HashMap<String, Long>()
            fun flush() {
                item?.let { out[it] = StockPosition(godowns.mapValues { (_, q) -> Quantity.ofScaledUnits(q) }, walker.result()) }
            }
            while (st.step()) {
                val id = st.getText(0)
                if (id != item) {
                    flush()
                    item = id
                    walker = StockValuation.Walker()
                    godowns = HashMap()
                }
                val q = st.getLong(2)
                godowns[st.getText(1)] = (godowns[st.getText(1)] ?: 0L) + q
                walker.add(q, if (st.isNull(3)) null else st.getLong(3))
            }
            flush()
            out
        }
    }

    override suspend fun stockPeriodTotals(firmId: String, from: LocalDate, to: LocalDate, onProgress: OnProgress): Map<String, StockPeriodTotals> =
        database.useReaderConnection { c ->
            val job = currentCoroutineContext()
            val total = c.usePrepared("SELECT COUNT(*) FROM stock_movements WHERE firmId = ? AND date <= ?") { st ->
                st.bindText(1, firmId)
                st.bindText(2, to.toString())
                st.step()
                st.getLong(0)
            }
            onProgress(Progress(STOCK_STAGE, 0, total))
            c.usePrepared(
                "SELECT itemId, date < ?, quantity, unitCost FROM stock_movements WHERE firmId = ? AND date <= ? " +
                    "ORDER BY itemId, date, createdAt, id",
            ) { st ->
                st.bindText(1, from.toString())
                st.bindText(2, firmId)
                st.bindText(3, to.toString())
                val out = HashMap<String, StockPeriodTotals>()
                var item: String? = null
                var walk = StockValuation.PeriodWalk(from, to)
                var rows = 0L
                while (st.step()) {
                    val id = st.getText(0)
                    if (id != item) {
                        item?.let { out[it] = walk.result() }
                        item = id
                        walk = StockValuation.PeriodWalk(from, to)
                    }
                    walk.add(st.getLong(1) != 0L, st.getLong(2), if (st.isNull(3)) null else st.getLong(3))
                    if (++rows % PROGRESS_ROWS == 0L) {
                        job.ensureActive()
                        onProgress(Progress(STOCK_STAGE, rows, total))
                    }
                }
                item?.let { out[it] = walk.result() }
                onProgress(Progress(STOCK_STAGE, total, total))
                out
            }
        }

    override fun observeItemMovements(firmId: String, itemId: String): Flow<List<StockMovement>> =
        movements.observeForItem(firmId, itemId).map { values -> values.map { it.toDomain() } }

    override fun observeBatches(itemId: String): Flow<List<Batch>> =
        database.batchDao().observeForItem(itemId).map { values -> values.map { it.toDomain() } }

    override fun observeSerials(itemId: String): Flow<List<SerialNumber>> =
        database.serialNumberDao().observeForItem(itemId).map { values -> values.map { it.toDomain() } }
}

private const val STOCK_STAGE = "Valuing stock movements"
private const val PROGRESS_ROWS = 50_000L
