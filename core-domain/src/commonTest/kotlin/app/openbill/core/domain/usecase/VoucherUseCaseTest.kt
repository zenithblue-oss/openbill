package app.openbill.core.domain.usecase

import app.openbill.core.domain.fake.InMemoryVoucherRepository
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherLine
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import app.openbill.core.model.Rate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class VoucherUseCaseTest {
    private val createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val updatedAt = Instant.fromEpochMilliseconds(1_700_000_100_000)
    private val postingTime = Instant.fromEpochMilliseconds(1_700_000_200_000)
    private val cancellingTime = Instant.fromEpochMilliseconds(1_700_000_300_000)

    @Test
    fun postVoucherAssignsNumberStatusAndPostedAt(): kotlin.Unit = runBlocking {
        val repository = InMemoryVoucherRepository()
        val draft = voucher("voucher-1")
        repository.saveDraft(draft)
        repository.saveDraft(draft.copy(notes = "Revised", updatedAt = updatedAt + 1.seconds))

        val posted = PostVoucher(repository)("voucher-1", postingTime)

        assertEquals(VoucherStatus.POSTED, posted.status)
        assertEquals("INV-0001", posted.number)
        assertEquals(postingTime, posted.postedAt)
        assertEquals(postingTime, posted.updatedAt)
        assertEquals("Revised", posted.notes)
        assertEquals(posted, repository.findVoucher("voucher-1"))
        assertEquals(posted, repository.observeVoucher("voucher-1").first())
    }

    @Test
    fun saleNumbersUseConfiguredPrefixAndPadding(): kotlin.Unit = runBlocking {
        val repository = InMemoryVoucherRepository(invoicePrefix = "SALE", numberPadding = 3)
        repository.saveDraft(voucher("voucher-1"))
        repository.saveDraft(voucher("voucher-2"))

        val post = PostVoucher(repository)

        assertEquals("SALE-001", post("voucher-1", postingTime).number)
        assertEquals("SALE-002", post("voucher-2", postingTime).number)
    }

    @Test
    fun numbersAreScopedByFirmTypeAndFinancialYear(): kotlin.Unit = runBlocking {
        val repository = InMemoryVoucherRepository()
        val drafts = listOf(
            voucher("voucher-1"),
            voucher("voucher-2"),
            voucher("voucher-3", firmId = "firm-2"),
            voucher("voucher-4", type = VoucherType.PURCHASE),
            voucher("voucher-5", financialYearId = "fy-2"),
            voucher("voucher-6", type = VoucherType.CREDIT_NOTE),
        )
        drafts.forEach { repository.saveDraft(it) }

        val post = PostVoucher(repository)
        val numbers = drafts.associate { draft ->
            draft.id to post(draft.id, postingTime).number
        }

        assertEquals("INV-0001", numbers["voucher-1"])
        assertEquals("INV-0002", numbers["voucher-2"])
        assertEquals("INV-0001", numbers["voucher-3"])
        assertEquals("PUR-0001", numbers["voucher-4"])
        assertEquals("INV-0001", numbers["voucher-5"])
        assertEquals("CRN-0001", numbers["voucher-6"])
    }

    @Test
    fun secondPostIsRejected(): kotlin.Unit = runBlocking {
        val repository = InMemoryVoucherRepository()
        repository.saveDraft(voucher("voucher-1"))
        val post = PostVoucher(repository)
        post("voucher-1", postingTime)

        assertFailsWith<IllegalArgumentException> {
            post("voucher-1", postingTime + 1.seconds)
        }
        assertFailsWith<IllegalArgumentException> {
            repository.post("voucher-1", postingTime + 2.seconds)
        }
    }

    @Test
    fun postRejectsEmptyLines(): kotlin.Unit = runBlocking {
        val repository = InMemoryVoucherRepository()
        repository.saveDraft(voucher("voucher-1", lines = emptyList(), total = Money.ZERO))

        assertFailsWith<IllegalArgumentException> {
            PostVoucher(repository)("voucher-1", postingTime)
        }
        assertEquals(VoucherStatus.DRAFT, repository.findVoucher("voucher-1")?.status)
        assertNull(repository.findVoucher("voucher-1")?.number)
    }

    @Test
    fun postRejectsLedgerVoucherWithoutParty(): kotlin.Unit = runBlocking {
        val repository = InMemoryVoucherRepository()
        repository.saveDraft(voucher("voucher-1").copy(partyId = null))

        assertFailsWith<IllegalArgumentException> {
            PostVoucher(repository)("voucher-1", postingTime)
        }
        assertEquals(VoucherStatus.DRAFT, repository.findVoucher("voucher-1")?.status)
        assertNull(repository.findVoucher("voucher-1")?.number)
    }

    @Test
    fun paymentVouchersMayOmitLinesButRejectTaxAndRoundOff(): kotlin.Unit = runBlocking {
        val repository = InMemoryVoucherRepository(numberPadding = 4)
        val payment = voucher(
            id = "payment-1",
            type = VoucherType.PAYMENT_OUT,
            lines = emptyList(),
            total = Money.of(5_000),
        ).copy(subtotal = Money.of(5_000), taxable = Money.ZERO)
        repository.saveDraft(payment)

        assertEquals("PAY-0001", PostVoucher(repository)("payment-1", postingTime).number)

        repository.saveDraft(
            payment.copy(
                id = "payment-2",
                number = null,
                status = VoucherStatus.DRAFT,
                postedAt = null,
                taxable = Money.of(1),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            PostVoucher(repository)("payment-2", postingTime)
        }

        repository.saveDraft(
            payment.copy(
                id = "payment-3",
                number = null,
                status = VoucherStatus.DRAFT,
                postedAt = null,
                roundOff = Money.of(1),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            PostVoucher(repository)("payment-3", postingTime)
        }
        assertEquals(VoucherStatus.DRAFT, repository.findVoucher("payment-3")?.status)
    }

    @Test
    fun postIssuesQuotationAndProformaWithANumber(): kotlin.Unit = runBlocking {
        val repository = InMemoryVoucherRepository()
        val post = PostVoucher(repository)

        for (type in listOf(VoucherType.QUOTATION, VoucherType.PROFORMA, VoucherType.DELIVERY_CHALLAN)) {
            val draft = voucher("voucher-$type", type = type)
            repository.saveDraft(draft)

            post(draft.id, postingTime)
            assertNotNull(repository.findVoucher(draft.id)?.number)
            assertEquals(VoucherStatus.POSTED, repository.findVoucher(draft.id)?.status)
        }
    }

    @Test
    fun postRejectsZeroAndNegativeTotals(): kotlin.Unit = runBlocking {
        val repository = InMemoryVoucherRepository()
        repository.saveDraft(voucher("voucher-zero", total = Money.ZERO))
        repository.saveDraft(voucher("voucher-negative", total = Money.of(-1)))
        val post = PostVoucher(repository)

        assertFailsWith<IllegalArgumentException> {
            post("voucher-zero", postingTime)
        }
        assertFailsWith<IllegalArgumentException> {
            post("voucher-negative", postingTime)
        }
    }

    @Test
    fun postRejectsMissingAndDeletedVouchers(): kotlin.Unit = runBlocking {
        val repository = InMemoryVoucherRepository()
        repository.saveDraft(voucher("voucher-1").copy(deletedAt = updatedAt))
        val post = PostVoucher(repository)

        assertFailsWith<IllegalArgumentException> {
            post("missing", postingTime)
        }
        assertFailsWith<IllegalArgumentException> {
            post("voucher-1", postingTime)
        }
    }

    @Test
    fun concurrentPostsAllocateDistinctNumbers(): kotlin.Unit = runBlocking {
        val repository = InMemoryVoucherRepository()
        val drafts = (1..20).map { voucher("voucher-$it") }
        drafts.forEach { repository.saveDraft(it) }
        val post = PostVoucher(repository)

        val numbers = coroutineScope {
            drafts.map { draft -> async { post(draft.id, postingTime).number } }.awaitAll()
        }

        assertEquals(numbers.size, numbers.distinct().size)
        assertEquals(
            (1..20).map { "INV-${it.toString().padStart(4, '0')}" }.toSet(),
            numbers.toSet(),
        )
    }

    @Test
    fun cancelVoucherRetainsNumberAndRecordsReasonTimeAndStatus(): kotlin.Unit = runBlocking {
        val repository = InMemoryVoucherRepository()
        repository.saveDraft(voucher("voucher-1"))
        val posted = PostVoucher(repository)("voucher-1", postingTime)

        val cancelled = CancelVoucher(repository)("voucher-1", "  Customer request  ", cancellingTime)

        assertEquals(posted.number, cancelled.number)
        assertEquals(VoucherStatus.CANCELLED, cancelled.status)
        assertEquals("Customer request", cancelled.cancellationReason)
        assertEquals(cancellingTime, cancelled.cancelledAt)
        assertEquals(cancellingTime, cancelled.updatedAt)
        assertEquals(cancelled, repository.observeVoucher("voucher-1").first())
    }

    @Test
    fun cancelRejectsBlankReason(): kotlin.Unit = runBlocking {
        val repository = InMemoryVoucherRepository()
        repository.saveDraft(voucher("voucher-1"))
        PostVoucher(repository)("voucher-1", postingTime)

        assertFailsWith<IllegalArgumentException> {
            CancelVoucher(repository)("voucher-1", "   ", cancellingTime)
        }
        assertEquals(VoucherStatus.POSTED, repository.findVoucher("voucher-1")?.status)
    }

    @Test
    fun cancelRejectsDraft(): kotlin.Unit = runBlocking {
        val repository = InMemoryVoucherRepository()
        repository.saveDraft(voucher("voucher-1"))

        assertFailsWith<IllegalArgumentException> {
            CancelVoucher(repository)("voucher-1", "Not posted", cancellingTime)
        }
        assertEquals(VoucherStatus.DRAFT, repository.findVoucher("voucher-1")?.status)
    }

    @Test
    fun secondCancelIsRejected(): kotlin.Unit = runBlocking {
        val repository = InMemoryVoucherRepository()
        repository.saveDraft(voucher("voucher-1"))
        PostVoucher(repository)("voucher-1", postingTime)
        CancelVoucher(repository)("voucher-1", "Customer request", cancellingTime)

        assertFailsWith<IllegalArgumentException> {
            CancelVoucher(repository)("voucher-1", "Customer request", cancellingTime + 1.seconds)
        }
        assertFailsWith<IllegalArgumentException> {
            repository.cancel("voucher-1", "Customer request", cancellingTime + 2.seconds)
        }
        assertEquals(VoucherStatus.CANCELLED, repository.findVoucher("voucher-1")?.status)
    }

    private fun voucher(
        id: String,
        firmId: String = "firm-1",
        financialYearId: String = "fy-1",
        type: VoucherType = VoucherType.SALE,
        lines: List<VoucherLine> = listOf(line(id)),
        total: Money = Money.of(23_600),
    ): Voucher = Voucher(
        id = id,
        firmId = firmId,
        financialYearId = financialYearId,
        type = type,
        date = LocalDate(2026, 5, 10),
        partyId = "party-1",
        placeOfSupplyStateCode = "27",
        lines = lines,
        subtotal = total,
        taxable = total,
        total = total,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun line(voucherId: String): VoucherLine = VoucherLine(
        id = "line-$voucherId",
        lineNumber = 1,
        itemId = "item-1",
        description = "Steel Widget",
        quantity = Quantity.of(2),
        rate = Money.of(10_000),
        taxRate = Rate.ofBasisPoints(1_800),
        taxable = Money.of(20_000),
        tax = Money.of(3_600),
        total = Money.of(23_600),
    )
}
