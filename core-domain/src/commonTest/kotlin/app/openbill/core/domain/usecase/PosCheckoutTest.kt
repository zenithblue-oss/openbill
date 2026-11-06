package app.openbill.core.domain.usecase

import app.openbill.core.domain.model.Party
import app.openbill.core.domain.model.PartyType
import app.openbill.core.domain.model.PaymentMethod
import app.openbill.core.model.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class PosCheckoutTest {
    private fun rs(r: String) = Money.parse(r)
    private fun cash(a: String) = PosTender(PaymentMethod.CASH, "cash", rs(a))
    private fun upi(a: String, ref: String? = "UTR1") = PosTender(PaymentMethod.UPI, "bank", rs(a), ref)

    @Test
    fun cashOverpaymentGivesChangeOutOfCashOnly() {
        val s = PosCheckout.settle(rs("118.00"), listOf(cash("100.00"), upi("50.00")), creditAllowed = false)
        assertEquals(rs("32.00"), s.change)
        assertEquals(listOf(rs("68.00"), rs("50.00")), s.applied)
        assertEquals(rs("118.00"), s.paid)
        assertEquals(Money.ZERO, s.credit)
    }

    @Test
    fun changeComesFromTheLastCashTendersFirst() {
        val s = PosCheckout.settle(rs("100.00"), listOf(cash("60.00"), upi("40.00"), cash("20.00")), creditAllowed = false)
        assertEquals(listOf(rs("60.00"), rs("40.00"), Money.ZERO), s.applied)
        assertEquals(rs("20.00"), s.change)
    }

    @Test
    fun underpaymentIsBlockedUnlessACustomerTakesItOnCredit() {
        val e = assertFailsWith<IllegalArgumentException> { PosCheckout.settle(rs("118.00"), listOf(cash("100.00")), creditAllowed = false) }
        assertTrue(e.message!!.contains("18.00"), e.message)
        val s = PosCheckout.settle(rs("118.00"), listOf(cash("100.00")), creditAllowed = true)
        assertEquals(rs("18.00"), s.credit)
        assertEquals(listOf(rs("100.00")), s.applied)
        assertEquals(rs("118.00"), PosCheckout.settle(rs("118.00"), emptyList(), creditAllowed = true).credit)
    }

    @Test
    fun nonCashCannotExceedTheBillAndNeedsAReference() {
        assertFailsWith<IllegalArgumentException> { PosCheckout.settle(rs("100.00"), listOf(upi("100.01")), creditAllowed = true) }
        assertFailsWith<IllegalArgumentException> { PosCheckout.settle(rs("100.00"), listOf(upi("100.00", ref = " ")), creditAllowed = false) }
        assertFailsWith<IllegalArgumentException> { PosCheckout.settle(rs("100.00"), listOf(cash("0.00")), creditAllowed = true) }
        assertEquals(Money.ZERO, PosCheckout.settle(rs("100.00"), listOf(upi("100.00")), creditAllowed = false).change)
    }

    @Test
    fun cashSalePartyIsReusedOrCreated() {
        val at = Instant.fromEpochMilliseconds(1_700_000_000_000)
        val (made, isNew) = PosCheckout.cashSaleParty(emptyList(), "f", "27", { "p1" }, at)
        assertTrue(isNew)
        assertEquals("Cash sale", made.name)
        assertEquals("27", made.stateCode)
        val other = Party(id = "p2", firmId = "f", name = "Acme", type = PartyType.CUSTOMER, createdAt = at, updatedAt = at)
        val (found, again) = PosCheckout.cashSaleParty(listOf(other, made), "f", "27", { "x" }, at)
        assertFalse(again)
        assertEquals("p1", found.id)
        assertFalse(PosCheckout.isCashSale(other))
    }
}
