package app.openbill.core.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.domain.model.AccountCode
import app.openbill.core.domain.model.PartyType
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherTally
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.report.ReportKind
import app.openbill.core.domain.report.ReportPeriod
import app.openbill.core.domain.report.Reports
import app.openbill.core.domain.report.TallyXml
import app.openbill.core.model.Progress
import app.openbill.core.domain.report.loadReportData
import app.openbill.core.domain.report.writeRegisterCsv
import app.openbill.core.domain.report.writeTallyXml
import app.openbill.core.domain.repository.Repositories
import app.openbill.core.domain.service.PartyAgeing
import app.openbill.core.domain.service.StockValuation
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate

/** The SQL-paged and SQL-aggregated reads match the load-everything reads they replace. */
class RoomPagingTest {
    private lateinit var db: OpenBillDatabase
    private lateinit var repos: Repositories
    private val sale = setOf(VoucherType.SALE)

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder<OpenBillDatabase>().setDriver(BundledSQLiteDriver()).build()
        repos = db.roomRepositories()
        repos.firms.saveFirm(testFirm())
        repos.firms.saveSettings(testSettings(defaultGodownId = "godown-1", negativeStockAllowed = true))
        repos.firms.saveFinancialYear(testFinancialYear())
        repos.parties.saveParty(testParty())
        repos.parties.saveParty(testParty(id = "party-2", name = "Bharat Supplies", addresses = emptyList()).copy(type = PartyType.SUPPLIER, phone = null, gstin = null))
        repos.catalog.saveCategory(testCategory())
        repos.catalog.saveUnit(testUnit())
        repos.catalog.saveGodown(testGodown())
        repos.catalog.saveItem(testItem())
        repos.catalog.saveItem(testItem(id = "item-2", name = "Assembly", sku = "SVC-1", isService = true, trackInventory = false))
        // Sales on three days, the middle one left as a draft; one purchase from party-2.
        listOf("s1" to 10, "s2" to 12, "s3" to 14).forEach { (id, day) ->
            repos.vouchers.saveDraft(testVoucher(id = id, date = LocalDate(2026, 5, day), lines = listOf(testLine(id = "$id-l", itemId = "item-1")), charges = emptyList()))
            if (id != "s2") repos.vouchers.post(id, Instant.fromEpochMilliseconds(POSTED_MILLIS + day))
        }
        repos.vouchers.saveDraft(
            testVoucher(id = "p1", type = VoucherType.PURCHASE, date = LocalDate(2026, 5, 11), partyId = "party-2", lines = listOf(testLine(id = "p1-l", itemId = "item-1", quantity = Quantity.of(10))), charges = emptyList()),
        )
        repos.vouchers.post("p1", POSTED_AT)
    }

    @AfterTest
    fun tearDown() = db.close()

    @Test
    fun voucherPageFiltersSearchesAndLimitsInSql(): Unit = runBlocking {
        val v = repos.vouchers
        assertEquals(listOf("s3", "s2", "s1"), v.observeVoucherPage("firm-1", sale, null, "", 10).first().map { it.id })
        assertEquals(listOf("s3", "s2"), v.observeVoucherPage("firm-1", sale, null, "", 2).first().map { it.id })
        assertEquals(listOf("s3", "s1"), v.observeVoucherPage("firm-1", sale, VoucherStatus.POSTED, "", 10).first().map { it.id })
        assertEquals(listOf("s2"), v.observeVoucherPage("firm-1", sale, VoucherStatus.DRAFT, "", 10).first().map { it.id })
        assertEquals(listOf("p1"), v.observeVoucherPage("firm-1", setOf(VoucherType.PURCHASE), null, "bharat", 10).first().map { it.id })
        assertEquals(3, v.observeVoucherPage("firm-1", sale, null, "acme", 10).first().size) // party name
        val number = v.findVoucher("s1")!!.number!!
        assertEquals(listOf("s1"), v.observeVoucherPage("firm-1", sale, null, number, 10).first().map { it.id })
        assertEquals(listOf("s1"), v.observeVoucherPage("firm-1", sale, null, " $number ", 10).first().map { it.id })

        val stats = v.observeVoucherStats("firm-1", sale, LocalDate(2026, 4, 1), LocalDate(2027, 3, 31)).first()
        assertEquals(VoucherTally(2, Money.of(47_600L)), stats[VoucherStatus.POSTED])
        assertEquals(1, stats[VoucherStatus.DRAFT]?.count)
        assertEquals(1, v.observeVoucherStats("firm-1", sale, LocalDate(2026, 5, 13), LocalDate(2026, 5, 31)).first()[VoucherStatus.POSTED]?.count)
    }

    @Test
    fun partyAndItemPagesMatchTheFullLists(): Unit = runBlocking {
        val all = PartyType.entries.toSet()
        assertEquals(listOf("party-1", "party-2"), repos.parties.observePartyPage("firm-1", all, "", 10).first().map { it.id })
        assertEquals(listOf("party-1"), repos.parties.observePartyPage("firm-1", all, "", 1).first().map { it.id })
        assertEquals(listOf("party-2"), repos.parties.observePartyPage("firm-1", setOf(PartyType.SUPPLIER, PartyType.BOTH), "", 10).first().map { it.id })
        assertEquals(listOf("party-1"), repos.parties.observePartyPage("firm-1", all, "98765", 10).first().map { it.id })
        assertEquals(listOf("item-2", "item-1"), repos.catalog.observeItemPage("firm-1", null, "", 10).first().map { it.id })
        assertEquals(listOf("item-2"), repos.catalog.observeItemPage("firm-1", true, "", 10).first().map { it.id })
        assertEquals(listOf("item-1"), repos.catalog.observeItemPage("firm-1", null, "wid-0", 10).first().map { it.id })
    }

    @Test
    fun accountPageAndAggregatesMatchTheHistory(): Unit = runBlocking {
        val ar = repos.ledger.findAccount("firm-1", AccountCode.AR)!!
        val history = repos.ledger.observeAccountTransactions(ar.id).first()
        assertEquals(history.asReversed().take(1), repos.ledger.observeAccountTransactionsPage(ar.id, 1).first())
        val net = history.fold(Money.ZERO) { t, x -> t + x.entry.debit - x.entry.credit }
        assertEquals(net to Money.ZERO, repos.ledger.observeAccountNet(ar.id).first())

        val today = LocalDate(2026, 6, 1)
        val parties = repos.parties.observeParties("firm-1").first()
        val moves = repos.ledger.observePartyMovements("firm-1").first().groupBy { it.partyId }
        val expected = parties.associate { p ->
            val opening = if (p.openingBalanceType == app.openbill.core.domain.model.OpeningBalanceType.DEBIT) p.openingBalance else -p.openingBalance
            p.id to PartyAgeing.of(opening, moves[p.id].orEmpty(), today).total
        }
        assertEquals(expected, repos.ledger.observePartyBalances("firm-1", today).first())

        val movements = repos.stock.observeFirmMovements("firm-1").first()
        val positions = movements.groupBy { it.itemId }.mapValues { (_, own) -> StockValuation.position(own) }
        assertEquals(positions, repos.stock.observeStockPositions("firm-1").first())
    }

    @Test
    fun reportsLoadedPerKindMatchTheFullSnapshot(): Unit = runBlocking {
        // A mid-month start puts movements before the period (opening stock) and inside it.
        listOf(ReportPeriod.month("2026-05"), ReportPeriod(LocalDate(2026, 5, 11), LocalDate(2026, 5, 13))).forEach { period ->
            val full = repos.loadReportData("firm-1", period)
            ReportKind.entries.forEach { kind ->
                val party = if (kind.needsParty) "party-1" else null
                assertEquals(Reports.run(kind, full, party), Reports.run(kind, repos.loadReportData("firm-1", period, kind), party), "${kind.key} $period")
            }
            listOf(true, false).forEach { sales ->
                assertEquals(Reports.register(full, sales).toCsv(), buildString { repos.writeRegisterCsv("firm-1", period, sales, this) }, "register $sales $period")
            }
            assertEquals(TallyXml.export(full), buildString { repos.writeTallyXml("firm-1", period, this) }, "tally $period")
        }
    }

    @Test
    fun progressIsMonotonicAndReachesTheTotal(): Unit = runBlocking {
        val period = ReportPeriod.month("2026-05")
        fun check(events: List<Progress>) {
            val counted = events.filter { it.total != null }
            assertTrue(counted.isNotEmpty())
            counted.groupBy { it.stage }.forEach { (stage, list) ->
                assertEquals(list.map { it.done }.sorted(), list.map { it.done }, stage)
                assertEquals(list.last().total, list.last().done, "$stage ends at 100%")
            }
        }
        check(buildList { buildString { repos.writeTallyXml("firm-1", period, this) { add(it) } } })
        check(buildList { buildString { repos.writeRegisterCsv("firm-1", period, true, this) { add(it) } } })
        check(buildList { repos.loadReportData("firm-1", period, ReportKind.STOCK_SUMMARY) { add(it) } })
        check(buildList { repos.loadReportData("firm-1", period, ReportKind.DAY_BOOK) { add(it) } })
    }
}
