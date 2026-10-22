package app.openbill.core.domain.service

import app.openbill.core.domain.model.FirmSettings
import app.openbill.core.domain.model.Item
import app.openbill.core.domain.model.StockDirection
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherLine
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import app.openbill.core.model.Rate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

class StockMovementDerivationTest {
    private val createdAt = Instant.parse("2026-05-10T08:00:00Z")
    private val postedAt = Instant.parse("2026-05-10T09:00:00Z")
    private val cancelledAt = Instant.parse("2026-05-20T11:00:00Z")
    private val voucherDate = LocalDate(2026, 5, 10)
    private val defaultSettings = FirmSettings(firmId = FIRM_ID, defaultGodownId = GODOWN_ID)
    private val trackedItem = item(ITEM_ID)
    private val items = mapOf(ITEM_ID to trackedItem)

    @Test
    fun saleIsOutwardAndPurchaseIsInward(): Unit {
        val sale = derive(VoucherType.SALE, line(quantity = Quantity.of(5)))
        assertEquals(listOf(Quantity.of(5) * -1L), sale.map { it.quantity })

        val purchase = derive(VoucherType.PURCHASE, line(quantity = Quantity.of(5)))
        assertEquals(listOf(Quantity.of(5)), purchase.map { it.quantity })
    }

    @Test
    fun creditNoteIsInwardAndDebitNoteIsOutward(): Unit {
        val creditNote = derive(VoucherType.CREDIT_NOTE, line(quantity = Quantity.of(3)))
        assertEquals(listOf(Quantity.of(3)), creditNote.map { it.quantity })

        val debitNote = derive(VoucherType.DEBIT_NOTE, line(quantity = Quantity.of(3)))
        assertEquals(listOf(Quantity.of(3) * -1L), debitNote.map { it.quantity })
    }

    @Test
    fun adjustmentUsesExplicitLineDirection(): Unit {
        val inward = derive(
            VoucherType.STOCK_ADJUSTMENT,
            line(stockDirection = StockDirection.INWARD),
        )
        assertEquals(listOf(Quantity.of(2)), inward.map { it.quantity })

        val outward = derive(
            VoucherType.STOCK_ADJUSTMENT,
            line(stockDirection = StockDirection.OUTWARD),
        )
        assertEquals(listOf(Quantity.of(2) * -1L), outward.map { it.quantity })
    }

    @Test
    fun adjustmentWithoutDirectionIsRejected(): Unit {
        assertFailsWith<IllegalArgumentException> {
            derive(VoucherType.STOCK_ADJUSTMENT, line(stockDirection = null))
        }
    }

    @Test
    fun servicesAndUntrackedItemsAreSkipped(): Unit {
        val service = item("service-1", isService = true, trackInventory = false)
        val untracked = item("untracked-1", trackInventory = false)
        val voucher = voucher(
            VoucherType.SALE,
            listOf(
                line(id = "line-1", itemId = service.id, lineNumber = 1),
                line(id = "line-2", itemId = untracked.id, lineNumber = 2),
                line(id = "line-3", itemId = null, lineNumber = 3),
            ),
        )

        val movements = StockMovementDerivation().derivePosted(
            voucher,
            mapOf(service.id to service, untracked.id to untracked),
            defaultSettings,
            postedAt,
        )

        assertTrue(movements.isEmpty())
    }

    @Test
    fun missingGodownForTrackedItemIsRejected(): Unit {
        assertFailsWith<IllegalArgumentException> {
            StockMovementDerivation().derivePosted(
                voucher(VoucherType.SALE, listOf(line(godownId = null))),
                items,
                FirmSettings(firmId = FIRM_ID, defaultGodownId = null),
                postedAt,
            )
        }
    }

    @Test
    fun lineGodownOverridesDefaultAndBatchAndLineIdArePreserved(): Unit {
        val line = line(godownId = "godown-2", batchId = "batch-1")
        val movement = derive(VoucherType.PURCHASE, line).single()

        assertEquals("godown-2", movement.godownId)
        assertEquals("batch-1", movement.batchId)
        assertEquals(line.id, movement.voucherLineId)
        assertEquals(ITEM_ID, movement.itemId)
        assertEquals(voucherDate, movement.date)
        assertEquals(postedAt, movement.createdAt)
    }

    @Test
    fun defaultGodownIsUsedWhenLineHasNone(): Unit {
        val movement = derive(VoucherType.PURCHASE, line(godownId = null)).single()
        assertEquals(GODOWN_ID, movement.godownId)
    }

