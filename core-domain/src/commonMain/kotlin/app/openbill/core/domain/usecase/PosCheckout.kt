package app.openbill.core.domain.usecase

import app.openbill.core.domain.model.AllocationRequest
import app.openbill.core.domain.model.Party
import app.openbill.core.domain.model.PartyType
import app.openbill.core.domain.model.PaymentMethod
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.repository.Repositories
import app.openbill.core.model.Money
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

/** One way the customer pays at the counter: cash, UPI, card or bank, into [accountId]. */
data class PosTender(val method: PaymentMethod, val accountId: String, val amount: Money, val reference: String? = null)

/** How a counter bill is settled: what each tender pays (cash net of change), the change and what goes on credit. */
data class PosSettlement(val applied: List<Money>, val change: Money, val credit: Money) {
    val paid: Money get() = applied.fold(Money.ZERO) { t, a -> t + a }
}

/**
 * Point-of-sale checkout. The bill itself is an ordinary sale invoice (same tax engine, posting,
 * stock and ledger); this only splits its payment across tenders, each recorded as its own
 * payment-in voucher allocated to the invoice, exactly like a split-tender payment.
 */
object PosCheckout {
    /** The walk-in customer counter bills go to when no party is chosen. */
    const val CASH_SALE = "Cash sale"

    fun isCashSale(party: Party?): Boolean = party != null && party.name.equals(CASH_SALE, ignoreCase = true) && party.gstin == null

    /** Existing "Cash sale" customer of the firm, or a new one (unregistered, in the firm's state) to save. */
    fun cashSaleParty(parties: List<Party>, firmId: String, stateCode: String, newId: () -> String, at: Instant): Pair<Party, Boolean> =
        parties.firstOrNull { it.firmId == firmId && it.deletedAt == null && isCashSale(it) }?.let { it to false }
            ?: (Party(id = newId(), firmId = firmId, name = CASH_SALE, type = PartyType.CUSTOMER, stateCode = stateCode, createdAt = at, updatedAt = at) to true)

    /**
     * Splits [total] across [tenders]. Only cash gives change, so card/UPI/bank may not exceed the
     * bill. Paying less than the bill is refused unless [creditAllowed] (a named customer), when
     * the rest stays outstanding on their account.
     */
    fun settle(total: Money, tenders: List<PosTender>, creditAllowed: Boolean): PosSettlement {
        require(total > Money.ZERO) { "The bill total must be above zero" }
        require(tenders.all { it.amount > Money.ZERO }) { "Enter an amount above zero for each payment" }
        tenders.forEach { t ->
            if (t.method != PaymentMethod.CASH) require(!t.reference.isNullOrBlank()) { "Enter the ${t.method.label()} reference" }
        }
        val nonCash = tenders.filter { it.method != PaymentMethod.CASH }.fold(Money.ZERO) { s, t -> s + t.amount }
        require(nonCash <= total) { "Card, UPI and bank payments add up to more than the bill; only cash can be given change" }
        val tendered = tenders.fold(Money.ZERO) { s, t -> s + t.amount }
        if (tendered < total) {
            require(creditAllowed) {
                "Paid ${(tendered).format()} of ${total.format()}. Collect the rest, or choose the customer to put ${(total - tendered).format()} on their account"
            }
        }
        val change = if (tendered > total) tendered - total else Money.ZERO
        // Change comes out of the cash tenders, last one first.
        var left = change
        val applied = tenders.asReversed().map { t ->
            if (t.method != PaymentMethod.CASH || left == Money.ZERO) {
                t.amount
            } else {
                val take = if (t.amount < left) t.amount else left
                left -= take
                t.amount - take
            }
        }.asReversed()
        return PosSettlement(applied, change, if (tendered < total) total - tendered else Money.ZERO)
    }

    /**
     * Records [settlement] against the posted invoice [invoiceId] of [partyId]: one posted
     * payment-in per tender that pays something, all in one transaction.
     */
    suspend fun recordPayments(
        repos: Repositories,
        post: PostVoucher,
        invoiceId: String,
        partyId: String,
        firmId: String,
        financialYearId: String,
        stateCode: String,
        date: LocalDate,
        tenders: List<PosTender>,
        settlement: PosSettlement,
        newId: () -> String,
        at: Instant,
    ): List<String> {
        val ids = ArrayList<String>()
        repos.transaction {
            val money = MoneyVouchers(repos.vouchers, repos.payments, repos.ledger, post)
            tenders.forEachIndexed { i, t ->
                val amount = settlement.applied[i]
                if (amount <= Money.ZERO) return@forEachIndexed
                val id = newId()
                money.record(
                    MoneyVoucherInput(
                        type = VoucherType.PAYMENT_IN, id = id, firmId = firmId, financialYearId = financialYearId,
                        stateCode = stateCode, date = date, amount = amount, accountId = t.accountId, method = t.method,
                        partyId = partyId, instrumentNumber = t.reference?.trim()?.takeIf { t.method != PaymentMethod.CASH },
                        instrumentDate = date.takeIf { t.method == PaymentMethod.BANK || t.method == PaymentMethod.CHEQUE },
                        allocations = listOf(AllocationRequest(invoiceId, amount)),
                    ),
                    at,
                )
                ids += id
            }
        }
        return ids
    }

    fun PaymentMethod.label(): String = when (this) {
        PaymentMethod.CASH -> "Cash"
        PaymentMethod.UPI -> "UPI"
        PaymentMethod.CARD -> "Card"
        PaymentMethod.BANK -> "Bank"
        PaymentMethod.CHEQUE -> "Cheque"
    }
}
