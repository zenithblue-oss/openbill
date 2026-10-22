package app.openbill.core.domain.model

import app.openbill.core.model.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

class PaymentModelsTest {
    private val now = Instant.parse("2026-05-10T09:00:00Z")
    private val date = LocalDate(2026, 5, 10)

    @Test
    fun cashDetailsRejectInstrumentBankAndUpiFields() {
        assertFailsWith<IllegalArgumentException> {
            details(method = PaymentMethod.CASH, instrumentNumber = "123")
        }
        assertFailsWith<IllegalArgumentException> {
            details(method = PaymentMethod.CASH, bankName = "HDFC")
        }
        assertFailsWith<IllegalArgumentException> {
            details(method = PaymentMethod.CASH, upiId = "shop@upi")
        }
        assertEquals(PaymentMethod.CASH, details().method)
    }

    @Test
    fun bankAndChequeRequireInstrumentNumberAndDate() {
        assertFailsWith<IllegalArgumentException> {
            details(method = PaymentMethod.BANK, instrumentNumber = "TXN-1")
        }
        assertFailsWith<IllegalArgumentException> {
            details(method = PaymentMethod.BANK, instrumentDate = date)
        }
        assertFailsWith<IllegalArgumentException> {
            details(method = PaymentMethod.CHEQUE, instrumentNumber = "CHQ-1")
        }
        assertFailsWith<IllegalArgumentException> {
            details(
                method = PaymentMethod.BANK,
                instrumentNumber = "TXN-1",
                instrumentDate = date,
                upiId = "shop@upi",
            )
        }
        val bank = details(
            method = PaymentMethod.BANK,
            instrumentNumber = "TXN-1",
            instrumentDate = date,
        )
        assertEquals("TXN-1", bank.instrumentNumber)
    }

    @Test
    fun upiRequiresInstrumentNumberButAllowsUpiId() {
        assertFailsWith<IllegalArgumentException> {
            details(method = PaymentMethod.UPI, upiId = "shop@upi")
        }
        val upi = details(
            method = PaymentMethod.UPI,
            instrumentNumber = "UPI-TXN-1",
            upiId = "shop@upi",
        )
        assertEquals("UPI-TXN-1", upi.instrumentNumber)
        assertEquals("shop@upi", upi.upiId)
    }

    @Test
    fun normalizedTrimsOptionalFieldsAndRejectsBlankValues() {
        val normalized = details(
            reference = "  REF-1  ",
            notes = "  Paid in full  ",
        ).normalized()

        assertEquals("REF-1", normalized.reference)
        assertEquals("Paid in full", normalized.notes)
        assertFailsWith<IllegalArgumentException> {
            details(notes = "   ")
        }
        assertFailsWith<IllegalArgumentException> {
            details(reference = " ")
        }
    }

    @Test
    fun allocationRequiresPositiveAmountAndOrderedTimestamps() {
        assertFailsWith<IllegalArgumentException> {
            PaymentAllocation(
                id = "allocation-1",
                firmId = "firm-1",
                paymentVoucherId = "payment-1",
                invoiceVoucherId = "invoice-1",
                amount = Money.ZERO,
                createdAt = now,
                updatedAt = now,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PaymentAllocation(
                id = "allocation-1",
                firmId = "firm-1",
                paymentVoucherId = "payment-1",
                invoiceVoucherId = "invoice-1",
                amount = Money.of(100L),
                createdAt = now,
                updatedAt = now - kotlin.time.Duration.parse("1s"),
            )
        }
    }

    @Test
    fun outstandingInvoiceMustHavePositiveRemainder() {
        assertFailsWith<IllegalArgumentException> {
            OutstandingInvoice(
                voucherId = "invoice-1",
                number = "INV-0001",
                date = date,
                dueDate = null,
                partyId = "party-1",
                type = VoucherType.SALE,
                total = Money.of(100L),
                allocated = Money.of(100L),
            )
        }
        val outstanding = OutstandingInvoice(
            voucherId = "invoice-1",
            number = "INV-0001",
            date = date,
            dueDate = null,
            partyId = "party-1",
            type = VoucherType.SALE,
            total = Money.of(150L),
            allocated = Money.of(100L),
        )
        assertEquals(Money.of(50L), outstanding.outstanding)
    }

    private fun details(
        method: PaymentMethod = PaymentMethod.CASH,
        instrumentNumber: String? = null,
        instrumentDate: LocalDate? = null,
        bankName: String? = null,
        upiId: String? = null,
        reference: String? = null,
        notes: String? = null,
    ): PaymentDetails = PaymentDetails(
        voucherId = "payment-1",
        firmId = "firm-1",
        accountId = "account-cash",
        method = method,
        instrumentNumber = instrumentNumber,
        instrumentDate = instrumentDate,
        bankName = bankName,
        upiId = upiId,
        reference = reference,
        notes = notes,
        createdAt = now,
        updatedAt = now,
    )
}
