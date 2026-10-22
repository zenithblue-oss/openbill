package app.openbill.core.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.openbill.core.database.DatabaseTransactionRunner
import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.domain.model.StockDirection
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.model.Quantity
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

class RoomStockPostingTest {
    private lateinit var db: OpenBillDatabase
    private lateinit var repository: RoomVoucherRepository
    private lateinit var firms: RoomFirmRepository
    private lateinit var catalog: RoomCatalogRepository
    private lateinit var stock: RoomStockRepository

    @BeforeTest
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder<OpenBillDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()
        val runner = DatabaseTransactionRunner(db)
        repository = RoomVoucherRepository(db, runner)
        firms = RoomFirmRepository(db, runner)
        catalog = RoomCatalogRepository(db, runner)
        stock = RoomStockRepository(db)
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun salePostingCreatesNegativeStock(): Unit = runBlocking {
        seedMasterData(negativeStockAllowed = true)
        val draft = voucher(
            VoucherType.SALE,
            line = testLine(itemId = ITEM_ID, quantity = Quantity.of(5)),
        )
        repository.saveDraft(draft)

        val posted = repository.post(draft.id, POSTED_AT)

        assertEquals(VoucherStatus.POSTED, posted.status)
        assertEquals(Quantity.of(5) * -1L, balance())
        val movements = movements(draft.id)
        assertEquals(1, movements.size)
        assertEquals(Quantity.of(5) * -1L, movements.single().quantity)
        assertEquals("line-1", movements.single().voucherLineId)
        assertEquals("godown-1", movements.single().godownId)
        assertEquals(draft.date, movements.single().date)
        assertEquals(Quantity.of(5) * -1L, stock.observeBalance("firm-1", ITEM_ID, "godown-1").first())
    }

    @Test
    fun purchasePostingCreatesPositiveStock(): Unit = runBlocking {
        seedMasterData()
        val draft = voucher(
            VoucherType.PURCHASE,
            line = testLine(itemId = ITEM_ID, quantity = Quantity.of(5)),
        )
        repository.saveDraft(draft)

        repository.post(draft.id, POSTED_AT)

        assertEquals(Quantity.of(5), balance())
        assertEquals(Quantity.of(5), movements(draft.id).single().quantity)
    }

    @Test
    fun creditNotePostingCreatesPositiveStock(): Unit = runBlocking {
        seedMasterData()
        val draft = voucher(
            VoucherType.CREDIT_NOTE,
            line = testLine(itemId = ITEM_ID, quantity = Quantity.of(3)),
        )
        repository.saveDraft(draft)

        repository.post(draft.id, POSTED_AT)

        assertEquals(Quantity.of(3), balance())
        assertEquals(Quantity.of(3), movements(draft.id).single().quantity)
    }

    @Test
    fun debitNotePostingCreatesNegativeStock(): Unit = runBlocking {
        seedMasterData(negativeStockAllowed = true)
        val draft = voucher(
            VoucherType.DEBIT_NOTE,
            line = testLine(itemId = ITEM_ID, quantity = Quantity.of(3)),
        )
        repository.saveDraft(draft)

        repository.post(draft.id, POSTED_AT)

        assertEquals(Quantity.of(3) * -1L, balance())
        assertEquals(Quantity.of(3) * -1L, movements(draft.id).single().quantity)
    }

    @Test
    fun adjustmentsUseExplicitLineDirectionAndPersistIt(): Unit = runBlocking {
        seedMasterData()
        val inward = voucher(
            VoucherType.STOCK_ADJUSTMENT,
            id = "adjustment-in",
            line = testLine(
                itemId = ITEM_ID,
                quantity = Quantity.of(5),
                stockDirection = StockDirection.INWARD,
            ),
        )
        val outward = voucher(
            VoucherType.STOCK_ADJUSTMENT,
            id = "adjustment-out",
            line = testLine(
                id = "line-out",
                itemId = ITEM_ID,
                quantity = Quantity.of(2),
                stockDirection = StockDirection.OUTWARD,
            ),
        )
        repository.saveDraft(inward)
        repository.saveDraft(outward)

        repository.post(inward.id, POSTED_AT)
        repository.post(outward.id, POSTED_AT)

        assertEquals(Quantity.of(3), balance())
        assertEquals(
            StockDirection.INWARD,
            repository.findVoucher(inward.id)?.lines?.single()?.stockDirection,
        )
        assertEquals(
            "inward",
            db.voucherDao().linesFor(inward.id).single().stockDirection,
        )
        assertEquals(
            listOf(Quantity.of(5), Quantity.of(2) * -1L),
            listOf(movements(inward.id).single().quantity, movements(outward.id).single().quantity),
        )
    }

    @Test
    fun cancellationWritesExactOppositesAndReturnsBalanceToZero(): Unit = runBlocking {
        seedMasterData(negativeStockAllowed = true)
        val draft = voucher(
            VoucherType.SALE,
            line = testLine(itemId = ITEM_ID, quantity = Quantity.of(5), batchId = null),
        )
        repository.saveDraft(draft)
        repository.post(draft.id, POSTED_AT)

        val original = movements(draft.id).single()
        val cancellationAt = Instant.parse("2026-05-20T10:00:00Z")
        repository.cancel(draft.id, "Customer request", cancellationAt)

        val allMovements = movements(draft.id)
        assertEquals(2, allMovements.size)
        assertEquals(original, allMovements.first())
        val reversal = allMovements.last()
        assertEquals(original.quantity * -1L, reversal.quantity)
        assertEquals(original.godownId, reversal.godownId)
        assertEquals(original.voucherLineId, reversal.voucherLineId)
        assertEquals(
            cancellationAt.toLocalDateTime(TimeZone.UTC).date,
            reversal.date,
        )
        assertEquals(Quantity.of(0), original.quantity + reversal.quantity)
        assertEquals(Quantity.of(0), balance())
        assertEquals(VoucherStatus.CANCELLED, repository.findVoucher(draft.id)?.status)
    }

