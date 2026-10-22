package app.openbill.core.domain.fake

import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.repository.VoucherRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDate

class InMemoryVoucherRepository : VoucherRepository {
    private val vouchers = MutableStateFlow<List<Voucher>>(emptyList())

    override fun observeVouchers(
        firmId: String,
        fromDate: LocalDate,
        toDate: LocalDate,
    ): Flow<List<Voucher>> =
        vouchers.map { values ->
            values.filter {
                it.firmId == firmId &&
                    it.deletedAt == null &&
                    it.date >= fromDate &&
                    it.date <= toDate
            }.sortedWith(
                compareByDescending<Voucher> { it.date }
                    .thenByDescending { it.createdAt }
                    .thenBy { it.id },
            )
        }

    override fun observeVoucher(id: String): Flow<Voucher?> =
        vouchers.map { values -> values.firstOrNull { it.id == id } }

    override suspend fun findVoucher(id: String): Voucher? =
        vouchers.value.firstOrNull { it.id == id }

    override suspend fun saveDraft(voucher: Voucher) {
        require(voucher.status == VoucherStatus.DRAFT) { "Only drafts can be saved here" }
        require(voucher.number == null) { "Draft voucher must not have a number" }
        vouchers.update { current -> current.filterNot { it.id == voucher.id } + voucher }
    }

    override suspend fun deleteDraft(id: String) {
        val voucher = vouchers.value.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unknown voucher: $id")
        require(voucher.status == VoucherStatus.DRAFT) { "Only drafts can be deleted here" }
        vouchers.update { current -> current.filterNot { it.id == id } }
    }
}
