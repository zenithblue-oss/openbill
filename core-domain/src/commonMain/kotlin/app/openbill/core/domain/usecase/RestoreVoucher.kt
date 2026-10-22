package app.openbill.core.domain.usecase

import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.repository.VoucherRepository
import kotlin.time.Instant

class RestoreVoucher(private val vouchers: VoucherRepository) {
    suspend operator fun invoke(
        voucherId: String,
        cancellationReason: String,
        restoreReason: String,
        at: Instant,
    ): Voucher {
        require(cancellationReason.isNotBlank()) { "Cancellation reason cannot be blank" }
        require(restoreReason.isNotBlank()) { "Restore reason cannot be blank" }
        val voucher = vouchers.findVoucher(voucherId)
            ?: throw IllegalArgumentException("Unknown voucher: $voucherId")
        require(voucher.deletedAt == null) { "Deleted vouchers cannot be restored" }
        require(voucher.status == VoucherStatus.CANCELLED) {
            "Only cancelled vouchers can be restored"
        }
        return vouchers.restoreCancelled(
            voucherId = voucherId,
            cancellationReason = cancellationReason.trim(),
            restoreReason = restoreReason.trim(),
            at = at,
        )
    }
}
