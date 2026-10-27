package app.openbill.core.domain.repository

import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherTally
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.model.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

interface VoucherRepository {
    fun observeVouchers(
        firmId: String,
        fromDate: LocalDate,
        toDate: LocalDate,
    ): Flow<List<Voucher>>

    /**
     * The newest [limit] non-deleted vouchers of [types], optionally of [status], whose number,
     * reference or party name contains [query]. Headers only: lines and charges are empty, so
     * open a row with [findVoucher]. Lists grow [limit] as they scroll.
     */
    fun observeVoucherPage(
        firmId: String,
        types: Set<VoucherType>,
        status: VoucherStatus?,
        query: String,
        limit: Int,
    ): Flow<List<Voucher>> = observeVouchers(firmId, LocalDate(1900, 1, 1), LocalDate(9999, 12, 31)).map { all ->
        val q = query.trim()
        all.filter {
            it.deletedAt == null && it.type in types && (status == null || it.status == status) &&
                (q.isEmpty() || it.number?.contains(q, true) == true || it.referenceNumber?.contains(q, true) == true)
        }.take(limit)
    }

    /** Count and total by status of non-deleted vouchers of [types] dated [fromDate]..[toDate]. */
    fun observeVoucherStats(
        firmId: String,
        types: Set<VoucherType>,
        fromDate: LocalDate,
        toDate: LocalDate,
    ): Flow<Map<VoucherStatus, VoucherTally>> = observeVouchers(firmId, fromDate, toDate).map { all ->
        all.filter { it.deletedAt == null && it.type in types }.groupBy { it.status }
            .mapValues { (_, l) -> VoucherTally(l.size, l.fold(Money.ZERO) { a, v -> a + v.total }) }
    }

    fun observeVoucher(id: String): Flow<Voucher?>

    suspend fun findVoucher(id: String): Voucher?

    suspend fun saveDraft(voucher: Voucher)

    suspend fun deleteDraft(id: String)

    suspend fun post(voucherId: String, at: Instant): Voucher

    suspend fun cancel(voucherId: String, reason: String, at: Instant): Voucher

    suspend fun restoreCancelled(
        voucherId: String,
        cancellationReason: String,
        restoreReason: String,
        at: Instant,
    ): Voucher

    suspend fun softDeleteDraft(id: String, at: Instant): Voucher

    suspend fun restoreDraft(id: String, at: Instant): Voucher

    suspend fun purgeDraft(id: String)

    fun observeRecycleBin(firmId: String): Flow<List<Voucher>>
}
