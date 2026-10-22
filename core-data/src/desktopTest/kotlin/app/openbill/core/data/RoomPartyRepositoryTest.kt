package app.openbill.core.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.openbill.core.database.DatabaseTransactionRunner
import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.domain.model.AddressKind
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class RoomPartyRepositoryTest {
    private lateinit var db: OpenBillDatabase
    private lateinit var repository: RoomPartyRepository

    @BeforeTest
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder<OpenBillDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()
        val runner = DatabaseTransactionRunner(db)
        repository = RoomPartyRepository(db, runner)
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun roundTripsPartyWithAddressesAndSearches(): Unit = runBlocking {
        db.firmDao().insert(testFirm().toEntity(null))
        val party = testParty()
        repository.saveParty(party)

        assertEquals(party, repository.findParty(party.id))
        assertEquals(listOf(party), repository.observeParties(party.firmId).first())
        assertEquals(party, repository.observeParty(party.id).first())
        assertEquals(listOf(party), repository.searchParties(party.firmId, "acme"))
        assertTrue(repository.searchParties(party.firmId, "   ").isEmpty())
    }

    @Test
    fun saveReplacesAddressesAndArchiveFiltersObservations(): Unit = runBlocking {
        db.firmDao().insert(testFirm().toEntity(null))
        val party = testParty()
        repository.saveParty(party)

        val later = Instant.fromEpochMilliseconds(UPDATED_MILLIS + 1_000L)
        val updated = party.copy(
            name = "Acme Prime",
            addresses = listOf(
                party.addresses[0].copy(city = "Pune City"),
                testAddress("address-3", AddressKind.OTHER),
            ),
            updatedAt = later,
        )
        repository.saveParty(updated)

        val replaced = assertNotNull(repository.findParty(party.id))
        assertEquals("Acme Prime", replaced.name)
        assertEquals(listOf("address-1", "address-3"), replaced.addresses.map { it.id })
        assertEquals("Pune City", replaced.addresses.first().city)

        val rawAddresses = db.partyDao().addressesFor(party.id)
        assertEquals(listOf("address-1", "address-3"), rawAddresses.map { it.id })
        assertFalse(rawAddresses.any { it.id == "address-2" })

        repository.archiveParty(party.id, ARCHIVED_AT)

        assertTrue(repository.observeParties(party.firmId).first().isEmpty())
        assertTrue(repository.searchParties(party.firmId, "acme").isEmpty())
        assertEquals(ARCHIVED_AT, repository.observeParty(party.id).first()?.deletedAt)
        assertEquals(ARCHIVED_AT, repository.findParty(party.id)?.deletedAt)
    }
}