    @Test
    fun untrackedVoucherTypesAndTransfersProduceNoMovements(): Unit {
        for (type in listOf(
            VoucherType.STOCK_TRANSFER,
            VoucherType.QUOTATION,
            VoucherType.PROFORMA,
            VoucherType.PAYMENT_IN,
            VoucherType.PAYMENT_OUT,
            VoucherType.EXPENSE,
        )) {
            assertTrue(derive(type, line()).isEmpty(), "Expected no movements for $type")
        }
    }

    @Test
    fun zeroQuantityLinesAreSkipped(): Unit {
        assertTrue(derive(VoucherType.PURCHASE, line(quantity = Quantity.of(0))).isEmpty())
    }

    @Test
    fun cancellationReversesEveryOriginalMovementAtCancellationDate(): Unit {
        val source = voucher(
            VoucherType.SALE,
            listOf(
                line(quantity = Quantity.of(5), godownId = "godown-1", batchId = "batch-1"),
                line(
                    id = "line-2",
                    lineNumber = 2,
                    quantity = Quantity.of(2),
                    godownId = "godown-2",
                ),
            ),
        )
        val original = StockMovementDerivation().derivePosted(
            source,
            items,
            defaultSettings,
            postedAt,
        )

        val reversals = StockMovementDerivation().deriveCancellation(source, original, cancelledAt)

        assertEquals(original.size, reversals.size)
        original.zip(reversals).forEach { (before, after) ->
            assertEquals(before.voucherId, after.voucherId)
            assertEquals(before.voucherLineId, after.voucherLineId)
            assertEquals(before.itemId, after.itemId)
            assertEquals(before.godownId, after.godownId)
            assertEquals(before.batchId, after.batchId)
            assertEquals(Quantity.of(0), before.quantity + after.quantity)
            assertEquals(LocalDate(2026, 5, 20), after.date)
            assertEquals(cancelledAt, after.createdAt)
            assertTrue(before.id != after.id)
        }
    }

    @Test
    fun cancellationIgnoresMovementsFromOtherVouchers(): Unit {
        val source = voucher(VoucherType.PURCHASE, listOf(line()))
        val original = StockMovementDerivation().derivePosted(
            source,
            items,
            defaultSettings,
            postedAt,
        )
        val otherVoucherMovement = original.single().copy(voucherId = "other-voucher")

        assertTrue(
            StockMovementDerivation()
                .deriveCancellation(source, listOf(otherVoucherMovement), cancelledAt)
                .isEmpty(),
        )
    }

    @Test
    fun unknownItemIsRejected(): Unit {
        assertFailsWith<IllegalArgumentException> {
            StockMovementDerivation().derivePosted(
                voucher(VoucherType.PURCHASE, listOf(line(itemId = "missing-item"))),
                items,
                defaultSettings,
                postedAt,
            )
        }
    }

    private fun derive(type: VoucherType, line: VoucherLine) = StockMovementDerivation()
        .derivePosted(voucher(type, listOf(line)), items, defaultSettings, postedAt)

    private fun voucher(type: VoucherType, lines: List<VoucherLine>): Voucher = Voucher(
        id = VOUCHER_ID,
        firmId = FIRM_ID,
        financialYearId = "fy-1",
        type = type,
        date = voucherDate,
        placeOfSupplyStateCode = "27",
        lines = lines,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun line(
        id: String = "line-1",
        lineNumber: Int = 1,
        itemId: String? = ITEM_ID,
        quantity: Quantity = Quantity.of(2),
        godownId: String? = GODOWN_ID,
        batchId: String? = null,
        stockDirection: StockDirection? = null,
    ): VoucherLine = VoucherLine(
        id = id,
        lineNumber = lineNumber,
        itemId = itemId,
        description = "Steel Widget",
        quantity = quantity,
        rate = Money.of(10_000),
        godownId = godownId,
        batchId = batchId,
        stockDirection = stockDirection,
    )

    private fun item(
        id: String,
        isService: Boolean = false,
        trackInventory: Boolean = true,
    ): Item = Item(
        id = id,
        firmId = FIRM_ID,
        name = "Item $id",
        unitId = "unit-1",
        isService = isService,
        trackInventory = trackInventory,
        taxRate = Rate.ofBasisPoints(1_800),
        salePrice = Money.of(10_000),
        purchasePrice = Money.of(8_000),
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private companion object {
        const val FIRM_ID = "firm-1"
        const val ITEM_ID = "item-1"
        const val GODOWN_ID = "godown-1"
        const val VOUCHER_ID = "voucher-1"
    }
}
