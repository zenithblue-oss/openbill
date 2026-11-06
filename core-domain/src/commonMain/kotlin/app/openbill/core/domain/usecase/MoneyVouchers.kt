package app.openbill.core.domain.usecase

import app.openbill.core.domain.model.Account
import app.openbill.core.domain.model.AccountType
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
import kotlinx.coroutines.flow.first
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
    /**
     * Payments only: settlement discount allowed (in) or received (out) on top of [amount]; it
     * settles invoices like money but posts to [discountAccountId] (see [MoneyVouchers.discountAccount]).
     * With a discount, [amount] may be zero (the balance is written off).
     */
    val discount: Money = Money.ZERO,
    val discountAccountId: String? = null,
)

class MoneyVouchers(
    private val vouchers: VoucherRepository,
    private val payments: PaymentRepository,
    private val ledger: LedgerRepository,
    private val post: PostVoucher = PostVoucher(vouchers),
) {
    suspend fun record(input: MoneyVoucherInput, at: Instant): Voucher {
        require(input.type.usesPaymentAccount()) { "${input.type} is not a money voucher" }
        require(input.discount >= Money.ZERO) { "Discount cannot be negative" }
        require(input.discount == Money.ZERO || input.type.isPayment()) { "Only payments can carry a settlement discount" }
        require(input.discount == Money.ZERO || input.discountAccountId != null) { "Settlement discount needs a discount account" }
        require(input.amount > Money.ZERO || (input.amount == Money.ZERO && input.discount > Money.ZERO)) { "Amount must be above zero" }
        require(input.inputTax >= Money.ZERO && (input.inputTax == Money.ZERO || input.inputTax < input.amount)) { "GST must be below the amount" }
        require(input.type == VoucherType.EXPENSE || input.inputTax == Money.ZERO) { "Only expenses carry GST" }
        if (input.type.isPayment()) require(input.partyId != null) { "Choose a party" }
        require(input.allocations.isEmpty() || input.type.isPayment()) { "Only payments can be allocated" }
        val settled = input.amount + input.discount
        val allocated = input.allocations.fold(Money.ZERO) { t, a -> t + a.amount }
        require(allocated <= settled) { "Allocations exceed the payment amount" }
        // A discount only settles invoices; it is never an advance.
        require(allocated >= input.discount) { "Allocate the settlement discount to open invoices" }
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
            ledgerAccountId = if (input.discount > Money.ZERO) input.discountAccountId else input.ledgerAccountId,
            placeOfSupplyStateCode = input.stateCode,
            taxable = if (input.type == VoucherType.EXPENSE) input.amount - input.inputTax else Money.ZERO,
            tax = input.inputTax,
            subtotal = settled,
            discount = input.discount,
            total = settled,
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
        const val DISCOUNT_ALLOWED = "Discount allowed"
        const val DISCOUNT_RECEIVED = "Discount received"

        /**
         * The settlement-discount ledger for [paymentType]: "Discount allowed" (expense) on
         * receipts, "Discount received" (income) on payments; created on first use.
         */
        suspend fun discountAccount(ledger: LedgerRepository, firmId: String, paymentType: VoucherType, newId: () -> String, at: Instant): Account {
            val (name, type) = if (paymentType == VoucherType.PAYMENT_OUT) DISCOUNT_RECEIVED to AccountType.INCOME else DISCOUNT_ALLOWED to AccountType.EXPENSE
            val same = ledger.observeAccounts(firmId).first().filter { it.deletedAt == null && it.name.equals(name, ignoreCase = true) }
            same.firstOrNull { it.type == type && it.code == null }?.let { return it }
            require(same.isEmpty()) { "An account named \"$name\" already exists with another type; rename it to record a settlement discount" }
            return Account(newId(), firmId, name, type, createdAt = at, updatedAt = at).also { ledger.saveAccount(it) }
        }

        fun invoiceTypeFor(paymentType: VoucherType): VoucherType =
            if (paymentType == VoucherType.PAYMENT_OUT) VoucherType.PURCHASE else VoucherType.SALE

        /**
         * Split tender: one payment voucher per mode. Hands [requests] to the tenders in order, each
         * taking allocations up to its own amount; returns one allocation list per tender.
         */
        fun splitAcross(requests: List<AllocationRequest>, amounts: List<Money>): List<List<AllocationRequest>> {
            val queue = ArrayDeque(requests)
            return amounts.map { amount ->
                var left = amount
                buildList {
                    while (left > Money.ZERO && queue.isNotEmpty()) {
                        val r = queue.removeFirst()
                        val take = if (r.amount < left) r.amount else left
                        add(AllocationRequest(r.invoiceVoucherId, take))
                        left -= take
                        if (take < r.amount) queue.addFirst(AllocationRequest(r.invoiceVoucherId, r.amount - take))
                    }
                }
            }
        }

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
