package app.openbill.core.domain.csv

import app.openbill.core.domain.model.Item
import app.openbill.core.domain.model.UnitOfMeasure
import app.openbill.core.model.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class MasterCsvTest {
    private val at = Instant.fromEpochMilliseconds(0)
    private var n = 0
    private val id = { "ID${n++}" }
    private val units = listOf(UnitOfMeasure(id = "U1", firmId = "F1", name = "Piece", symbol = "pcs", createdAt = at, updatedAt = at))

    @Test
    fun partiesDryRunReportsRowNumbers() {
        val r = MasterCsv.parties("name,type,gstin\r\n\"Gupta, Sons\",customer,\r\nBad,vendor,\r\nX,both,27XX\r\n", "F1", id, at)
        assertEquals(listOf("Gupta, Sons"), r.valid.map { it.name })
        assertEquals(listOf(3, 4), r.errors.map { it.first })
    }

    @Test
    fun itemsRoundTripAndDuplicateSku() {
        val existing = Item(id = "I0", firmId = "F1", name = "Old", sku = "OLD", unitId = "U1", createdAt = at, updatedAt = at)
        val r = MasterCsv.items("name,sku,unit,gst_rate,sale_price\nNut,NUT,pcs,18,2.50\nDup,old,,0,1\nBox,BX,box,5,1\nNut2,nut,pcs,5,1\n", "F1", units, listOf(existing), id, at)
        assertEquals(listOf("Nut"), r.valid.map { it.name })
        assertEquals(listOf(3, 4, 5), r.errors.map { it.first })
        assertEquals(Money.parse("2.50"), r.valid.single().salePrice)
        val again = MasterCsv.items(MasterCsv.itemsCsv(r.valid) { "pcs" }, "F1", units, emptyList(), id, at)
        assertEquals(r.valid.map { it.copy(id = "x") }, again.valid.map { it.copy(id = "x") })
    }

    @Test
    fun itemOpeningStockColumnsAreValidatedPerRow() {
        val godowns = listOf(app.openbill.core.domain.model.Godown(id = "G1", firmId = "F1", name = "Back store", createdAt = at, updatedAt = at))
        val r = MasterCsv.items(
            "name,purchase_price,opening_qty,opening_cost,godown,batch,expiry,is_service\n" +
                "Oil,90,12,,back store,B1,2027-03-31,\n" + // 2: valid, cost from purchase price, batch tracked
                "Nut,1,5,0.5,,,,\n" + // 3: valid, default godown
                "Bolt,1,,,,B2,,\n" + // 4: opening columns without a quantity
                "Fix,0,2,,,,,yes\n" + // 5: service
                "Can,1,-1,,,,,\n" + // 6: bad quantity
                "Jar,1,1,,Shed,,,\n" + // 7: unknown godown
                "Tin,1,1,,,,2027-01-01,\n" + // 8: expiry without batch
                "Box,1,1,,,X,31/03/2027,\n", // 9: bad date
            "F1", units, emptyList(), id, at, godowns,
        )
        assertEquals(listOf("Oil", "Nut"), r.valid.map { it.name })
        assertEquals(listOf(4, 5, 6, 7, 8, 9), r.errors.map { it.first })
        val oil = r.valid[0]
        assertEquals(true, oil.trackBatch)
        assertEquals(OpeningStock(app.openbill.core.model.Quantity.of(12), Money.parse("90"), "G1", "B1", kotlinx.datetime.LocalDate(2027, 3, 31)), r.openings[oil.id])
        assertEquals(OpeningStock(app.openbill.core.model.Quantity.of(5), Money.parse("0.5"), null, null, null), r.openings[r.valid[1].id])
        assertEquals(false, r.valid[1].trackBatch)
    }
}
