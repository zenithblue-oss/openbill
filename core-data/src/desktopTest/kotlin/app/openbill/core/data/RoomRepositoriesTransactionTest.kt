package app.openbill.core.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.openbill.core.database.OpenBillDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

class RoomRepositoriesTransactionTest {
    @Test
    fun failureInsideTransactionRollsBackEveryWrite(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder<OpenBillDatabase>().setDriver(BundledSQLiteDriver()).build()
        val repos = db.roomRepositories()
        repos.firms.saveFirm(testFirm())
        assertFailsWith<IllegalStateException> {
            repos.transaction {
                repos.parties.saveParty(testParty("p1", addresses = emptyList()))
                error("boom")
            }
        }
        assertEquals(null, repos.parties.findParty("p1"))
        repos.transaction { repos.parties.saveParty(testParty("p2", addresses = emptyList())) }
        assertEquals("p2", repos.parties.findParty("p2")?.id)
        db.close()
    }
}
