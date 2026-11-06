package app.openbill.core.domain.usecase

import app.openbill.core.domain.fake.InMemoryRecurringRepository
import app.openbill.core.domain.fake.InMemoryVoucherRepository
import app.openbill.core.domain.model.OrderFulfilment
import app.openbill.core.domain.model.OrderStatus
import app.openbill.core.domain.model.Orders
import app.openbill.core.domain.model.RecurrenceFrequency
import app.openbill.core.domain.model.RecurringSchedule
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherLine
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.model.postsLedger
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate

class OrdersAndRecurringTest {
    private val at = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private var seq = 0
    private fun id() = "id-${seq++}"

    private fun line(id: String, qty: Long, discount: Long = 0) = VoucherLine(
        id = id, lineNumber = id.last().digitToInt(), description = "Item $id", quantity = Quantity.of(qty),
        rate = Money.of(10_000), discount = Money.of(discount), taxable = Money.of(10_000 * qty - discount), total = Money.of(10_000 * qty - discount),
    )

    private fun voucher(id: String, type: VoucherType, lines: List<VoucherLine>, ref: String? = null, date: LocalDate = LocalDate(2026, 5, 1)) = Voucher(
        id = id, firmId = "f", financialYearId = "fy", type = type, date = date, partyId = "p", referenceVoucherId = ref,
        placeOfSupplyStateCode = "27", lines = lines, total = Money.of(lines.sumOf { it.total.paise }), createdAt = at, updatedAt = at,
    )

    @Test
    fun partialConversionTracksPendingAndOrderStatus(): Unit = runBlocking {
        val repo = InMemoryVoucherRepository()
        val post = PostVoucher(repo)
        repo.saveDraft(voucher("so", VoucherType.SALES_ORDER, listOf(line("l1", 10, discount = 1_000), line("l2", 5))))
        assertEquals("SO-0001", post("so", at).number)
        assertEquals(OrderStatus.OPEN, Orders.status(repo.findVoucher("so")!!))

        // First invoice: 4 of line 1 only.
        val first = Orders.conversionLines(repo.findVoucher("so")!!, emptyList(), ::id)!!
        assertEquals(listOf(10L, 5L), first.map { it.quantity.scaledUnits / 10_000 })
        val partial = first.take(1).map { it.copy(quantity = Quantity.of(4), discount = Money.of(400)) }
        repo.saveDraft(voucher("inv1", VoucherType.SALE, partial, ref = "so"))
        // A draft reserves its quantity for the next conversion but does not fulfil the order.
        val withDraft = Orders.conversionLines(repo.findVoucher("so")!!, repo.observeConversions("so").first(), ::id)!!
        assertEquals(listOf(6L, 5L), withDraft.map { it.quantity.scaledUnits / 10_000 })
        assertEquals(Money.of(600), withDraft[0].discount, "the pending part keeps its share of the discount")
        assertEquals(OrderStatus.OPEN, Orders.status(repo.findVoucher("so")!!))

        post("inv1", at)
        assertEquals(OrderFulfilment.PARTIAL, repo.findVoucher("so")!!.fulfilment)
        assertEquals(OrderStatus.PARTIAL, Orders.status(repo.findVoucher("so")!!))

        // Second invoice takes the rest: the order closes.
        val rest = Orders.conversionLines(repo.findVoucher("so")!!, repo.observeConversions("so").first(), ::id)!!
        repo.saveDraft(voucher("inv2", VoucherType.SALE, rest, ref = "so"))
        post("inv2", at)
        assertEquals(OrderStatus.CLOSED, Orders.status(repo.findVoucher("so")!!))
        assertNull(Orders.conversionLines(repo.findVoucher("so")!!, repo.observeConversions("so").first(), ::id))

        // Cancelling an invoice reopens what it billed.
        CancelVoucher(repo)("inv2", "wrong", at)
        assertEquals(OrderStatus.PARTIAL, Orders.status(repo.findVoucher("so")!!))
    }

    @Test
    fun ordersNeverPostStockOrLedger(): Unit = runBlocking {
        val repo = InMemoryVoucherRepository()
        repo.saveDraft(voucher("po", VoucherType.PURCHASE_ORDER, listOf(line("l1", 3))))
        assertEquals("PO-0001", PostVoucher(repo)("po", at).number)
        assertFalse(VoucherType.PURCHASE_ORDER.postsLedger())
        assertNull(app.openbill.core.domain.service.StockMovementDerivation.directionOf(VoucherType.PURCHASE_ORDER, line("l1", 3)))
    }

    private fun schedule(start: LocalDate, max: Int? = null, end: LocalDate? = null, autoPost: Boolean = false) = RecurringSchedule(
        id = "r", firmId = "f", templateVoucherId = "tpl", name = "Rent", frequency = RecurrenceFrequency.MONTHLY,
        startDate = start, endDate = end, maxCount = max, autoPost = autoPost, createdAt = at, updatedAt = at,
    )

    @Test
    fun monthlyDatesDoNotDriftAndLimitsStopTheSchedule() {
        val s = schedule(LocalDate(2026, 1, 31), max = 3)
        assertEquals(listOf(LocalDate(2026, 1, 31), LocalDate(2026, 2, 28), LocalDate(2026, 3, 31)), (0..2).map(s::occurrence))
        assertEquals(listOf(0, 1, 2), s.dueIndexes(LocalDate(2027, 1, 1)))
        assertEquals(listOf(0, 1), schedule(LocalDate(2026, 1, 31), end = LocalDate(2026, 3, 30)).dueIndexes(LocalDate(2027, 1, 1)))
        assertTrue(s.copy(nextIndex = 3).finished)
    }

