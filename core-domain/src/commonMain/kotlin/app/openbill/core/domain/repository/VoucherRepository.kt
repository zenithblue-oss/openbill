package app.openbill.core.domain.repository

import app.openbill.core.domain.model.Voucher
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

interface VoucherRepository {
    fun observeVouchers(
        firmId: String,
        fromDate: LocalDate,
        toDate: LocalDate,
    ): Flow<List<Voucher>>

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
