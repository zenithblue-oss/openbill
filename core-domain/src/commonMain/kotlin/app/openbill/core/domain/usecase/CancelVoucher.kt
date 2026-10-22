package app.openbill.core.domain.usecase

import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.repository.VoucherRepository
import kotlin.time.Instant

class CancelVoucher(private val vouchers: VoucherRepository) {
    suspend operator fun invoke(voucherId: String, reason: String, at: Instant): Voucher {
        val trimmedReason = reason.trim()
        require(trimmedReason.isNotEmpty()) { "Cancellation reason cannot be blank" }
        val voucher = vouchers.findVoucher(voucherId)
            ?: throw IllegalArgumentException("Unknown voucher: $voucherId")
        require(voucher.status == VoucherStatus.POSTED) { "Only posted vouchers can be cancelled" }
        return vouchers.cancel(voucherId, trimmedReason, at)
    }
}
