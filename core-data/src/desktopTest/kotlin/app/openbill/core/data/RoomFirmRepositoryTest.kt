package app.openbill.core.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.openbill.core.database.DatabaseTransactionRunner
import app.openbill.core.database.OpenBillDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class RoomFirmRepositoryTest {
    private lateinit var db: OpenBillDatabase
    private lateinit var repository: RoomFirmRepository

    @BeforeTest
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder<OpenBillDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()
        repository = RoomFirmRepository(db, DatabaseTransactionRunner(db))
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun roundTripsFirmSettingsAndFinancialYearsThenArchives(): Unit = runBlocking {
        val firm = testFirm()
        repository.saveFirm(firm)

        assertEquals(firm, repository.findFirm(firm.id))
        assertEquals(listOf(firm), repository.observeFirms().first())

        val settings = testSettings()
        repository.saveSettings(settings)
        assertEquals(settings, repository.findSettings(firm.id))
        assertEquals(settings, repository.observeSettings(firm.id).first())

        val financialYear = testFinancialYear()
        repository.saveFinancialYear(financialYear)
        assertEquals(financialYear, repository.findFinancialYear(financialYear.id))
        assertEquals(listOf(financialYear), repository.observeFinancialYears(firm.id).first())

        repository.archiveFirm(firm.id, ARCHIVED_AT)

        assertTrue(repository.observeFirms().first().isEmpty())
        val archived = assertNotNull(repository.findFirm(firm.id))
        assertEquals(ARCHIVED_AT, archived.deletedAt)
        assertEquals(ARCHIVED_AT, archived.updatedAt)
    }

    @Test
    fun savesAreUpsertsAndSettingsObserveUpdates(): Unit = runBlocking {
        repository.saveFirm(testFirm())
        repository.saveFirm(testFirm(name = "OpenBill Prime"))
        assertEquals("OpenBill Prime", repository.findFirm("firm-1")?.name)

        repository.saveSettings(testSettings(invoicePrefix = "INV"))
        repository.saveSettings(testSettings(invoicePrefix = "BILL"))
        assertEquals("BILL", repository.observeSettings("firm-1").first()?.invoicePrefix)
    }
}
