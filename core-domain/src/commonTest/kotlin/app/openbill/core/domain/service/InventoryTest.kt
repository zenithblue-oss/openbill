package app.openbill.core.domain.service

import app.openbill.core.domain.fake.InMemoryCatalogRepository
import app.openbill.core.domain.fake.InMemoryFirmRepository
import app.openbill.core.domain.fake.InMemoryStockRepository
import app.openbill.core.domain.fake.InMemoryVoucherRepository
import app.openbill.core.domain.model.FirmSettings
import app.openbill.core.domain.model.Item
import app.openbill.core.domain.model.SerialStatus
import app.openbill.core.domain.model.StockDirection
import app.openbill.core.domain.model.StockMovement
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherLine
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.usecase.StockLineInput
import app.openbill.core.domain.usecase.StockVouchers
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate

class InventoryTest {
    private val t0 = Instant.parse("2026-05-10T08:00:00Z")
    private val item = Item(id = "i", firmId = "f", name = "Phone", unitId = "u", createdAt = t0, updatedAt = t0)
    private val serialItem = item.copy(trackSerial = true)
    private val batchItem = item.copy(trackBatch = true)

    private fun voucher(type: VoucherType, vararg lines: VoucherLine) = Voucher(
        id = "v", firmId = "f", financialYearId = "fy", type = type, date = LocalDate(2026, 5, 10),
        placeOfSupplyStateCode = "27", lines = lines.toList(), createdAt = t0, updatedAt = t0,
    )

    private fun line(qty: Long, serials: List<String> = emptyList(), batch: String? = null, n: Int = 1) = VoucherLine(
        id = "l$n", lineNumber = n, itemId = "i", description = "Phone", quantity = Quantity.of(qty),
        rate = Money.ZERO, godownId = "g", serialNumbers = serials, batchNumber = batch,
    )

    @Test
    fun serialCountMustMatchQuantityAndBeUniqueAcrossLines() {
        val items = mapOf("i" to serialItem)
        InventoryRules.validate(voucher(VoucherType.PURCHASE, line(2, listOf("A", "B"))), items)
        assertFailsWith<IllegalArgumentException> {
            InventoryRules.validate(voucher(VoucherType.PURCHASE, line(2, listOf("A"))), items)
        }
        assertFailsWith<IllegalArgumentException> {
            InventoryRules.validate(voucher(VoucherType.SALE, line(1, listOf("A")), line(1, listOf("A"), n = 2)), items)
        }
    }

    @Test
    fun batchNumberRequiredForBatchItems() {
        val items = mapOf("i" to batchItem)
        InventoryRules.validate(voucher(VoucherType.PURCHASE, line(1, batch = "B1")), items)
        assertFailsWith<IllegalArgumentException> {
            InventoryRules.validate(voucher(VoucherType.PURCHASE, line(1)), items)
        }
    }

    @Test
    fun serialTransitionsRejectDuplicatesAndUnknownSales() {
        assertEquals(SerialStatus.IN_STOCK, InventoryRules.serialTransition("P", "A", null, inward = true, allowUnknown = false))
        assertEquals(SerialStatus.IN_STOCK, InventoryRules.serialTransition("P", "A", SerialStatus.OUT, inward = true, allowUnknown = false))
        assertFailsWith<IllegalArgumentException> {
            InventoryRules.serialTransition("P", "A", SerialStatus.IN_STOCK, inward = true, allowUnknown = false)
        }
        assertEquals(SerialStatus.OUT, InventoryRules.serialTransition("P", "A", SerialStatus.IN_STOCK, inward = false, allowUnknown = false))
        assertFailsWith<IllegalArgumentException> {
            InventoryRules.serialTransition("P", "A", null, inward = false, allowUnknown = false)
        }
        assertEquals(SerialStatus.OUT, InventoryRules.serialTransition("P", "A", null, inward = false, allowUnknown = true))
        assertFailsWith<IllegalArgumentException> {
            InventoryRules.serialTransition("P", "A", SerialStatus.OUT, inward = null, allowUnknown = true)
        }
    }

    private fun move(day: Int, qty: Long, cost: Long?, godown: String = "g", id: String = "m$day$qty") = StockMovement(
        id = id, firmId = "f", itemId = "i", godownId = godown, voucherId = "v", date = LocalDate(2026, 5, day),
        quantity = Quantity.of(qty), createdAt = t0, unitCost = cost?.let { Money.of(it) },
    )

    @Test
    fun movingWeightedAverage() {
        // 10 @ 100 + 10 @ 200 -> avg 150; sell 5 -> 15 left worth 2250; buy 5 @ 250 -> 20 worth 3500, avg 175.
        val v = StockValuation.value(listOf(move(1, 10, 100), move(2, 10, 200), move(3, -5, null), move(4, 5, 250)))
        assertEquals(Quantity.of(20), v.quantity)
        assertEquals(Money.of(3_500), v.value)
        assertEquals(Money.of(175), v.averageCost)
    }

    @Test
    fun transfersKeepValueAndReturnsComeInAtAverage() {
        val base = listOf(move(1, 10, 100), move(2, 10, 200))
        val transferred = base + move(3, -4, null, "g") + move(3, 4, null, "g2", id = "t2")
        assertEquals(StockValuation.value(base).value, StockValuation.value(transferred).value)
        val returned = StockValuation.value(base + move(4, -2, null) + move(5, 1, null))
        assertEquals(Money.of(150), returned.averageCost)
        assertEquals(Money.of(2_850), returned.value)
    }

    @Test
    fun lowStockFlagUsesThreshold() {
        val stock = StockValuation.summarize(listOf(item.copy(lowStockLevel = Quantity.of(5))), listOf(move(1, 5, 10)))
        assertTrue(stock.single().isLow)
        assertEquals(Quantity.of(5), stock.single().byGodown["g"])
    }

    @Test
    fun stockVouchersOpeningAdjustmentAndTransfer(): Unit = runBlocking {
        val stock = InMemoryStockRepository()
        val catalog = InMemoryCatalogRepository().apply { saveItem(item) }
        val firms = InMemoryFirmRepository().apply { saveSettings(FirmSettings(firmId = "f", defaultGodownId = "g")) }
        val vouchers = InMemoryVoucherRepository(stock = stock, catalog = catalog, firms = firms)
        val sv = StockVouchers(vouchers)
        var n = 0
        suspend fun adjust(qty: Long, dir: StockDirection, reason: String) = sv.adjust(
            "a${n++}", "f", "fy", "27", LocalDate(2026, 5, 10), reason,
            listOf(StockLineInput("i", "Phone", Quantity.of(qty), "g", dir, Money.of(1_000))), { "a${n}l$it" }, t0 + n.minutes,
        )
        adjust(10, StockDirection.INWARD, "Opening stock")
        assertFailsWith<IllegalArgumentException> { adjust(1, StockDirection.OUTWARD, " ") }
        // Negative stock is blocked by default.
        assertFailsWith<IllegalArgumentException> { adjust(11, StockDirection.OUTWARD, "Damaged") }
        adjust(2, StockDirection.OUTWARD, "Damaged")
        sv.transfer(
            "t", "f", "fy", "27", LocalDate(2026, 5, 11), null,
            listOf(StockLineInput("i", "Phone", Quantity.of(3), "g", toGodownId = "g2")), { "tl$it" }, t0 + 1.hours,
        )
        assertEquals(Quantity.of(5), stock.balance("f", "i", "g"))
        assertEquals(Quantity.of(3), stock.balance("f", "i", "g2"))
        val value = StockValuation.value(stock.observeFirmMovements("f").first())
        assertEquals(Money.of(8_000), value.value)
    }
}
