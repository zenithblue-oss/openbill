package app.openbill.core.domain.report

import app.openbill.core.domain.model.AccountCode
import app.openbill.core.domain.model.Batch
import app.openbill.core.domain.model.StockMovement
import app.openbill.core.domain.model.booksLockedThrough
import app.openbill.core.domain.model.requireBooksOpen
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

class ReportsTest {
    private val data = ReportFixture.data()
    private val f = ReportFixture

    private fun ReportTable.total(key: String) = Money.parse(totals!![columns.indexOfFirst { it.key == key }])

    private fun ledgerNet(code: AccountCode): Money = f.journal.filter { it.entry.accountId == f.ledger.accountFor(code).id }
        .fold(Money.ZERO) { t, x -> t + x.entry.debit - x.entry.credit }

    @Test
    fun salesRegisterTiesToLedger() {
        val t = Reports.run(ReportKind.SALES_REGISTER, data)
        assertEquals(4, t.rows.size) // three posted sales and the credit note; the cancelled sale is out
        assertEquals(-ledgerNet(AccountCode.SALES), t.total("taxable"))
        assertEquals(Money.parse("121400.00"), t.total("taxable"))
        assertEquals(ledgerNet(AccountCode.AR), t.total("total"))
        assertEquals(-ledgerNet(AccountCode.OUTPUT_GST), t.total("cgst") + t.total("sgst") + t.total("igst") + t.total("cess"))
    }

    @Test
    fun purchaseRegisterTiesToLedger() {
        val t = Reports.run(ReportKind.PURCHASE_REGISTER, data)
        assertEquals(ledgerNet(AccountCode.PURCHASES), t.total("taxable"))
        assertEquals(-ledgerNet(AccountCode.AP), t.total("total"))
        assertEquals(Money.parse("180.00"), t.total("igst"))
    }

    @Test
    fun itemProfitUsesWeightedAverageCost() {
        val t = Reports.run(ReportKind.ITEM_PROFIT, data)
        val bolt = t.rows.single { it[0] == "Steel Bolt" }
        assertEquals(listOf("Steel Bolt", "9", "900.00", "450.00", "450.00", "50.00"), bolt)
    }

    @Test
    fun profitAndLossAndStockSummary() {
        val pl = Reports.run(ReportKind.PROFIT_LOSS, data)
        assertEquals(Money.parse("120950.00"), pl.total("amount"))
        val stock = Reports.run(ReportKind.STOCK_SUMMARY, data)
        assertEquals(listOf("Steel Bolt", "pcs", "0", "21", "10", "11", "50.00", "550.00"), stock.rows.single())
        assertEquals(emptyList<List<String>>(), Reports.run(ReportKind.LOW_STOCK, data).rows, "no level set")
        val low = ReportData(data.firm, data.settings, data.period, data.vouchers, data.parties, data.items.map { it.copy(lowStockLevel = Quantity.of(12)) }, data.units, data.accounts, data.journal, data.stock, data.partyMovements)
        assertEquals(listOf("Steel Bolt", "pcs", "11", "12", "1"), Reports.run(ReportKind.LOW_STOCK, low).rows.single())
    }

    @Test
    fun outstandingAndStatementReconcile() {
        val out = Reports.run(ReportKind.OUTSTANDING, data)
        assertEquals(ledgerNet(AccountCode.AR), out.total("receivable"))
        assertEquals(-ledgerNet(AccountCode.AP), out.total("payable"))
        val st = Reports.run(ReportKind.PARTY_STATEMENT, data, "P1")
        assertEquals(Money.parse("1062.00"), st.total("balance"))
    }

    @Test
    fun gstr3bTables() {
        val t = Reports.run(ReportKind.GSTR3B, data)
        assertEquals(listOf("3.1(a) Outward taxable supplies", "121400.00", "21600.00", "111.00", "111.00", "0.00"), t.rows[0])
        assertEquals(listOf("4(A)(5) All other ITC", "", "180.00", "0.00", "0.00", "0.00"), t.rows[6])
    }

    @Test
    fun gstr1Classification() {
        val g = Gstr1.build(data)
        assertEquals(listOf("INV-0001"), g.b2b.map { it.voucher.number })
        assertEquals(listOf("INV-0002"), g.b2cl.map { it.voucher.number })
        assertEquals(1, g.b2cs.size)
        assertEquals(listOf("CN-0001"), g.cdnr.map { it.voucher.number })
        assertEquals(Gstr1.DocRange(1, "INV-0001", "INV-0004", 4, 1), g.docs.first())
    }

    @Test
    fun gstr1JsonGolden() {
        assertEquals(GSTR1_GOLDEN, Gstr1.build(data).toJson())
    }