    @Test
    fun noNegativeStockRejectionRollsBackStatusNumberSequenceAuditAndStock(): Unit = runBlocking {
        seedMasterData(negativeStockAllowed = false)
        val draft = voucher(
            VoucherType.SALE,
            line = testLine(itemId = ITEM_ID, quantity = Quantity.of(5)),
        )
        repository.saveDraft(draft)

        val failure = runCatching { repository.post(draft.id, POSTED_AT) }
        assertIs<IllegalArgumentException>(failure.exceptionOrNull())

        val stored = repository.findVoucher(draft.id)
        assertEquals(VoucherStatus.DRAFT, stored?.status)
        assertNull(stored?.number)
        assertNull(stored?.postedAt)
        assertNull(db.voucherNumberSequenceDao().get("firm-1", "sale", "fy-1"))
        assertTrue(db.auditDao().observeForEntity("firm-1", "voucher", draft.id).first().isEmpty())
        assertTrue(movements(draft.id).isEmpty())
        assertEquals(Quantity.of(0), balance())
    }

    @Test
    fun cancellationReversalThatWouldGoNegativeIsRejectedAndRolledBack(): Unit = runBlocking {
        seedMasterData(negativeStockAllowed = false)
        val purchase = voucher(
            VoucherType.PURCHASE,
            id = "purchase-1",
            line = testLine(id = "purchase-line", itemId = ITEM_ID, quantity = Quantity.of(5)),
        )
        val sale = voucher(
            VoucherType.SALE,
            id = "sale-1",
            line = testLine(id = "sale-line", itemId = ITEM_ID, quantity = Quantity.of(4)),
        )
        repository.saveDraft(purchase)
        repository.saveDraft(sale)
        repository.post(purchase.id, POSTED_AT)
        repository.post(sale.id, POSTED_AT)
        assertEquals(Quantity.of(1), balance())

        val failure = runCatching {
            repository.cancel(purchase.id, "Supplier return", POSTED_AT)
        }
        assertIs<IllegalArgumentException>(failure.exceptionOrNull())

        assertEquals(VoucherStatus.POSTED, repository.findVoucher(purchase.id)?.status)
        assertEquals(1, movements(purchase.id).size)
        assertEquals(Quantity.of(1), balance())
        assertEquals(1, db.auditDao().observeForEntity("firm-1", "voucher", purchase.id).first().size)
    }

    @Test
    fun duplicatePostAndCancelDoNotDuplicateMovements(): Unit = runBlocking {
        seedMasterData(negativeStockAllowed = true)
        val draft = voucher(
            VoucherType.SALE,
            line = testLine(itemId = ITEM_ID, quantity = Quantity.of(5)),
        )
        repository.saveDraft(draft)
        repository.post(draft.id, POSTED_AT)

        assertIs<IllegalArgumentException>(
            runCatching { repository.post(draft.id, POSTED_AT) }.exceptionOrNull(),
        )
        assertEquals(1, movements(draft.id).size)

        repository.cancel(draft.id, "Customer request", POSTED_AT)

        assertIs<IllegalArgumentException>(
            runCatching { repository.cancel(draft.id, "Customer request", POSTED_AT) }
                .exceptionOrNull(),
        )
        assertEquals(2, movements(draft.id).size)
        assertEquals(Quantity.of(0), balance())
    }

    @Test
    fun servicesAndUntrackedItemsDoNotCreateMovements(): Unit = runBlocking {
        seedMasterData()
        catalog.saveItem(
            testItem(
                id = "service-1",
                name = "Installation",
                isService = true,
                trackInventory = false,
            ),
        )
        catalog.saveItem(testItem(id = "untracked-1", name = "Freight", trackInventory = false))
        val draft = voucher(
            VoucherType.SALE,
            lines = listOf(
                testLine(id = "service-line", itemId = "service-1"),
                testLine(id = "untracked-line", lineNumber = 2, itemId = "untracked-1"),
            ),
        )
        repository.saveDraft(draft)

        repository.post(draft.id, POSTED_AT)

        assertTrue(movements(draft.id).isEmpty())
        assertEquals(Quantity.of(0), balance())
    }

    private suspend fun seedMasterData(
        negativeStockAllowed: Boolean = false,
        defaultGodownId: String? = "godown-1",
    ) {
        firms.saveFirm(testFirm())
        firms.saveSettings(
            testSettings(
                defaultGodownId = defaultGodownId,
                negativeStockAllowed = negativeStockAllowed,
            ),
        )
        firms.saveFinancialYear(testFinancialYear())
        catalog.saveCategory(testCategory())
        catalog.saveUnit(testUnit())
        catalog.saveGodown(testGodown())
        catalog.saveItem(testItem())
    }

    private fun voucher(
        type: VoucherType,
        id: String = "voucher-1",
        line: app.openbill.core.domain.model.VoucherLine = testLine(itemId = ITEM_ID),
        lines: List<app.openbill.core.domain.model.VoucherLine> = listOf(line),
    ) = testVoucher(
        id = id,
        type = type,
        lines = lines,
        charges = emptyList(),
    )

    private suspend fun movements(voucherId: String) =
        db.stockMovementDao().movementsForVoucher(voucherId).map { it.toDomain() }

    private suspend fun balance(): Quantity = stock.balance("firm-1", ITEM_ID, "godown-1")

    private companion object {
        const val ITEM_ID = "item-1"
        val POSTED_AT: Instant = Instant.parse("2026-05-10T09:00:00Z")
    }
}
