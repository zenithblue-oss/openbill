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

    override suspend fun stockPeriodTotals(firmId: String, from: LocalDate, to: LocalDate): Map<String, StockPeriodTotals> =
        database.useReaderConnection { c ->
            c.usePrepared(
                "SELECT itemId, date, quantity, unitCost FROM stock_movements WHERE firmId = ? AND date <= ? " +
                    "ORDER BY itemId, date, createdAt, id",
            ) { st ->
                st.bindText(1, firmId)
                st.bindText(2, to.toString())
                val out = HashMap<String, StockPeriodTotals>()
                var item: String? = null
                var walk = StockValuation.PeriodWalk(from, to)
                while (st.step()) {
                    val id = st.getText(0)
                    if (id != item) {
                        item?.let { out[it] = walk.result() }
                        item = id
                        walk = StockValuation.PeriodWalk(from, to)
                    }
                    walk.add(LocalDate.parse(st.getText(1)), st.getLong(2), if (st.isNull(3)) null else st.getLong(3))
                }
                item?.let { out[it] = walk.result() }
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