    @Test
    fun tallyXmlGoldenAndBalanced() {
        val xml = TallyXml.export(data)
        assertEquals(TALLY_GOLDEN, xml)
        Regex("<VOUCHER .*?</VOUCHER>", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { v ->
            val sum = Regex("<AMOUNT>(.*?)</AMOUNT>").findAll(v.value).fold(Money.ZERO) { t, m -> t + Money.parse(m.groupValues[1]) }
            assertEquals(Money.ZERO, sum, "voucher amounts must balance")
        }
    }

    @Test
    fun csvAndJsonRender() {
        val t = Reports.run(ReportKind.EXPENSES, data)
        assertEquals("Category,Vouchers,Amount,GST,Total\r\nTotal,0,0.00,0.00,0.00\r\n", t.toCsv())
        assertTrue(t.toJson().contains("\"totals\": {"))
    }

    @Test
    fun daySummaryMatchesRegister() {
        val t = Reports.run(ReportKind.DAY_SUMMARY, data)
        assertEquals(listOf("2026-10-02", "2026-10-05", "2026-10-07", "2026-10-10"), t.rows.map { it[0] })
        assertEquals(listOf("2026-10-10", "0", "-118.00", "0.00", "0.00", "0.00"), t.rows.last()) // credit note
        assertEquals(listOf("Total", "3", "143222.00", "0.00", "0.00", "0.00"), t.totals)
        assertEquals(Reports.run(ReportKind.SALES_REGISTER, data).total("total"), t.total("sales"))
    }

    @Test
    fun gstr2MatchesPurchaseRegisterAndGstr3b() {
        val t = Reports.run(ReportKind.GSTR2, data)
        assertEquals(
            listOf("2026-10-01", "Purchase", "PUR-0001", "BS/77", f.parties.first { it.id == "P4" }.name, f.parties.first { it.id == "P4" }.gstin.orEmpty()),
            t.rows.single().take(6),
        )
        assertEquals(listOf("18", "taxable", "No", "Eligible", "1000.00", "0.00", "0.00", "180.00", "0.00"), t.rows.single().drop(7))
        val register = Reports.run(ReportKind.PURCHASE_REGISTER, data)
        listOf("taxable", "igst", "cgst", "sgst", "cess").forEach { assertEquals(register.total(it), t.total(it), it) }
        val itc = Reports.run(ReportKind.GSTR3B, data).rows.single { it[0].startsWith("4(C)") }
        assertEquals("Eligible ITC (GSTR-3B 4(C)): IGST ${itc[2]}, CGST ${itc[3]}, SGST ${itc[4]}, cess ${itc[5]}.", t.notes[0])
    }

    @Test
    fun batchExpiryListsBatchesWithStock() {
        val batches = listOf(
            Batch("B1", "I1", "G1", "LOT-1", LocalDate(2026, 11, 15)),
            Batch("B2", "I1", "G1", "LOT-2", LocalDate(2026, 10, 20)),
            Batch("B3", "I1", "G1", "LOT-3", null),
            Batch("B4", "I1", "G1", "LOT-4", LocalDate(2027, 6, 1)), // sold out
        )
        fun move(id: String, batch: String, qty: Long) =
            StockMovement(id, "F1", "I1", "G1", batchId = batch, voucherId = "V6", date = LocalDate(2026, 10, 1), quantity = Quantity.of(qty), createdAt = kotlin.time.Instant.fromEpochSeconds(0))
        val stock = listOf(move("b1", "B1", 5), move("b2", "B2", 3), move("b3", "B3", 2), move("b4", "B4", 4), move("b5", "B4", -4))
        val d = ReportData(
            data.firm, data.settings, data.period, data.vouchers, data.parties, data.items, data.units, data.accounts, data.journal, stock, data.partyMovements,
            batches = batches, godowns = listOf(app.openbill.core.domain.model.Godown("G1", data.firm.id, "Main", createdAt = kotlin.time.Instant.fromEpochSeconds(0), updatedAt = kotlin.time.Instant.fromEpochSeconds(0))),
        )
        val t = Reports.run(ReportKind.BATCH_EXPIRY, d)
        assertEquals(
            listOf(
                listOf("Steel Bolt", "pcs", "LOT-2", "Main", "2026-10-20", "3", "-11", "Expired"),
                listOf("Steel Bolt", "pcs", "LOT-1", "Main", "2026-11-15", "5", "15", "Expiring soon"),
                listOf("Steel Bolt", "pcs", "LOT-3", "Main", "", "2", "", "No expiry"),
            ),
            t.rows,
        )
    }

    @Test
    fun zipCrcAndBundleKinds() {
        assertEquals(0xCBF43926.toInt(), ZipWriter.crc32("123456789".encodeToByteArray()))
        val zip = ZipWriter.write(listOf("a.csv" to "x,y\r\n".encodeToByteArray()))
        assertEquals(listOf<Byte>(0x50, 0x4b, 0x03, 0x04), zip.take(4))
        assertTrue(ReportKind.PARTY_STATEMENT !in ReportPack.BUNDLE_KINDS)
        assertFailsWith<IllegalArgumentException> { ZipWriter.write(listOf("a" to ByteArray(0), "a" to ByteArray(0))) }
        assertEquals(listOf("102026", "112026", "122026"), ReportPack.months(ReportPeriod(LocalDate(2026, 10, 15), LocalDate(2026, 12, 2))).map { it.returnPeriod })
    }

    @Test
    fun periodsAndLock() {
        assertEquals(ReportPeriod(LocalDate(2026, 4, 1), LocalDate(2027, 3, 31)), PeriodPreset.THIS_FY.resolve(LocalDate(2026, 10, 26)))
        assertEquals(ReportPeriod(LocalDate(2026, 10, 1), LocalDate(2026, 12, 31)), PeriodPreset.THIS_QUARTER.resolve(LocalDate(2026, 10, 26)))
        assertEquals(ReportPeriod(LocalDate(2026, 9, 1), LocalDate(2026, 9, 30)), PeriodPreset.LAST_MONTH.resolve(LocalDate(2026, 10, 26)))
        assertEquals("102026", data.period.returnPeriod)
        val lock = booksLockedThrough(LocalDate(2026, 6, 30), LocalDate(2026, 3, 31))
        assertEquals(LocalDate(2026, 6, 30), lock)
        assertFailsWith<IllegalArgumentException> { requireBooksOpen(LocalDate(2026, 6, 30), lock) }
        requireBooksOpen(LocalDate(2026, 7, 1), lock)
    }
}
