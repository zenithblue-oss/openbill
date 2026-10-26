package app.openbill.core.domain.usecase

import app.openbill.core.domain.model.AllocationRequest
import app.openbill.core.domain.model.OutstandingInvoice
import app.openbill.core.domain.model.PaymentDetails
import app.openbill.core.domain.model.PaymentMethod
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.model.isPayment
import app.openbill.core.domain.model.usesPaymentAccount
import app.openbill.core.domain.repository.LedgerRepository
import app.openbill.core.domain.repository.PaymentRepository
import app.openbill.core.domain.repository.VoucherRepository
import app.openbill.core.model.Money
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

/** A payment in/out, expense or contra to record and post in one step. */
data class MoneyVoucherInput(
    val type: VoucherType,
    val id: String,
    val firmId: String,
    val financialYearId: String,
    val stateCode: String,
    val date: LocalDate,
    /** Gross amount paid or received. */
    val amount: Money,
    /** Cash/bank account money leaves (payment out, expense, contra source) or enters (payment in). */
    val accountId: String,
    val method: PaymentMethod = PaymentMethod.CASH,
    val partyId: String? = null,
    /** Expense category or contra destination. */
    val ledgerAccountId: String? = null,
    /** Input GST claimed on an expense (part of [amount]). */
    val inputTax: Money = Money.ZERO,
    val instrumentNumber: String? = null,
    val instrumentDate: LocalDate? = null,
    val reference: String? = null,
    val notes: String? = null,
    /** Payments only; the unallocated remainder stays on account as an advance. */
    val allocations: List<AllocationRequest> = emptyList(),
)

class MoneyVouchers(
    private val vouchers: VoucherRepository,
    private val payments: PaymentRepository,
    private val ledger: LedgerRepository,
    private val post: PostVoucher = PostVoucher(vouchers),
) {
    suspend fun record(input: MoneyVoucherInput, at: Instant): Voucher {
        require(input.type.usesPaymentAccount()) { "${input.type} is not a money voucher" }
        require(input.amount > Money.ZERO) { "Amount must be above zero" }
        require(input.inputTax >= Money.ZERO && input.inputTax < input.amount) { "GST must be below the amount" }
        require(input.type == VoucherType.EXPENSE || input.inputTax == Money.ZERO) { "Only expenses carry GST" }
        if (input.type.isPayment()) require(input.partyId != null) { "Choose a party" }
        require(input.allocations.isEmpty() || input.type.isPayment()) { "Only payments can be allocated" }
        val allocated = input.allocations.fold(Money.ZERO) { t, a -> t + a.amount }
        require(allocated <= input.amount) { "Allocations exceed the payment amount" }
        if (input.allocations.isNotEmpty()) {
            val open = payments.outstandingInvoices(input.firmId, input.partyId!!, invoiceTypeFor(input.type))
                .associateBy { it.voucherId }
            input.allocations.forEach { a ->
                val inv = open[a.invoiceVoucherId] ?: throw IllegalArgumentException("Invoice is not outstanding")
                require(a.amount <= inv.outstanding) { "Allocation exceeds ${inv.number ?: "invoice"} outstanding" }
            }
        }
        val voucher = Voucher(
            id = input.id,
            firmId = input.firmId,
            financialYearId = input.financialYearId,
            type = input.type,
            date = input.date,
            partyId = input.partyId,
            ledgerAccountId = input.ledgerAccountId,
            placeOfSupplyStateCode = input.stateCode,
            taxable = if (input.type == VoucherType.EXPENSE) input.amount - input.inputTax else Money.ZERO,
            tax = input.inputTax,
            subtotal = input.amount,
            total = input.amount,
            notes = input.notes?.trim()?.ifEmpty { null },
            createdAt = at,
            updatedAt = at,
        )
        vouchers.saveDraft(voucher)
        val posted = try {
            SavePaymentDetails(vouchers, payments, ledger)(
                PaymentDetails(
                    voucherId = input.id,
                    firmId = input.firmId,
                    accountId = input.accountId,
                    method = input.method,
                    instrumentNumber = input.instrumentNumber?.trim()?.ifEmpty { null },
                    instrumentDate = input.instrumentDate,
                    reference = input.reference?.trim()?.ifEmpty { null },
                    createdAt = at,
                    updatedAt = at,
                ),
            )
            post(input.id, at)
        } catch (e: Exception) {
            runCatching { vouchers.deleteDraft(input.id) }
            throw e
        }
        if (input.allocations.isNotEmpty()) payments.allocate(input.id, input.allocations, at)
        return posted
    }

    companion object {
        fun invoiceTypeFor(paymentType: VoucherType): VoucherType =
            if (paymentType == VoucherType.PAYMENT_OUT) VoucherType.PURCHASE else VoucherType.SALE

        /** Oldest-first allocation of [amount] across [open] invoices. */
        fun fifo(open: List<OutstandingInvoice>, amount: Money): List<AllocationRequest> {
            var left = amount
            return open.sortedWith(compareBy({ it.date }, { it.voucherId })).mapNotNull { inv ->
                if (left <= Money.ZERO) return@mapNotNull null
                val take = if (inv.outstanding < left) inv.outstanding else left
                left -= take
                AllocationRequest(inv.voucherId, take)
            }
        }
    }
}
