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
}
