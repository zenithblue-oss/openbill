package app.openbill.core.domain.usecase

import app.openbill.core.domain.model.StockDirection
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherLine
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.repository.VoucherRepository
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

/** One line of a stock adjustment or transfer. */
data class StockLineInput(
    val itemId: String,
    val description: String,
    val quantity: Quantity,
    val godownId: String,
    /** Inward or outward for adjustments; ignored for transfers. */
    val direction: StockDirection = StockDirection.INWARD,
    /** Unit cost of an inward adjustment (opening stock); zero means "at average cost". */
    val unitCost: Money = Money.ZERO,
    val toGodownId: String? = null,
    val batchNumber: String? = null,
    val expiryDate: LocalDate? = null,
    val serialNumbers: List<String> = emptyList(),
)

/**
 * Stock changes outside trading documents are vouchers too, so every change is a posted,
 * immutable movement with an audit trail: opening stock and adjustments (with a reason) are
 * STOCK_ADJUSTMENT vouchers, godown moves are STOCK_TRANSFER vouchers.
 */
class StockVouchers(
    private val vouchers: VoucherRepository,
    private val post: PostVoucher = PostVoucher(vouchers),
) {
    suspend fun adjust(
        id: String,
        firmId: String,
        financialYearId: String,
        stateCode: String,
        date: LocalDate,
        reason: String,
        lines: List<StockLineInput>,
        lineId: (Int) -> String,
        at: Instant,
    ): Voucher = postNew(VoucherType.STOCK_ADJUSTMENT, id, firmId, financialYearId, stateCode, date, reason, lines, lineId, at)

    suspend fun transfer(
        id: String,
        firmId: String,
        financialYearId: String,
        stateCode: String,
        date: LocalDate,
        note: String?,
        lines: List<StockLineInput>,
        lineId: (Int) -> String,
        at: Instant,
    ): Voucher = postNew(VoucherType.STOCK_TRANSFER, id, firmId, financialYearId, stateCode, date, note, lines, lineId, at)

    private suspend fun postNew(
        type: VoucherType,
        id: String,
        firmId: String,
        financialYearId: String,
        stateCode: String,
        date: LocalDate,
        notes: String?,
        lines: List<StockLineInput>,
        lineId: (Int) -> String,
        at: Instant,
    ): Voucher {
        require(lines.isNotEmpty()) { "Add at least one item" }
        require(lines.all { it.quantity > Quantity.of(0) }) { "Quantities must be above zero" }
        if (type == VoucherType.STOCK_ADJUSTMENT) require(!notes.isNullOrBlank()) { "Stock adjustment needs a reason" }
        val voucher = Voucher(
            id = id,
            firmId = firmId,
            financialYearId = financialYearId,
            type = type,
            date = date,
            placeOfSupplyStateCode = stateCode,
            lines = lines.mapIndexed { i, l ->
                VoucherLine(
                    id = lineId(i),
                    lineNumber = i + 1,
                    itemId = l.itemId,
                    description = l.description,
                    quantity = l.quantity,
                    rate = l.unitCost,
                    godownId = l.godownId,
                    stockDirection = if (type == VoucherType.STOCK_ADJUSTMENT) l.direction else null,
                    toGodownId = l.toGodownId,
                    batchNumber = l.batchNumber?.trim()?.ifEmpty { null },
                    expiryDate = l.expiryDate,
                    serialNumbers = l.serialNumbers.map { it.trim() }.filter { it.isNotEmpty() },
                )
            },
            notes = notes?.trim()?.ifEmpty { null },
            createdAt = at,
            updatedAt = at,
        )
        vouchers.saveDraft(voucher)
        return try {
            post(id, at)
        } catch (e: IllegalArgumentException) {
            // Never leave a half-made stock voucher behind as a draft.
            runCatching { vouchers.deleteDraft(id) }
            throw e
        }
    }
}
