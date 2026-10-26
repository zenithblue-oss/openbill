package app.openbill.core.domain.usecase

import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.model.isPayment
import app.openbill.core.domain.model.postsLedger
import app.openbill.core.domain.repository.VoucherRepository
import app.openbill.core.model.Money
import kotlin.time.Instant

class PostVoucher(private val vouchers: VoucherRepository) {
    suspend operator fun invoke(voucherId: String, at: Instant): Voucher {
        val voucher = vouchers.findVoucher(voucherId)
            ?: throw IllegalArgumentException("Unknown voucher: $voucherId")
        require(voucher.deletedAt == null) { "Deleted vouchers cannot be posted" }
        require(voucher.status == VoucherStatus.DRAFT) { "Only drafts can be posted" }
        require(voucher.number == null) { "Voucher already has a number" }
        require(voucher.type !in CONVERTIBLE_TYPES) {
            "Quotation and proforma vouchers must be converted before posting"
        }
        require(voucher.lines.isNotEmpty() || voucher.type.isPayment()) {
            "Voucher must have at least one line"
        }
        require(voucher.placeOfSupplyStateCode.length == 2) {
            "Voucher place of supply state code must have two characters"
        }
        if (voucher.type !in STOCK_ONLY_TYPES) {
            require(voucher.total > Money.ZERO) { "Voucher total must be positive" }
        }
        if (voucher.type.postsLedger()) {
            require(voucher.partyId != null) { "Voucher must have a party to post to the ledger" }
        }
        if (voucher.type.isPayment()) {
            require(voucher.taxable == Money.ZERO && voucher.tax == Money.ZERO) {
                "Payment vouchers cannot have taxable value or tax"
            }
            require(voucher.roundOff == Money.ZERO) {
                "Payment vouchers cannot have round-off"
            }
        }
        return vouchers.post(voucherId, at)
    }

    private companion object {
        val CONVERTIBLE_TYPES = setOf(VoucherType.QUOTATION, VoucherType.PROFORMA)
        val STOCK_ONLY_TYPES = setOf(VoucherType.STOCK_ADJUSTMENT, VoucherType.STOCK_TRANSFER)
    }
}
