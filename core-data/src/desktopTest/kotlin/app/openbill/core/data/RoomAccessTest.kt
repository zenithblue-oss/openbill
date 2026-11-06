package app.openbill.core.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.domain.model.Access
import app.openbill.core.domain.model.AppUser
import app.openbill.core.domain.model.PermissionDeniedException
import app.openbill.core.domain.model.Role
import app.openbill.core.domain.report.ReportKind
import app.openbill.core.domain.report.ReportPeriod
import app.openbill.core.domain.report.Reports
import app.openbill.core.domain.report.loadReportData
import app.openbill.core.domain.repository.Repositories
import app.openbill.core.model.Money
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate

/** Roles are enforced in the repositories (not only by hidden buttons) and every write names its user. */
class RoomAccessTest {
    private lateinit var db: OpenBillDatabase
    private lateinit var repos: Repositories
    private val cashier = AppUser("u-cash", "Ravi", Role.CASHIER, pinHash = "1:00:00", createdAt = CREATED_AT)
    private val accountant = AppUser("u-ca", "Meera CA", Role.ACCOUNTANT, pinHash = "1:00:00", createdAt = CREATED_AT)

    @BeforeTest
    fun setUp() = runBlocking {
        Access.current = null
        db = Room.inMemoryDatabaseBuilder<OpenBillDatabase>().setDriver(BundledSQLiteDriver()).build()
        repos = db.roomRepositories()
        repos.firms.saveFirm(testFirm())
        repos.firms.saveSettings(testSettings(negativeStockAllowed = true))
        repos.firms.saveFinancialYear(testFinancialYear())
        repos.parties.saveParty(testParty(addresses = emptyList()))
        repos.catalog.saveCategory(testCategory())
        repos.catalog.saveUnit(testUnit())
        repos.catalog.saveItem(testItem())
        repos.users.saveUser(cashier)
        repos.users.saveUser(accountant)
    }

    @AfterTest
    fun tearDown() {
        Access.current = null
        db.close()
    }

    private fun service(id: String) = testVoucher(id = id, lines = listOf(testLine(id = "$id-l")), charges = emptyList())

    @Test
    fun cashierBillsButCannotCancelChangePricesOrSettings(): Unit = runBlocking {
        Access.current = cashier
        repos.vouchers.saveDraft(service("v-1"))
        repos.vouchers.post("v-1", POSTED_AT)
        assertFailsWith<PermissionDeniedException> { repos.vouchers.cancel("v-1", "typo", CANCELLED_AT) }

        val itemLine = testLine(id = "v-2-l", itemId = "item-1")
        repos.vouchers.saveDraft(testVoucher(id = "v-2", lines = listOf(itemLine), charges = emptyList()))
        repos.vouchers.saveDraft(testVoucher(id = "v-3", lines = listOf(itemLine.copy(id = "v-3-l", rate = Money.of(9_500))), charges = emptyList()))
        assertFailsWith<PermissionDeniedException>("a rate other than the item's prices") {
            repos.vouchers.saveDraft(testVoucher(id = "v-4", lines = listOf(itemLine.copy(id = "v-4-l", rate = Money.of(9_000))), charges = emptyList()))
        }
        assertFailsWith<PermissionDeniedException>("a line discount is a price change too") {
            repos.vouchers.saveDraft(testVoucher(id = "v-5", lines = listOf(itemLine.copy(id = "v-5-l", discount = Money.of(100))), charges = emptyList()))
        }
        assertFailsWith<PermissionDeniedException> { repos.catalog.saveItem(testItem().copy(salePrice = Money.of(1))) }
        assertFailsWith<PermissionDeniedException> { repos.firms.saveSettings(testSettings(negativeStockAllowed = false)) }
        assertFailsWith<PermissionDeniedException> { repos.parties.archiveParty("party-1", ARCHIVED_AT) }
        assertFailsWith<PermissionDeniedException> { repos.users.saveUser(cashier.copy(role = Role.MANAGER)) }
        assertFailsWith<PermissionDeniedException> { repos.firms.eraseFirm("firm-1") }
        repos.parties.saveParty(testParty(id = "party-2", addresses = emptyList()))

        Access.current = null
        repos.vouchers.saveDraft(testVoucher(id = "v-6", lines = listOf(itemLine.copy(id = "v-6-l", rate = Money.of(1))), charges = emptyList()))
        repos.vouchers.cancel("v-1", "typo", CANCELLED_AT)
    }