    @Test
    fun catchUpMakesEachMissedPeriodOnceAndResumeSkipsPausedPeriods(): Unit = runBlocking {
        val vouchers = InMemoryVoucherRepository()
        val recurring = InMemoryRecurringRepository(vouchers)
        vouchers.saveDraft(voucher("tpl", VoucherType.SALE, listOf(line("l1", 1))).copy(dueDate = LocalDate(2026, 5, 16), ewayBillNumber = "123456789012"))
        recurring.saveSchedule(schedule(LocalDate(2026, 6, 10), autoPost = true))
        val generate = GenerateRecurring(recurring, vouchers) { id, t -> PostVoucher(vouchers)(id, t) }
        val today = LocalDate(2026, 9, 15)

        assertEquals(4, generate("f", today, at, ::id) { "fy" }) // Jun, Jul, Aug, Sep
        assertEquals(0, generate("f", today, at, ::id) { "fy" }, "re-running makes nothing")
        val made = vouchers.observeVouchers("f", LocalDate(2026, 6, 1), LocalDate(2026, 12, 31)).first().filter { it.id != "tpl" }
        assertEquals(4, made.size)
        assertEquals(listOf(LocalDate(2026, 6, 10), LocalDate(2026, 7, 10), LocalDate(2026, 8, 10), LocalDate(2026, 9, 10)), made.map { it.date }.sorted())
        assertTrue(made.all { it.status == VoucherStatus.POSTED && it.ewayBillNumber == null })
        assertEquals(LocalDate(2026, 6, 25), made.first { it.date == LocalDate(2026, 6, 10) }.dueDate, "15-day terms kept")
        assertEquals(4, recurring.findSchedule("r")!!.generatedCount)

        // A claimed period cannot be claimed again.
        assertFalse(recurring.claimOccurrence("r", 3, null))

        // Paused through Oct and Nov; resumed on 20 Nov: Oct and Nov are skipped, Dec comes next.
        recurring.saveSchedule(recurring.findSchedule("r")!!.copy(paused = true))
        assertEquals(0, generate("f", LocalDate(2026, 11, 20), at, ::id) { "fy" })
        recurring.saveSchedule(GenerateRecurring.resumed(recurring.findSchedule("r")!!, LocalDate(2026, 11, 20), at))
        assertEquals(LocalDate(2026, 12, 10), recurring.findSchedule("r")!!.nextDate)
        assertEquals(0, generate("f", LocalDate(2026, 11, 20), at, ::id) { "fy" })
        assertEquals(1, generate("f", LocalDate(2026, 12, 10), at, ::id) { "fy" })
        assertEquals(5, recurring.findSchedule("r")!!.generatedCount)
    }

    @Test
    fun leapYearsAndMonthEndsClampWithoutDrift() {
        val monthly = schedule(LocalDate(2028, 1, 31))
        assertEquals(LocalDate(2028, 2, 29), monthly.occurrence(1), "leap February")
        assertEquals(LocalDate(2028, 3, 31), monthly.occurrence(2))
        val yearly = schedule(LocalDate(2028, 2, 29)).copy(frequency = RecurrenceFrequency.YEARLY)
        assertEquals(listOf(LocalDate(2029, 2, 28), LocalDate(2032, 2, 29)), listOf(yearly.occurrence(1), yearly.occurrence(4)))
    }

    @Test
    fun savingAStaleCopyNeverRewindsTheSchedule(): Unit = runBlocking {
        val vouchers = InMemoryVoucherRepository()
        val recurring = InMemoryRecurringRepository(vouchers)
        vouchers.saveDraft(voucher("tpl", VoucherType.SALE, listOf(line("l1", 1))))
        recurring.saveSchedule(schedule(LocalDate(2026, 6, 10)))
        val stale = recurring.findSchedule("r")!!
        val generate = GenerateRecurring(recurring, vouchers) { _, _ -> }
        assertEquals(2, generate("f", LocalDate(2026, 7, 10), at, ::id) { "fy" })
        // A screen still holding the old copy pauses it: the claimed periods stay claimed.
        recurring.saveSchedule(stale.copy(paused = true))
        recurring.saveSchedule(recurring.findSchedule("r")!!.copy(paused = false))
        assertEquals(0, generate("f", LocalDate(2026, 7, 10), at, ::id) { "fy" })
        assertEquals(2, recurring.findSchedule("r")!!.nextIndex)
    }

    @Test
    fun missingFinancialYearStopsWithAnError(): Unit = runBlocking {
        val vouchers = InMemoryVoucherRepository()
        val recurring = InMemoryRecurringRepository(vouchers)
        vouchers.saveDraft(voucher("tpl", VoucherType.SALE, listOf(line("l1", 1))))
        recurring.saveSchedule(schedule(LocalDate(2026, 3, 1)))
        val made = GenerateRecurring(recurring, vouchers) { _, _ -> }("f", LocalDate(2026, 5, 1), at, ::id) { d -> "fy".takeIf { d >= LocalDate(2026, 4, 1) } }
        assertEquals(0, made)
        assertTrue(recurring.findSchedule("r")!!.lastError!!.contains("No financial year"))
        assertEquals(0, recurring.findSchedule("r")!!.nextIndex)
    }
}
