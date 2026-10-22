package app.openbill.core.domain.service

import app.openbill.core.domain.model.AllocationRequest
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.model.isPayment
import app.openbill.core.model.Money

class PaymentAllocationPolicy {
    fun requireAllocatablePayment(payment: Voucher) {
        require(payment.status == VoucherStatus.POSTED) {
            "Only posted payments can be allocated"
        }
        require(payment.deletedAt == null) { "Deleted payments cannot be allocated" }
        require(payment.type.isPayment()) { "Voucher ${payment.id} is not a payment" }
        require(payment.partyId != null) { "Payment ${payment.id} must have a party" }
        require(payment.total > Money.ZERO) { "Payment total must be positive" }
    }

    fun validateRequests(requests: List<AllocationRequest>): List<AllocationRequest> {
        require(requests.isNotEmpty()) { "At least one allocation is required" }
        require(requests.all { it.amount > Money.ZERO }) {
            "Allocation amounts must be positive"
        }
        require(requests.map { it.invoiceVoucherId }.distinct().size == requests.size) {
            "Allocation invoices must be distinct"
        }
        return requests
    }

    fun requireWithinPayment(payment: Voucher, allocated: Money, requests: List<AllocationRequest>) {
        val requested = requests.fold(Money.ZERO) { total, request -> total + request.amount }
        require(allocated + requested <= payment.total) {
            "Allocations exceed payment ${payment.id} outstanding"
        }
    }

    fun requireInvoice(payment: Voucher, invoice: Voucher) {
        require(invoice.status == VoucherStatus.POSTED) {
            "Invoice ${invoice.id} is not posted"
        }
        require(invoice.deletedAt == null) { "Invoice ${invoice.id} is deleted" }
        require(invoice.firmId == payment.firmId) {
            "Invoice ${invoice.id} belongs to a different firm"
        }
        require(invoice.partyId == payment.partyId) {
            "Invoice ${invoice.id} belongs to a different party"
        }
        val expectedInvoiceType = when (payment.type) {
            VoucherType.PAYMENT_IN -> VoucherType.SALE
            VoucherType.PAYMENT_OUT -> VoucherType.PURCHASE
            else -> throw IllegalArgumentException("Voucher ${payment.id} is not a payment")
        }
        require(invoice.type == expectedInvoiceType) {
            "Invoice ${invoice.id} must be a ${expectedInvoiceType.name.lowercase()} for " +
                "this payment"
        }
    }

    fun requireWithinInvoice(invoice: Voucher, allocated: Money, request: AllocationRequest) {
        require(request.amount <= invoice.total - allocated) {
            "Allocation exceeds invoice ${invoice.id} outstanding"
        }
    }

    fun requireNotAlreadyAllocated(existingInvoiceIds: Set<String>, requests: List<AllocationRequest>) {
        require(requests.none { it.invoiceVoucherId in existingInvoiceIds }) {
            "An invoice can only be allocated once per payment"
        }
    }
}
