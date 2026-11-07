package app.openbill.core.domain.usecase

import app.openbill.core.domain.model.Account
import app.openbill.core.domain.model.AccountType
import app.openbill.core.domain.model.AllocationRequest
import app.openbill.core.domain.model.EXCHANGE_GAIN_LOSS
import app.openbill.core.domain.model.isExchangeAccount
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
    /**
     * Payments only: settle this foreign-currency invoice in full, posting the difference between
     * its outstanding and [amount] to the exchange account in [discountAccountId] (see
     * [MoneyVouchers.exchangeAccount]). Replaces [allocations] and [discount].
     */
    val exchangeInvoiceId: String? = null,
)

class MoneyVouchers(
    private val vouchers: VoucherRepository,
    private val payments: PaymentRepository,
    private val ledger: LedgerRepository,
    private val post: PostVoucher = PostVoucher(vouchers),
) {
    suspend fun record(request: MoneyVoucherInput, at: Instant): Voucher {
        require(request.type.usesPaymentAccount()) { "${request.type} is not a money voucher" }
        val input = request.exchangeInvoiceId?.let { exchangeSettlement(request, it) } ?: request
        require(input.discount >= Money.ZERO || request.exchangeInvoiceId != null) { "Discount cannot be negative" }
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
            ledgerAccountId = if (input.discount != Money.ZERO) input.discountAccountId else input.ledgerAccountId,
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

    /**
     * Settles [invoiceId] in full: the rupees that moved ([MoneyVoucherInput.amount]) differ from
     * its outstanding because the exchange rate changed. The difference ([exchangeDifference])
     * becomes a signed settlement discount posted to the exchange account.
     */
    private suspend fun exchangeSettlement(input: MoneyVoucherInput, invoiceId: String): MoneyVoucherInput {
        require(input.type.isPayment()) { "Only payments can settle an exchange difference" }
        require(input.discount == Money.ZERO) { "Use either a settlement discount or an exchange difference, not both" }
        require(input.discountAccountId != null) { "Exchange difference needs the exchange gain/loss account" }
        require(input.amount > Money.ZERO) { "Amount must be above zero" }
        val partyId = requireNotNull(input.partyId) { "Choose a party" }
        val invoice = vouchers.findVoucher(invoiceId) ?: throw IllegalArgumentException("Unknown invoice")
        require(invoice.currency != null) { "Only foreign-currency invoices can settle an exchange difference" }
        val open = payments.outstandingInvoices(input.firmId, partyId, invoiceTypeFor(input.type)).firstOrNull { it.voucherId == invoiceId }
            ?: throw IllegalArgumentException("Invoice is not outstanding")
        val difference = exchangeDifference(open.outstanding, input.amount)
        return input.copy(discount = difference, allocations = listOf(AllocationRequest(invoiceId, open.outstanding)))
    }

    companion object {
        const val EXCHANGE_CAP_PERCENT = 10

        /**
         * Outstanding minus the rupees that moved, as a signed settlement discount. On a receipt a
         * positive value is an exchange loss and a negative one a gain; on a payment out it is the
         * other way round. Refuses no difference and one above [EXCHANGE_CAP_PERCENT]% of the
         * outstanding (almost certainly a typing mistake, or a part payment).
         */
        fun exchangeDifference(outstanding: Money, amount: Money): Money {
            val d = outstanding - amount
            require(d != Money.ZERO) { "The amount equals the outstanding; there is no difference to settle" }
            val size = if (d < Money.ZERO) -d else d
            require(size * 100 <= outstanding * EXCHANGE_CAP_PERCENT) {
                "The difference of ${size.format()} is more than $EXCHANGE_CAP_PERCENT% of the outstanding ${outstanding.format()}; check the amount, or record a part payment"
            }
            return d
        }

        /** Whether [difference] (from [exchangeDifference]) is a gain for a payment of [paymentType]. */
        fun isExchangeGain(paymentType: VoucherType, difference: Money): Boolean =
            if (paymentType == VoucherType.PAYMENT_OUT) difference > Money.ZERO else difference < Money.ZERO

        /** The "Exchange gain/loss" income account (Indirect Incomes in Tally); created on first use, by any role. */
        suspend fun exchangeAccount(ledger: LedgerRepository, firmId: String, newId: () -> String, at: Instant): Account {
            val same = ledger.observeAccounts(firmId).first().filter { it.deletedAt == null && it.name.equals(EXCHANGE_GAIN_LOSS, ignoreCase = true) }
            same.firstOrNull { isExchangeAccount(it) }?.let { return it }
            require(same.isEmpty()) { "An account named \"$EXCHANGE_GAIN_LOSS\" already exists with another type; rename it to settle an exchange difference" }
            return Account(newId(), firmId, EXCHANGE_GAIN_LOSS, AccountType.INCOME, createdAt = at, updatedAt = at).also { ledger.createSystemAccount(it) }
        }

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
            return Account(newId(), firmId, name, type, createdAt = at, updatedAt = at).also { ledger.createSystemAccount(it) }
        }

        fun invoiceTypeFor(paymentType: VoucherType): VoucherType =
            if (paymentType == VoucherType.PAYMENT_OUT) VoucherType.PURCHASE else VoucherType.SALE

        /**
         * [splitAcross] with a settlement [discount] carried by the first tender. Allocations above the
         * money already include the discount (₹980 paid + ₹20 discount against ₹1,000); any discount
         * left over settles what [open] still has outstanding, oldest first.
         * With a discount, throws [IllegalArgumentException] when allocations exceed money plus discount
         * or the discount exceeds what is open.
         */
        fun splitWithDiscount(requests: List<AllocationRequest>, open: List<OutstandingInvoice>, amounts: List<Money>, discount: Money): List<List<AllocationRequest>> {
            if (discount == Money.ZERO) return splitAcross(requests, amounts)
            val money = amounts.fold(Money.ZERO) { t, a -> t + a }
            val requested = requests.fold(Money.ZERO) { t, a -> t + a.amount }
            require(requested <= money + discount) { "Allocations are more than the amount and discount" }
            val topUp = discount - minOf(maxOf(requested - money, Money.ZERO), discount)
            val paid = requests.groupBy { it.invoiceVoucherId }.mapValues { (_, r) -> r.fold(Money.ZERO) { t, a -> t + a.amount } }
            val left = open.mapNotNull { inv -> (paid[inv.voucherId] ?: Money.ZERO).takeIf { it < inv.outstanding }?.let { inv.copy(allocated = inv.allocated + it) } }
            require(topUp <= left.fold(Money.ZERO) { t, i -> t + i.outstanding }) { "The discount is more than the balance this payment leaves open" }
            val all = (requests + fifo(left, topUp)).groupBy { it.invoiceVoucherId }
                .map { (id, r) -> AllocationRequest(id, r.fold(Money.ZERO) { t, a -> t + a.amount }) }
            return splitAcross(all, listOf(amounts[0] + discount) + amounts.drop(1))
        }

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
