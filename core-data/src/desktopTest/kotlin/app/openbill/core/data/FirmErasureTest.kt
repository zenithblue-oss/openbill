package app.openbill.core.data

import androidx.room.Room
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import app.openbill.core.database.DatabaseTransactionRunner
import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.database.openBillDatabase
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class FirmErasureTest {
    // The production builder, so the append-only audit triggers are installed.
    private val db: OpenBillDatabase = openBillDatabase(Room.inMemoryDatabaseBuilder<OpenBillDatabase>())
    private val runner = DatabaseTransactionRunner(db)
    private val firms = RoomFirmRepository(db, runner)
    private val vouchers = RoomVoucherRepository(db, runner)
    private val catalog = RoomCatalogRepository(db, runner)

    @AfterTest
    fun tearDown() = db.close()

    private suspend fun count(sql: String): Long = db.useReaderConnection { c -> c.usePrepared(sql) { it.step(); it.getLong(0) } }

    private val firmTables = listOf(
        "firm_settings", "financial_years", "parties", "item_categories", "units", "godowns", "items", "vouchers",
        "voucher_number_sequences", "payment_allocations", "payment_details", "stock_movements", "accounts",
        "journal_entries", "audit_events", "attachments",
    )

    @Test
    fun eraseFirmDeletesEveryRowIncludingAuditAndKeepsOtherFirmsAndTriggers(): Unit = runBlocking {
        firms.saveFirm(testFirm())
        firms.saveSettings(testSettings(negativeStockAllowed = true))
        firms.saveFinancialYear(testFinancialYear())
        RoomPartyRepository(db, runner).saveParty(testParty())
        catalog.saveCategory(testCategory())
        catalog.saveUnit(testUnit())
        catalog.saveGodown(testGodown())
        catalog.saveItem(testItem())
        vouchers.saveDraft(testVoucher(lines = listOf(testLine(itemId = "item-1"))))
        vouchers.post("voucher-1", POSTED_AT)
        firms.saveFirm(testFirm(id = "firm-2", name = "Other"))
        firms.saveFinancialYear(testFinancialYear(id = "fy-2", firmId = "firm-2"))
        firms.saveSettings(testSettings(firmId = "firm-2", defaultGodownId = null).copy(lockDate = kotlinx.datetime.LocalDate(2026, 3, 31))) // audited
        vouchers.saveDraft(testVoucher(id = "other-1", firmId = "firm-2", financialYearId = "fy-2", partyId = null, lines = listOf(testLine(id = "other-line")), charges = emptyList()))
        assertTrue(count("SELECT COUNT(*) FROM audit_events WHERE firmId = 'firm-1'") > 0)
        assertTrue(count("SELECT COUNT(*) FROM journal_entries WHERE firmId = 'firm-1'") > 0)

        firms.eraseFirm("firm-1")

        assertNull(firms.findFirm("firm-1"))
        firmTables.forEach { t -> assertEquals(0, count("SELECT COUNT(*) FROM $t WHERE firmId = 'firm-1'"), t) }
        listOf("voucher_lines", "voucher_charges", "voucher_charge_portions").forEach { t ->
            assertEquals(0, count("SELECT COUNT(*) FROM $t WHERE voucherId = 'voucher-1'"), t)
        }
        assertNotNull(vouchers.findVoucher("other-1"))
        assertEquals(2, count("SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'audit_events_no_%'"))
        assertTrue(count("SELECT COUNT(*) FROM audit_events") > 0, "firm-2 audit rows survive")
        assertFailsWith<Exception> {
            db.useWriterConnection { c -> c.usePrepared("DELETE FROM audit_events") { it.step() } }
        }
    }
}