    @Test
    fun cashierGetsSystemDiscountAccountButCannotSaveAccounts(): Unit = runBlocking {
        Access.current = cashier
        val type = app.openbill.core.domain.model.VoucherType.PAYMENT_IN
        val made = app.openbill.core.domain.usecase.MoneyVouchers.discountAccount(repos.ledger, "firm-1", type, { "acc-d" }, CREATED_AT)
        assertEquals("Discount allowed", made.name)
        assertEquals("acc-d", app.openbill.core.domain.usecase.MoneyVouchers.discountAccount(repos.ledger, "firm-1", type, { "acc-x" }, CREATED_AT).id, "found, not made twice")
        assertFailsWith<PermissionDeniedException> { repos.ledger.saveAccount(made.copy(name = "Renamed")) }
        assertFailsWith<IllegalArgumentException>("never overwrites") { repos.ledger.createSystemAccount(made.copy(name = "Renamed")) }
    }

    @Test
    fun cashierBillsForeignCurrencyAtConvertedListPrice(): Unit = runBlocking {
        val export = app.openbill.core.domain.model.ExportDetails(
            app.openbill.core.domain.model.ExportType.EXPORT_UNDER_LUT,
            currency = "USD",
            exchangeRate = app.openbill.core.model.Quantity.parse("83.25"),
        )
        // ₹100.00 at ₹83.25 = $1.20 (₹99.90 back in rupees); wholesale ₹95.00 = $1.14.
        fun fx(id: String, usd: Long, rate: String = "83.25", ref: String? = null, type: app.openbill.core.domain.model.VoucherType = app.openbill.core.domain.model.VoucherType.SALE) = testVoucher(
            id = id,
            type = type,
            lines = listOf(testLine(id = "$id-l", itemId = "item-1").copy(rate = Money.of(usd * 8325 / 100), foreignRate = Money.of(usd))),
            charges = emptyList(),
        ).copy(export = export.copy(exchangeRate = app.openbill.core.model.Quantity.parse(rate)), referenceVoucherId = ref)
        // A manager or owner sets the rate on a draft and on an order.
        repos.vouchers.saveDraft(fx("v-usd", 120))
        repos.vouchers.saveDraft(fx("so-usd", 120, type = app.openbill.core.domain.model.VoucherType.SALES_ORDER))

        Access.current = cashier
        repos.vouchers.saveDraft(fx("v-usd", 114))
        assertFailsWith<PermissionDeniedException>("a different dollar rate is a price change") { repos.vouchers.saveDraft(fx("v-usd", 121)) }
        assertFailsWith<PermissionDeniedException>("the exchange rate is a price too") { repos.vouchers.saveDraft(fx("v-usd", 111, rate = "90")) }
        assertFailsWith<PermissionDeniedException>("a cashier sets no rate of their own") { repos.vouchers.saveDraft(fx("v-new", 120)) }
        repos.vouchers.saveDraft(fx("v-from-order", 120, ref = "so-usd"))
        assertFailsWith<PermissionDeniedException>("not another rate than the order's") { repos.vouchers.saveDraft(fx("v-from-order-2", 119, rate = "84", ref = "so-usd")) }
    }

    @Test
    fun draftCreationIsLoggedOnce(): Unit = runBlocking {
        Access.current = cashier
        repos.vouchers.saveDraft(service("v-1"))
        repos.vouchers.saveDraft(service("v-1"))
        Access.current = null
        val events = repos.audit.observeRecent("firm-1", 100).first().filter { it.action == "voucher.draft_created" }
        assertEquals(listOf("u-cash"), events.map { it.actor })
        assertEquals("v-1", events.single().voucherId)
    }

