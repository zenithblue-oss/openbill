package app.openbill.core.domain.usecase

import app.openbill.core.domain.model.PaymentDetails
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.isPayment
import app.openbill.core.domain.model.isPaymentAccount
import app.openbill.core.domain.repository.LedgerRepository
import app.openbill.core.domain.repository.PaymentRepository
import app.openbill.core.domain.repository.VoucherRepository

class SavePaymentDetails(
    private val vouchers: VoucherRepository,
    private val payments: PaymentRepository,
    private val ledger: LedgerRepository,
) {
    suspend operator fun invoke(details: PaymentDetails): PaymentDetails {
        val voucher = vouchers.findVoucher(details.voucherId)
            ?: throw IllegalArgumentException("Unknown voucher: ${details.voucherId}")
        require(voucher.deletedAt == null) { "Deleted vouchers cannot have payment details" }
        require(voucher.firmId == details.firmId) {
            "Payment details belong to a different firm"
        }
        require(voucher.type.isPayment()) { "Voucher is not a payment" }
        require(voucher.status == VoucherStatus.DRAFT) {
            "Payment details can only be saved for drafts"
        }
        val account = ledger.findAccountById(details.accountId)
            ?: throw IllegalArgumentException("Unknown account: ${details.accountId}")
        require(account.firmId == voucher.firmId) {
            "Payment account belongs to a different firm"
        }
        require(account.deletedAt == null) { "Payment account is deleted" }
        require(account.type.isPaymentAccount) {
            "Payment account must be a cash, bank, or asset account"
        }
        val normalized = details.normalized()
        payments.savePaymentDetails(normalized)
        return normalized
    }
}
