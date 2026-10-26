package app.openbill.ui

import app.openbill.core.domain.model.Batch
import app.openbill.core.domain.model.StockMovement
import app.openbill.core.model.Quantity
import app.openbill.ui.inventory.Inventory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

class BatchPickerTest {
    private fun mv(batch: String, qty: Int, godown: String = "G1") = StockMovement(
        id = "M$batch$qty", firmId = "F1", itemId = "I1", godownId = godown, batchId = batch, voucherId = "V",
        date = LocalDate(2026, 4, 1), quantity = Quantity.of(qty.toLong()), createdAt = Instant.fromEpochMilliseconds(0),
    )

    @Test
    fun fefoSkipsEmptyAndOtherGodownBatchesAndPutsNoExpiryLast() {
        val batches = listOf(
            Batch("B1", "I1", "G1", "LATE", LocalDate(2027, 6, 1)),
            Batch("B2", "I1", "G1", "NOEXP", null),
            Batch("B3", "I1", "G1", "SOON", LocalDate(2026, 12, 1)),
            Batch("B4", "I1", "G1", "GONE", LocalDate(2026, 5, 1)),
            Batch("B5", "I1", "G2", "ELSEWHERE", LocalDate(2026, 5, 1)),
        )
        val moves = listOf(mv("B1", 5), mv("B2", 3), mv("B3", 10), mv("B3", -4), mv("B4", 2), mv("B4", -2), mv("B5", 7, "G2"))
        val picked = Inventory.fefo(batches, moves, "G1")
        assertEquals(listOf("SOON", "LATE", "NOEXP"), picked.map { it.first.batchNumber })
        assertEquals(Quantity.of(6), picked.first().second)
        assertEquals(4, Inventory.fefo(batches, moves, null).size)
    }
}
