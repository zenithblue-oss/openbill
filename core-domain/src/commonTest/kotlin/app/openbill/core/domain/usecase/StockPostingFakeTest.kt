package app.openbill.core.domain.usecase

import app.openbill.core.domain.fake.InMemoryCatalogRepository
import app.openbill.core.domain.fake.InMemoryFirmRepository
import app.openbill.core.domain.fake.InMemoryStockRepository
import app.openbill.core.domain.fake.InMemoryVoucherRepository
import app.openbill.core.domain.model.FirmSettings
import app.openbill.core.domain.model.Godown
import app.openbill.core.domain.model.Item
import app.openbill.core.domain.model.StockDirection
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class StockPostingFakeTest {
    private val createdAt = Instant.parse("2026-05-10T08:00:00Z")
    private val postedAt = Instant.parse("2026-05-10T09:00:00Z")
    private val cancelledAt = Instant.parse("2026-05-20T10:00:00Z")

    @Test
    fun salePostingThenCancellationReturnsBalanceToZero(): Unit = runBlocking {
        val fixture = fixture(negativeStockAllowed = true)
        val draft = voucher(VoucherType.SALE)
        fixture.vouchers.saveDraft(draft)

        PostVoucher(fixture.vouchers)(draft.id, postedAt)
        assertEquals(Quantity.of(2) * -1L, fixture.balance())

        CancelVoucher(fixture.vouchers)(draft.id, "Customer request", cancelledAt)
        assertEquals(Quantity.of(0), fixture.balance())
        val movements = fixture.stock.observeMovements(draft.id).first()
        assertEquals(2, movements.size)
        assertEquals(Quantity.of(0), movements.map { it.quantity }.reduce { a, b -> a + b })
    }

    @Test
    fun purchasePostingAndCancellationAreMirrored(): Unit = runBlocking {
        val fixture = fixture(negativeStockAllowed = false)
        val draft = voucher(VoucherType.PURCHASE)
        fixture.vouchers.saveDraft(draft)

        PostVoucher(fixture.vouchers)(draft.id, postedAt)
        assertEquals(Quantity.of(2), fixture.balance())

        CancelVoucher(fixture.vouchers)(draft.id, "Supplier request", cancelledAt)
        assertEquals(Quantity.of(0), fixture.balance())
        assertEquals(
            listOf(Quantity.of(2), Quantity.of(2) * -1L),
            fixture.stock.observeMovements(draft.id).first().map { it.quantity },
        )
    }

    @Test
    fun negativeStockRejectionLeavesDraftWithoutMovements(): Unit = runBlocking {
        val fixture = fixture(negativeStockAllowed = false)
        val draft = voucher(VoucherType.SALE)
        fixture.vouchers.saveDraft(draft)

        assertFailsWith<IllegalArgumentException> {
            PostVoucher(fixture.vouchers)(draft.id, postedAt)
        }

        val stored = fixture.vouchers.findVoucher(draft.id)
        assertEquals(VoucherStatus.DRAFT, stored?.status)
        assertNull(stored?.number)
        assertNull(stored?.postedAt)
        assertTrue(fixture.stock.observeMovements(draft.id).first().isEmpty())
    }

    @Test
    fun adjustmentDirectionControlsTheSign(): Unit = runBlocking {
        val fixture = fixture(negativeStockAllowed = false)
        val inward = voucher(
            VoucherType.STOCK_ADJUSTMENT,
            id = "adjustment-in",
            stockDirection = StockDirection.INWARD,
        )
        val outward = voucher(
            VoucherType.STOCK_ADJUSTMENT,
            id = "adjustment-out",
            stockDirection = StockDirection.OUTWARD,
        )
        fixture.vouchers.saveDraft(inward)
        fixture.vouchers.saveDraft(outward)

        PostVoucher(fixture.vouchers)(inward.id, postedAt)
        PostVoucher(fixture.vouchers)(outward.id, postedAt + 1.minutes)

        assertEquals(Quantity.of(0), fixture.balance())
    }

    private suspend fun fixture(negativeStockAllowed: Boolean): Fixture {
        val stock = InMemoryStockRepository()
        val catalog = InMemoryCatalogRepository()
        val firms = InMemoryFirmRepository()
        catalog.saveGodown(
            Godown(
                id = GODOWN_ID,
                firmId = FIRM_ID,
                name = "Main",
                isDefault = true,
                createdAt = createdAt,
                updatedAt = createdAt,
            ),
        )
        catalog.saveItem(
            Item(
                id = ITEM_ID,
                firmId = FIRM_ID,
                name = "Steel Widget",
                unitId = "unit-1",
                trackInventory = true,
                salePrice = Money.of(10_000),
                purchasePrice = Money.of(8_000),
                createdAt = createdAt,
                updatedAt = createdAt,
            ),
        )
        firms.saveSettings(
            FirmSettings(
                firmId = FIRM_ID,
                defaultGodownId = GODOWN_ID,
                negativeStockAllowed = negativeStockAllowed,
            ),
        )
        val vouchers = InMemoryVoucherRepository(
            stock = stock,
            catalog = catalog,
            firms = firms,
        )
        return Fixture(stock, vouchers)
    }

    private suspend fun Fixture.balance(): Quantity =
        stock.balance(FIRM_ID, ITEM_ID, GODOWN_ID)

    private fun voucher(
        type: VoucherType,
        id: String = "voucher-1",
        stockDirection: StockDirection? = null,
    ): Voucher = Voucher(
        id = id,
        firmId = FIRM_ID,
        financialYearId = "fy-1",
        type = type,
        date = LocalDate(2026, 5, 10),
        placeOfSupplyStateCode = "27",
        partyId = "party-1",
        lines = listOf(
            VoucherLine(
                id = "line-$id",
                lineNumber = 1,
                itemId = ITEM_ID,
                description = "Steel Widget",
                quantity = Quantity.of(2),
                rate = Money.of(10_000),
                taxRate = Rate.ofBasisPoints(1_800),
                taxable = Money.of(20_000),
                tax = Money.of(3_600),
                total = Money.of(23_600),
                stockDirection = stockDirection,
            ),
        ),
        subtotal = Money.of(20_000),
        taxable = Money.of(20_000),
        tax = Money.of(3_600),
        total = Money.of(23_600),
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private data class Fixture(
        val stock: InMemoryStockRepository,
        val vouchers: InMemoryVoucherRepository,
    )

    private companion object {
        const val FIRM_ID = "firm-1"
        const val ITEM_ID = "item-1"
        const val GODOWN_ID = "godown-1"
    }
}
