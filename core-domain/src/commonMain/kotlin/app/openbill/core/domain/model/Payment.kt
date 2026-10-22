package app.openbill.core.domain.model

import app.openbill.core.model.Money
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

enum class PaymentMethod {
    CASH,
    BANK,
    UPI,
    CHEQUE,
}

data class PaymentDetails(
    val voucherId: String,
    val firmId: String,
    val accountId: String,
    val method: PaymentMethod,
    val instrumentNumber: String? = null,
    val instrumentDate: LocalDate? = null,
    val bankName: String? = null,
    val branch: String? = null,
    val upiId: String? = null,
    val reference: String? = null,
    val notes: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(voucherId.isNotBlank()) { "PaymentDetails voucherId cannot be blank" }
        require(firmId.isNotBlank()) { "PaymentDetails firmId cannot be blank" }
        require(accountId.isNotBlank()) { "PaymentDetails accountId cannot be blank" }
        require(updatedAt >= createdAt) { "PaymentDetails updatedAt cannot precede createdAt" }
        requireNonBlank(instrumentNumber, "instrumentNumber")
        requireNonBlank(bankName, "bankName")
        requireNonBlank(branch, "branch")
        requireNonBlank(upiId, "upiId")
        requireNonBlank(reference, "reference")
        requireNonBlank(notes, "notes")
        when (method) {
            PaymentMethod.CASH -> {
                require(instrumentNumber == null && instrumentDate == null) {
                    "Cash payments cannot have an instrument"
                }
                require(bankName == null && branch == null && upiId == null) {
                    "Cash payments cannot have bank or UPI details"
                }
            }

            PaymentMethod.BANK -> {
                require(instrumentNumber != null && instrumentDate != null) {
                    "Bank payments require instrument number and date"
                }
                require(upiId == null) { "Bank payments cannot have a UPI id" }
            }

            PaymentMethod.UPI -> {
                require(instrumentNumber != null) {
                    "UPI payments require an instrument number"
                }
            }

            PaymentMethod.CHEQUE -> {
                require(instrumentNumber != null && instrumentDate != null) {
                    "Cheque payments require instrument number and date"
                }
                require(upiId == null) { "Cheque payments cannot have a UPI id" }
            }
        }
    }

    fun normalized(): PaymentDetails = copy(
        instrumentNumber = instrumentNumber?.trim(),
        bankName = bankName?.trim(),
        branch = branch?.trim(),
        upiId = upiId?.trim(),
        reference = reference?.trim(),
        notes = notes?.trim(),
    )

    private fun requireNonBlank(value: String?, name: String) {
        require(value == null || value.isNotBlank()) { "PaymentDetails $name cannot be blank" }
    }
}

data class PaymentAllocation(
    val id: String,
    val firmId: String,
    val paymentVoucherId: String,
    val invoiceVoucherId: String,
    val amount: Money,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(id.isNotBlank()) { "PaymentAllocation id cannot be blank" }
        require(firmId.isNotBlank()) { "PaymentAllocation firmId cannot be blank" }
        require(paymentVoucherId.isNotBlank()) {
            "PaymentAllocation paymentVoucherId cannot be blank"
        }
        require(invoiceVoucherId.isNotBlank()) {
            "PaymentAllocation invoiceVoucherId cannot be blank"
        }
        require(amount > Money.ZERO) { "PaymentAllocation amount must be positive" }
        require(updatedAt >= createdAt) {
            "PaymentAllocation updatedAt cannot precede createdAt"
        }
    }
}

data class AllocationRequest(
    val invoiceVoucherId: String,
    val amount: Money,
) {
    init {
        require(invoiceVoucherId.isNotBlank()) { "AllocationRequest invoiceVoucherId cannot be blank" }
    }
}

data class OutstandingInvoice(
    val voucherId: String,
    val number: String?,
    val date: LocalDate,
    val dueDate: LocalDate?,
    val partyId: String,
    val type: VoucherType,
    val total: Money,
    val allocated: Money,
) {
    init {
        require(voucherId.isNotBlank()) { "OutstandingInvoice voucherId cannot be blank" }
        require(partyId.isNotBlank()) { "OutstandingInvoice partyId cannot be blank" }
        require(total >= Money.ZERO) { "OutstandingInvoice total cannot be negative" }
        require(allocated >= Money.ZERO) { "OutstandingInvoice allocated cannot be negative" }
        require(total > allocated) { "OutstandingInvoice must have a positive outstanding amount" }
    }

    val outstanding: Money
        get() = total - allocated
}
