package app.openbill.core.domain.model

import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import app.openbill.core.model.Rate
import app.openbill.core.tax.TaxMode
import app.openbill.core.tax.TaxTreatment
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

data class VoucherLine(
    val id: String,
    val lineNumber: Int,
    val itemId: String? = null,
    val description: String,
    val hsnSac: String? = null,
    val quantity: Quantity,
    val rate: Money,
    val discount: Money = Money.ZERO,
    val taxRate: Rate = Rate.ofBasisPoints(0),
    val cessRate: Rate = Rate.ofBasisPoints(0),
    val taxMode: TaxMode = TaxMode.EXCLUSIVE,
    val treatment: TaxTreatment = TaxTreatment.TAXABLE,
    val taxable: Money = Money.ZERO,
    val tax: Money = Money.ZERO,
    val total: Money = Money.ZERO,
    val godownId: String? = null,
    val batchId: String? = null,
    val stockDirection: StockDirection? = null,
) {
    init {
        require(id.isNotBlank()) { "VoucherLine id cannot be blank" }
        require(lineNumber > 0) { "VoucherLine lineNumber must be positive" }
        require(description.isNotBlank()) { "VoucherLine description cannot be blank" }
        require(quantity >= Quantity.of(0)) { "VoucherLine quantity cannot be negative" }
        require(rate >= Money.ZERO) { "VoucherLine rate cannot be negative" }
        require(discount >= Money.ZERO) { "VoucherLine discount cannot be negative" }
        require(taxable >= Money.ZERO) { "VoucherLine taxable cannot be negative" }
        require(tax >= Money.ZERO) { "VoucherLine tax cannot be negative" }
        require(total >= Money.ZERO) { "VoucherLine total cannot be negative" }
    }
}

data class VoucherCharge(
    val id: String,
    val name: String,
    val amount: Money,
    val taxRate: Rate = Rate.ofBasisPoints(0),
    val cessRate: Rate = Rate.ofBasisPoints(0),
    val taxAmount: Money = Money.ZERO,
    val total: Money = Money.ZERO,
    val sortOrder: Int = 0,
) {
    init {
        require(id.isNotBlank()) { "VoucherCharge id cannot be blank" }
        require(name.isNotBlank()) { "VoucherCharge name cannot be blank" }
        require(amount >= Money.ZERO) { "VoucherCharge amount cannot be negative" }
        require(taxAmount >= Money.ZERO) { "VoucherCharge tax cannot be negative" }
        require(total >= Money.ZERO) { "VoucherCharge total cannot be negative" }
        require(sortOrder >= 0) { "VoucherCharge sortOrder cannot be negative" }
    }
}

data class Voucher(
    val id: String,
    val firmId: String,
    val financialYearId: String,
    val type: VoucherType,
    val number: String? = null,
    val date: LocalDate,
    val dueDate: LocalDate? = null,
    val partyId: String? = null,
    val referenceVoucherId: String? = null,
    val status: VoucherStatus = VoucherStatus.DRAFT,
    val placeOfSupplyStateCode: String,
    val lines: List<VoucherLine> = emptyList(),
    val charges: List<VoucherCharge> = emptyList(),
    val subtotal: Money = Money.ZERO,
    val discount: Money = Money.ZERO,
    val taxable: Money = Money.ZERO,
    val tax: Money = Money.ZERO,
    val roundOff: Money = Money.ZERO,
    val total: Money = Money.ZERO,
    val notes: String? = null,
    val cancellationReason: String? = null,
    val postedAt: Instant? = null,
    val cancelledAt: Instant? = null,
    val restoredAt: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
) {
    init {
        require(id.isNotBlank()) { "Voucher id cannot be blank" }
        require(firmId.isNotBlank()) { "Voucher firmId cannot be blank" }
        require(financialYearId.isNotBlank()) { "Voucher financialYearId cannot be blank" }
        require(placeOfSupplyStateCode.length == 2) {
            "Voucher place of supply state code must have two characters"
        }
        require(lines.map { it.lineNumber }.distinct().size == lines.size) {
            "Voucher line numbers must be unique"
        }
        require(lines.map { it.id }.distinct().size == lines.size) {
            "Voucher line ids must be unique"
        }
        require(charges.map { it.sortOrder }.distinct().size == charges.size) {
            "Voucher charge sort orders must be unique"
        }
        require(charges.map { it.id }.distinct().size == charges.size) {
            "Voucher charge ids must be unique"
        }
        require(dueDate == null || dueDate >= date) {
            "Voucher due date cannot precede the voucher date"
        }
        if (status == VoucherStatus.POSTED) {
            require(number != null) { "Posted voucher must have a number" }
            require(postedAt != null) { "Posted voucher must have postedAt" }
        }
        if (status == VoucherStatus.CANCELLED) {
            require(cancelledAt != null) { "Cancelled voucher must have cancelledAt" }
            require(!cancellationReason.isNullOrBlank()) {
                "Cancelled voucher must have a cancellation reason"
            }
        }
        require(updatedAt >= createdAt) { "Voucher updatedAt cannot precede createdAt" }
    }
}
