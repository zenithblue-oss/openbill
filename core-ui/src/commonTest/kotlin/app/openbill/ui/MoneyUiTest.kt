package app.openbill.ui

import app.openbill.core.domain.model.Account
import app.openbill.core.domain.model.AccountType
import app.openbill.core.domain.model.OpeningBalanceType
import app.openbill.core.domain.model.OutstandingInvoice
import app.openbill.core.domain.model.Party
import app.openbill.core.domain.model.PartyMovement
import app.openbill.core.domain.model.PartyType
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.model.Money
import app.openbill.ui.invoice.pdfTitle
import app.openbill.ui.money.MoneyOverview
import app.openbill.ui.money.Reminders
import app.openbill.ui.nav.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

class MoneyUiTest {
    private val at = Instant.parse("2026-05-01T00:00:00Z")

    @Test
    fun moneyRoutesRoundTrip() {
        listOf(
            Route.MoneyEditor(VoucherType.PAYMENT_IN, "p1", "v1"),
            Route.MoneyEditor(VoucherType.EXPENSE),
            Route.MoneyDetail("m1"),
            Route.AccountLedger("a1"),
            Route.Outstanding(false),
            Route.PartyLedger("p1"),
            Route.Documents(VoucherType.QUOTATION),
        ).forEach { assertEquals(it, Route.fromKey(it.key)) }
    }

    @Test
    fun overviewSplitsReceivablesPayablesAndMoney() {
        fun party(id: String, opening: Long, type: OpeningBalanceType) =
            Party(id, "f", id, PartyType.BOTH, openingBalance = Money.of(opening), openingBalanceType = type, createdAt = at, updatedAt = at)
        val movements = listOf(PartyMovement("a", "v", VoucherType.SALE, "INV-1", LocalDate(2026, 5, 1), Money.of(500)))
        val o = MoneyOverview.of(
            listOf(party("a", 1_000, OpeningBalanceType.DEBIT), party("b", 300, OpeningBalanceType.CREDIT), party("c", 0, OpeningBalanceType.DEBIT)),
            movements,
            listOf(
                Account("cash", "f", "Cash", AccountType.CASH, createdAt = at, updatedAt = at),
                Account("ar", "f", "AR", AccountType.ASSET, createdAt = at, updatedAt = at),
            ),
            mapOf("cash" to Money.of(700), "ar" to Money.of(99)),
            LocalDate(2026, 5, 31),
        )
        assertEquals(Money.of(1_500), o.receivable)
        assertEquals(Money.of(300), o.payable)
        assertEquals(Money.of(700), o.cashAndBank)
        assertEquals(2, o.positions.size)
    }

    @Test
    fun reminderListsDuesAndUpiLink() {
        val text = Reminders.text(
            "Shree Traders", "Asha", Money.of(150_000),
            listOf(OutstandingInvoice("v", "INV-7", LocalDate(2026, 5, 1), null, "p", VoucherType.SALE, Money.of(200_000), Money.of(50_000))),
            LocalDate(2026, 5, 31),
            "shree@upi",
        )
        assertTrue("INV-7" in text && "30 days" in text)
        assertTrue("upi://pay?pa=shree@upi" in text && "am=1500.00" in text)
        assertEquals(
            "Hi Asha, Shree Traders here: ₹1,500.00 due.",
            Reminders.text("Shree Traders", "Asha", Money.of(150_000), emptyList(), LocalDate(2026, 5, 31), template = "Hi {party}, {firm} here: {balance} due.{invoices}{upi}"),
        )
    }

    @Test
    fun pdfHeadingsFollowTheDocumentType() {
        assertEquals("Tax Invoice", pdfTitle(VoucherType.SALE, "27ABCDE1234F1Z5"))
        assertEquals("Credit Note", pdfTitle(VoucherType.CREDIT_NOTE, null))
        assertEquals("Debit Note", pdfTitle(VoucherType.DEBIT_NOTE, null))
        assertEquals("Purchase Bill", pdfTitle(VoucherType.PURCHASE, null))
        assertEquals("Quotation", pdfTitle(VoucherType.QUOTATION, null))
        assertEquals("Proforma Invoice", pdfTitle(VoucherType.PROFORMA, null))
        assertEquals("Delivery Challan", pdfTitle(VoucherType.DELIVERY_CHALLAN, null))
    }
}
