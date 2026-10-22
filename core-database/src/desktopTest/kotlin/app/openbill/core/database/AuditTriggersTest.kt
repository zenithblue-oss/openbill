package app.openbill.core.database

import androidx.room.Room
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class AuditTriggersTest {
    private lateinit var db: OpenBillDatabase

    @BeforeTest
    fun setUp() {
        db = openBillDatabase(
            Room.inMemoryDatabaseBuilder<OpenBillDatabase>()
                .setDriver(BundledSQLiteDriver()),
        )
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun openBillDatabaseInstallsAuditTriggers(): Unit = runBlocking {
        db.firmDao().insert(sampleFirm())
        assertEquals(
            listOf("audit_events_no_delete", "audit_events_no_update"),
            db.triggerNames(),
        )
    }

    @Test
    fun updateAndDeleteOnAuditEventsAreRejectedButInsertWorks(): Unit = runBlocking {
        db.firmDao().insert(sampleFirm())
        db.auditDao().insert(sampleAuditEvent())

        val update = runCatching {
            db.execute("UPDATE audit_events SET action = 'tampered' WHERE id = 'audit-1'")
        }
        assertTrue(update.isFailure)
        assertTrue(
            update.exceptionOrNull()?.message.orEmpty().contains("append-only"),
            "Unexpected error: ${update.exceptionOrNull()}",
        )

        val delete = runCatching {
            db.execute("DELETE FROM audit_events WHERE id = 'audit-1'")
        }
        assertTrue(delete.isFailure)
        assertTrue(
            delete.exceptionOrNull()?.message.orEmpty().contains("append-only"),
            "Unexpected error: ${delete.exceptionOrNull()}",
        )

        val unchanged = assertNotNull(
            db.auditDao().observeRecent(Fixture.FIRM_ID, 10).first()
                .singleOrNull { it.id == Fixture.AUDIT_ID },
        )
        assertEquals("created", unchanged.action)

        db.auditDao().insert(
            sampleAuditEvent(id = "audit-2", action = "voucher.posted"),
        )
        assertEquals(
            listOf("audit-2", Fixture.AUDIT_ID),
            db.auditDao().observeRecent(Fixture.FIRM_ID, 10).first().map { it.id },
        )
    }

    @Test
    fun installHelperIsIdempotentOnARawConnection() {
        val connection = BundledSQLiteDriver().open(":memory:")
        try {
            connection.execSQL(
                "CREATE TABLE audit_events (id TEXT PRIMARY KEY, action TEXT NOT NULL)",
            )
            AuditTriggers.install(connection)
            AuditTriggers.install(connection)
            connection.execSQL("INSERT INTO audit_events (id, action) VALUES ('a', 'created')")

            val update = runCatching {
                connection.execSQL("UPDATE audit_events SET action = 'tampered' WHERE id = 'a'")
            }
            assertTrue(update.isFailure)
            assertTrue(update.exceptionOrNull()?.message.orEmpty().contains("append-only"))

            val delete = runCatching {
                connection.execSQL("DELETE FROM audit_events WHERE id = 'a'")
            }
            assertTrue(delete.isFailure)
            assertTrue(delete.exceptionOrNull()?.message.orEmpty().contains("append-only"))

            connection.execSQL("INSERT INTO audit_events (id, action) VALUES ('b', 'created')")
            connection.prepare("SELECT COUNT(*) FROM audit_events").use { statement ->
                assertTrue(statement.step())
                assertEquals(2, statement.getLong(0))
            }
        } finally {
            connection.close()
        }
    }

    private suspend fun OpenBillDatabase.triggerNames(): List<String> =
        useReaderConnection { transactor ->
            transactor.usePrepared(
                "SELECT name FROM sqlite_master WHERE type = 'trigger' " +
                    "AND name LIKE 'audit_events_%' ORDER BY name",
            ) { statement ->
                buildList {
                    while (statement.step()) add(statement.getText(0))
                }
            }
        }

    private suspend fun OpenBillDatabase.execute(sql: String) {
        useWriterConnection { transactor ->
            transactor.usePrepared(sql) { statement ->
                statement.step()
            }
        }
    }
}