    @Test
    fun accountantIsReadOnly(): Unit = runBlocking {
        Access.current = accountant
        assertFailsWith<PermissionDeniedException> { repos.vouchers.saveDraft(service("v-1")) }
        assertFailsWith<PermissionDeniedException> { repos.parties.saveParty(testParty(id = "party-2", addresses = emptyList())) }
        assertFailsWith<PermissionDeniedException> { repos.ledger.saveAccount(app.openbill.core.domain.model.Account("a", "firm-1", "Bank", app.openbill.core.domain.model.AccountType.BANK, createdAt = CREATED_AT, updatedAt = CREATED_AT)) }
        val data = repos.loadReportData("firm-1", ReportPeriod(LocalDate(2026, 4, 1), LocalDate(2027, 3, 31)), ReportKind.DAY_BOOK)
        assertTrue(Reports.dayBook(data).rows.isEmpty(), "reads still work")
    }

    @Test
    fun auditNamesTheUserAndSalesByUserGroupsByPoster(): Unit = runBlocking {
        repos.vouchers.saveDraft(service("v-own"))
        repos.vouchers.post("v-own", POSTED_AT)
        Access.current = cashier
        repos.vouchers.saveDraft(service("v-1"))
        repos.vouchers.post("v-1", POSTED_AT)
        repos.vouchers.saveDraft(service("v-2"))
        repos.vouchers.post("v-2", POSTED_AT)
        Access.current = null

        val events = repos.audit.observeRecent("firm-1", 100).first()
        assertEquals(listOf("owner", "u-cash", "u-cash"), events.filter { it.action == "voucher.posted" }.map { it.actor.orEmpty() }.sorted())
        assertTrue(events.filter { it.action == "party.created" }.all { it.actor == "owner" })

        val period = ReportPeriod(LocalDate(2026, 4, 1), LocalDate(2027, 3, 31))
        val sales = Reports.run(ReportKind.SALES_BY_USER, repos.loadReportData("firm-1", period, ReportKind.SALES_BY_USER))
        assertEquals(listOf("Owner" to "1", "Ravi" to "2"), sales.rows.map { it[0] to it[1] })
        assertEquals("3", sales.totals!![1])
        assertEquals(sales.rows.last().last(), "476.00", "Ravi: two of 238.00")
        val book = Reports.run(ReportKind.DAY_BOOK_BY_USER, repos.loadReportData("firm-1", period, ReportKind.DAY_BOOK_BY_USER))
        assertEquals(listOf("Owner", "Ravi", "Ravi"), book.rows.map { it[0] })
    }

    @Test
    fun oneFixedOwnerThatStaysActive(): Unit = runBlocking {
        val owner = repos.users.observeUsers().first().first()
        assertEquals(Access.OWNER_ID, owner.id)
        assertEquals(Role.OWNER, owner.role)
        assertFailsWith<IllegalArgumentException> { repos.users.saveUser(owner.copy(active = false)) }
        assertFailsWith<IllegalArgumentException> { repos.users.saveUser(cashier.copy(id = "u-2", role = Role.OWNER)) }
        assertFailsWith<IllegalArgumentException> { repos.users.saveUser(cashier.copy(id = "u-3", pinHash = null)) }
        repos.users.saveUser(cashier.copy(active = false))
        assertEquals(false, repos.users.observeUsers().first().single { it.id == "u-cash" }.active)
        // A hand-edited row cannot mint a second owner.
        db.userDao().upsert(app.openbill.core.database.UserEntity("u-x", "X", "owner", true, "1:00:00", 0, 0))
        assertEquals(Role.ACCOUNTANT, repos.users.observeUsers().first().single { it.id == "u-x" }.role)
    }
}
