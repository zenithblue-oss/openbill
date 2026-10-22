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
        assertEquals(1, OPENBILL_DATABASE_VERSION)
        assertTrue(Path.of("schemas/app.openbill.core.database.OpenBillDatabase/1.json").exists())

        helper.createDatabase(1).use { connection ->
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
    fun schemaV1MigratesAndValidatesAgainstCurrentEntities() {
        helper.createDatabase(1).close()
        helper.runMigrationsAndValidate(1, emptyList()).use { connection ->
            connection.prepare("SELECT COUNT(*) FROM firms").use { statement ->
                assertTrue(statement.step())
                assertEquals(0, statement.getLong(0))
            }
        }
    }

    @Test
    fun freshDatabaseCreatesCurrentSchemaAndAcceptsWrites() = runBlocking {
        val freshPath = tempDir.resolve("fresh.db")
        val db = Room.databaseBuilder<OpenBillDatabase>(freshPath.toString())
            .setDriver(BundledSQLiteDriver())
            .build()
        try {
            db.firmDao().insert(sampleFirm())
            assertEquals("OpenBill Traders", db.firmDao().findById(Fixture.FIRM_ID)?.name)
        } finally {
            db.close()
        }
    }

    @Test
    fun migrationChainHasNoGaps() {
        assertEquals(emptyList(), DatabaseMigrations.migrationsFor(1))
    }

    @Test
    fun migrationRangesAreRejectedWhenInvalid() {
        assertTrue(runCatching { DatabaseMigrations.migrationsFor(2, 1) }.isFailure)
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
