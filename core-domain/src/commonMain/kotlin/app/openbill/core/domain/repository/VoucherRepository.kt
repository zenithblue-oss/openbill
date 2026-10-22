package app.openbill.core.domain.repository

import app.openbill.core.domain.model.Voucher
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate

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
}
