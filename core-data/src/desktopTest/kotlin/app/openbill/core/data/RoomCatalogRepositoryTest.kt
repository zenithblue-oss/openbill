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

class RoomCatalogRepositoryTest {
    private lateinit var db: OpenBillDatabase
    private lateinit var repository: RoomCatalogRepository

    @BeforeTest
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder<OpenBillDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()
        repository = RoomCatalogRepository(db, DatabaseTransactionRunner(db))
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun roundTripsCatalogEntitiesAndSearchesItems(): Unit = runBlocking {
        db.firmDao().insert(testFirm().toEntity(null))
        val unit = testUnit()
        val category = testCategory()
        val godown = testGodown()
        val item = testItem()

        repository.saveUnit(unit)
        repository.saveCategory(category)
        repository.saveGodown(godown)
        repository.saveItem(item)

        assertEquals(unit, repository.findUnit(unit.id))
        assertEquals(category, repository.findCategory(category.id))
        assertEquals(godown, repository.findGodown(godown.id))
        assertEquals(item, repository.findItem(item.id))

        assertEquals(listOf(unit), repository.observeUnits(item.firmId).first())
        assertEquals(listOf(category), repository.observeCategories(item.firmId).first())
        assertEquals(listOf(godown), repository.observeGodowns(item.firmId).first())
        assertEquals(listOf(item), repository.observeItems(item.firmId).first())
        assertEquals(listOf(item), repository.searchItems(item.firmId, "WID-001"))
        assertEquals(listOf(item), repository.searchItems(item.firmId, "7326"))
        assertTrue(repository.searchItems(item.firmId, "  ").isEmpty())
    }

    @Test
    fun archivesItemsAndCategoriesOutOfObservations(): Unit = runBlocking {
        db.firmDao().insert(testFirm().toEntity(null))
        repository.saveUnit(testUnit())
        repository.saveCategory(testCategory())
        repository.saveItem(testItem())

        repository.archiveItem("item-1", ARCHIVED_AT)
        assertTrue(repository.observeItems("firm-1").first().isEmpty())
        assertTrue(repository.searchItems("firm-1", "Steel").isEmpty())
        val item = assertNotNull(repository.findItem("item-1"))
        assertEquals(ARCHIVED_AT, item.deletedAt)
        assertEquals(ARCHIVED_AT, item.updatedAt)

        repository.archiveCategory("category-1", ARCHIVED_AT)
        assertTrue(repository.observeCategories("firm-1").first().isEmpty())
        val category = assertNotNull(repository.findCategory("category-1"))
        assertEquals(ARCHIVED_AT, category.deletedAt)
    }
}
