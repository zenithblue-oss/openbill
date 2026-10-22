package app.openbill.core.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DatabaseSchemaTest {
    private lateinit var tempDir: Path
    private lateinit var databasePath: Path
    private lateinit var helper: MigrationTestHelper
    private var validationDatabase: OpenBillDatabase? = null

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("openbill-schema-test")
        databasePath = tempDir.resolve("openbill.db")
        helper = MigrationTestHelper(
            schemaDirectoryPath = Path.of("schemas"),
            databasePath = databasePath,
            driver = BundledSQLiteDriver(),
            databaseClass = OpenBillDatabase::class,
            databaseFactory = {
                Room.inMemoryDatabaseBuilder<OpenBillDatabase>()
                    .setDriver(BundledSQLiteDriver())
                    .build()
                    .also { validationDatabase = it }
            },
            autoMigrationSpecs = emptyList(),
        )
    }

    @AfterTest
    fun tearDown() {
        validationDatabase?.close()
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun exportedSchemaHasEveryEntityTable() {
        assertEquals(3, OPENBILL_DATABASE_VERSION)
        assertTrue(Path.of("schemas/app.openbill.core.database.OpenBillDatabase/1.json").exists())
        assertTrue(Path.of("schemas/app.openbill.core.database.OpenBillDatabase/2.json").exists())
        assertTrue(Path.of("schemas/app.openbill.core.database.OpenBillDatabase/3.json").exists())

        helper.createDatabase(3).use { connection ->
            val tables = mutableListOf<String>()
            connection.prepare(
                "SELECT name FROM sqlite_master WHERE type = 'table' " +
                    "AND name NOT LIKE 'sqlite_%' AND name != 'room_master_table' ORDER BY name",
            ).use { statement ->
                while (statement.step()) tables += statement.getText(0)
            }
            assertEquals(EXPECTED_TABLES.sorted(), tables)
        }
    }

    @Test
    fun schemaV1MigratesToV2AndValidatesAgainstCurrentEntities() {
        helper.createDatabase(1).close()
        helper.runMigrationsAndValidate(
            OPENBILL_DATABASE_VERSION,
            DatabaseMigrations.migrationsFor(1),
        ).use { connection ->
            connection.prepare("SELECT COUNT(*) FROM firms").use { statement ->
                assertTrue(statement.step())
                assertEquals(0, statement.getLong(0))
            }

            var stockDirectionColumnFound = false
            connection.prepare("PRAGMA table_info(voucher_lines)").use { statement ->
                while (statement.step()) {
                    if (statement.getText(1) == "stockDirection") {
                        stockDirectionColumnFound = true
                        assertEquals("TEXT", statement.getText(2))
                        assertEquals(0, statement.getLong(3))
                    }
                }
            }
            assertTrue(stockDirectionColumnFound)
        }
    }

    @Test
    fun freshDatabaseCreatesCurrentSchemaAndAcceptsWrites() = runBlocking {
        val freshPath = tempDir.resolve("fresh.db")
        val db = openBillDatabase(Room.databaseBuilder<OpenBillDatabase>(freshPath.toString()))
        try {
            db.firmDao().insert(sampleFirm())
            assertEquals("OpenBill Traders", db.firmDao().findById(Fixture.FIRM_ID)?.name)
        } finally {
            db.close()
        }
        assertEquals(
            listOf("audit_events_no_delete", "audit_events_no_update"),
            triggerNames(freshPath),
        )
    }

    @Test
    fun migratedDatabaseInstallsAuditTriggers() = runBlocking {
        helper.createDatabase(2).close()
        val db = openBillDatabase(
            Room.databaseBuilder<OpenBillDatabase>(databasePath.toString()),
        )
        try {
            assertNull(db.firmDao().findById(Fixture.FIRM_ID))
        } finally {
            db.close()
        }
        assertEquals(
            listOf("audit_events_no_delete", "audit_events_no_update"),
            triggerNames(databasePath),
        )
    }

    @Test
    fun schemaV2MigratesToV3AndValidatesAgainstCurrentEntities() {
        helper.createDatabase(2).close()
        helper.runMigrationsAndValidate(
            OPENBILL_DATABASE_VERSION,
            DatabaseMigrations.migrationsFor(2),
        ).use { connection ->
            var codeColumnFound = false
            connection.prepare("PRAGMA table_info(accounts)").use { statement ->
                while (statement.step()) {
                    if (statement.getText(1) == "code") {
                        codeColumnFound = true
                        assertEquals("TEXT", statement.getText(2))
                        assertEquals(0, statement.getLong(3))
                        assertTrue(statement.isNull(4))
                    }
                }
            }
            assertTrue(codeColumnFound)

            connection.prepare("SELECT COUNT(*) FROM payment_details").use { statement ->
                assertTrue(statement.step())
                assertEquals(0, statement.getLong(0))
            }
        }
    }

    @Test
    fun migrationChainHasNoGaps() {
        val migrations = DatabaseMigrations.migrationsFor(1)
        assertEquals(2, migrations.size)
        assertEquals(1, migrations[0].startVersion)
        assertEquals(2, migrations[0].endVersion)
        assertEquals(2, migrations[1].startVersion)
        assertEquals(3, migrations[1].endVersion)
        assertTrue(DatabaseMigrations.migrationsFor(3).isEmpty())
    }

    @Test
    fun migrationRangesAreRejectedWhenInvalid() {
        assertTrue(runCatching { DatabaseMigrations.migrationsFor(2, 1) }.isFailure)
    }

    private fun triggerNames(path: Path): List<String> {
        val connection = BundledSQLiteDriver().open(path.toString())
        try {
            val names = mutableListOf<String>()
            connection.prepare(
                "SELECT name FROM sqlite_master WHERE type = 'trigger' " +
                    "AND name LIKE 'audit_events_%' ORDER BY name",
            ).use { statement ->
                while (statement.step()) names += statement.getText(0)
            }
            return names
        } finally {
            connection.close()
        }
    }

    private companion object {
        val EXPECTED_TABLES = listOf(
            "accounts",
            "attachments",
            "audit_events",
            "batches",
            "firm_settings",
            "firms",
            "financial_years",
            "godowns",
            "item_categories",
            "items",
            "journal_entries",
            "party_addresses",
            "parties",
            "payment_allocations",
            "payment_details",
            "serial_numbers",
            "stock_movements",
            "units",
            "voucher_charges",
            "voucher_lines",
            "voucher_number_sequences",
            "vouchers",
        )
    }
}
