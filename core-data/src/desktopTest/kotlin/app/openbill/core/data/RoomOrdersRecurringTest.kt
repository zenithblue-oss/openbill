package app.openbill.core.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.domain.model.OrderFulfilment
import app.openbill.core.domain.model.Orders
import app.openbill.core.domain.model.RecurrenceFrequency
import app.openbill.core.domain.model.RecurringSchedule
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.report.ReportData
import app.openbill.core.domain.report.ReportPeriod
import app.openbill.core.domain.report.Reports
import app.openbill.core.domain.usecase.GenerateRecurring
import app.openbill.core.model.Quantity
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate

class RoomOrdersRecurringTest {
    private lateinit var db: OpenBillDatabase
    private var seq = 0
    private fun id() = "gen-${seq++}"

    @BeforeTest
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder<OpenBillDatabase>().setDriver(BundledSQLiteDriver()).build()
    }

    @AfterTest
    fun tearDown() = db.close()

    private suspend fun seed() = db.roomRepositories().also { r ->
        r.firms.saveFirm(testFirm())
        r.firms.saveSettings(testSettings(defaultGodownId = null))
        r.firms.saveFinancialYear(testFinancialYear())
        r.parties.saveParty(testParty())
    }

    @Test
    fun postedConversionsKeepOrderFulfilmentAndStayOutOfLedgerAndDayBook(): Unit = runBlocking {
        val r = seed()
        r.vouchers.saveDraft(testVoucher("so", type = VoucherType.SALES_ORDER, charges = emptyList()))
        assertEquals("SO-0001", r.vouchers.post("so", POSTED_AT).number)
        assertTrue(r.ledger.journalEntriesForVoucher("so").isEmpty())

        val order = r.vouchers.findVoucher("so")!!
        val half = Orders.conversionLines(order, emptyList(), ::id)!!.map { it.copy(quantity = Quantity.of(1)) }
        r.vouchers.saveDraft(testVoucher("inv", lines = half, charges = emptyList()).copy(referenceVoucherId = "so"))
        assertEquals("line-1", r.vouchers.observeConversions("so").first().single().lines.single().sourceLineId)
        r.vouchers.post("inv", POSTED_AT)
        assertEquals(OrderFulfilment.PARTIAL, r.vouchers.findVoucher("so")!!.fulfilment)
        assertFalse(r.ledger.journalEntriesForVoucher("inv").isEmpty())

        r.vouchers.cancel("inv", "wrong qty", POSTED_AT)
        assertEquals(OrderFulfilment.NONE, r.vouchers.findVoucher("so")!!.fulfilment)

        val data = ReportData(
            firm = testFirm(), settings = null, period = ReportPeriod(LocalDate(2026, 4, 1), LocalDate(2027, 3, 31)),
            vouchers = r.vouchers.observeVouchers("firm-1", LocalDate(2026, 4, 1), LocalDate(2027, 3, 31)).first(),
            parties = emptyList(), items = emptyList(), units = emptyList(), accounts = emptyList(),
            journal = emptyList(), stock = emptyList(), partyMovements = emptyList(),
        )
        assertTrue(Reports.dayBook(data).rows.none { "SO-0001" in it })
    }

    @Test
    fun shortCloseClosesWhatIsPendingOnceAndBlocksFurtherConversions(): Unit = runBlocking {
        val r = seed()
        r.vouchers.saveDraft(testVoucher("so", type = VoucherType.SALES_ORDER, charges = emptyList()))
        r.vouchers.post("so", POSTED_AT)
        assertFailsWith<IllegalArgumentException>("a draft order cannot be closed") {
            r.vouchers.saveDraft(testVoucher("so-d", type = VoucherType.SALES_ORDER, lines = listOf(testLine("so-d-l")), charges = emptyList()))
            r.vouchers.shortCloseOrder("so-d", null, POSTED_AT)
        }
        val one = Orders.conversionLines(r.vouchers.findVoucher("so")!!, emptyList(), ::id)!!.map { it.copy(quantity = Quantity.of(1)) }
        r.vouchers.saveDraft(testVoucher("inv", lines = one, charges = emptyList()).copy(referenceVoucherId = "so"))
        r.vouchers.post("inv", POSTED_AT)
        // A second conversion draft made before the close cannot be posted after it.
        r.vouchers.saveDraft(testVoucher("inv2", lines = one.map { it.copy(id = "inv2-l") }, charges = emptyList()).copy(referenceVoucherId = "so"))

        val cashier = app.openbill.core.domain.model.AppUser("u-c", "Ravi", app.openbill.core.domain.model.Role.CASHIER, pinHash = "1:00:00", createdAt = CREATED_AT)
        r.users.saveUser(cashier)
        app.openbill.core.domain.model.Access.current = cashier
        try {
            assertFailsWith<app.openbill.core.domain.model.PermissionDeniedException> { r.vouchers.shortCloseOrder("so", "x", POSTED_AT) }
        } finally {
            app.openbill.core.domain.model.Access.current = null
        }
        assertFailsWith<IllegalArgumentException> { r.vouchers.shortCloseOrder("so", "x".repeat(Orders.SHORT_CLOSE_NOTE_MAX + 1), POSTED_AT) }

        val closed = r.vouchers.shortCloseOrder("so", "  Customer cancelled the rest  ", POSTED_AT)
        assertEquals(app.openbill.core.domain.model.OrderStatus.CLOSED, Orders.status(closed))
        assertEquals("Customer cancelled the rest", closed.shortCloseNote)
        assertEquals(mapOf("line-1" to Quantity.of(1)), Orders.shortClosed(closed, r.vouchers.observeConversions("so").first()), "2 ordered, 1 billed")
        assertEquals(null, Orders.conversionLines(closed, emptyList(), ::id), "nothing more to convert")
        assertFalse(Orders.canShortClose(closed))
        assertFailsWith<IllegalArgumentException>("closed once, no re-open") { r.vouchers.shortCloseOrder("so", null, POSTED_AT) }
        assertFailsWith<IllegalArgumentException> { r.vouchers.post("inv2", POSTED_AT) }

        val event = r.audit.observeRecent("firm-1", 100).first().single { it.action == "voucher.short_closed" }
        assertEquals("so", event.voucherId)
        assertTrue("Customer cancelled the rest" in event.detailsJson.orEmpty() && ": 1" in event.detailsJson.orEmpty(), event.detailsJson)
        // The fulfilled part stays: cancelling the invoice leaves the order closed with all of it pending.
        r.vouchers.cancel("inv", "wrong", POSTED_AT)
        assertEquals(app.openbill.core.domain.model.OrderStatus.CLOSED, Orders.status(r.vouchers.findVoucher("so")!!))
    }

    @Test
    fun heldBillsAreListedUntilPostedOrDiscarded(): Unit = runBlocking {
        val r = seed()
        fun held(id: String) = testVoucher(id, lines = listOf(testLine("$id-l")), charges = emptyList())
        r.vouchers.saveDraft(held("h1").copy(heldAt = POSTED_AT))
        r.vouchers.saveDraft(held("h2").copy(heldAt = CREATED_AT))
        r.vouchers.saveDraft(held("d3"))
        assertEquals(listOf("h2", "h1"), r.vouchers.observeHeldBills("firm-1").first().map { it.id }, "oldest hold first, plain drafts left out")
        assertTrue(r.vouchers.observeHeldBills("firm-1").first().all { it.lines.isNotEmpty() })
        r.vouchers.post("h1", POSTED_AT)
        r.vouchers.softDeleteDraft("h2", POSTED_AT)
        assertTrue(r.vouchers.observeHeldBills("firm-1").first().isEmpty())
    }

    @Test
    fun recurringClaimIsAtomicAndIdempotent(): Unit = runBlocking {
        val r = seed()
        r.vouchers.saveDraft(testVoucher("tpl", charges = emptyList()))
        r.recurring.saveSchedule(
            RecurringSchedule(
                id = "rs", firmId = "firm-1", templateVoucherId = "tpl", name = "Retainer", frequency = RecurrenceFrequency.MONTHLY,
                startDate = LocalDate(2026, 6, 5), createdAt = CREATED_AT, updatedAt = CREATED_AT,
            ),
        )
        val generate = GenerateRecurring(r.recurring, r.vouchers) { v, at -> r.vouchers.post(v, at) }
        val today = LocalDate(2026, 8, 20)
        assertEquals(3, generate("firm-1", today, POSTED_AT, ::id) { "fy-1" })
        assertEquals(0, generate("firm-1", today, POSTED_AT, ::id) { "fy-1" })
        val drafts = r.vouchers.observeVouchers("firm-1", LocalDate(2026, 6, 1), LocalDate(2026, 12, 31)).first().filter { it.id != "tpl" }
        assertEquals(listOf(LocalDate(2026, 6, 5), LocalDate(2026, 7, 5), LocalDate(2026, 8, 5)), drafts.map { it.date }.sorted())
        assertEquals(3, r.recurring.findSchedule("rs")!!.nextIndex)
        val latest = drafts.maxBy { it.date }.id
        assertEquals(latest, r.recurring.findSchedule("rs")!!.lastVoucherId, "the newest invoice is remembered for copy-latest")
        r.recurring.saveSchedule(r.recurring.findSchedule("rs")!!.copy(copyLatest = true, lastVoucherId = null))
        assertEquals(latest, r.recurring.findSchedule("rs")!!.lastVoucherId, "a stale save does not forget it")
        assertTrue(r.recurring.findSchedule("rs")!!.copyLatest)
        assertFalse(r.recurring.claimOccurrence("rs", 1, null), "a handled period cannot be claimed again")
        // Pausing from a stale copy (nextIndex 0) must not rewind the claimed periods.
        r.recurring.saveSchedule(r.recurring.findSchedule("rs")!!.copy(nextIndex = 0, generatedCount = 0, paused = true))
        assertEquals(3, r.recurring.findSchedule("rs")!!.nextIndex)
        assertEquals(3, r.recurring.findSchedule("rs")!!.generatedCount)
        assertTrue(r.recurring.findSchedule("rs")!!.paused)
        r.recurring.deleteSchedule("rs")
        assertTrue(r.recurring.observeSchedules("firm-1").first().isEmpty())
    }
}
