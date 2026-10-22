package app.openbill.core.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
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
        assertEquals(5, OPENBILL_DATABASE_VERSION)
        assertTrue(Path.of("schemas/app.openbill.core.database.OpenBillDatabase/1.json").exists())
        assertTrue(Path.of("schemas/app.openbill.core.database.OpenBillDatabase/2.json").exists())
        assertTrue(Path.of("schemas/app.openbill.core.database.OpenBillDatabase/3.json").exists())
        assertTrue(Path.of("schemas/app.openbill.core.database.OpenBillDatabase/4.json").exists())
        assertTrue(Path.of("schemas/app.openbill.core.database.OpenBillDatabase/5.json").exists())

        helper.createDatabase(5).use { connection ->
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
    fun schemaV3MigratesToV4AndKeepsExistingChargesExplicit() {
        helper.createDatabase(3).use { connection ->
            connection.execSQL(
                "INSERT INTO firms (id, name, stateCode, addressLine1, city, createdAt, updatedAt, revision) " +
                    "VALUES ('firm-1', 'Firm', '27', 'Line 1', 'Pune', 1, 1, 1)",
            )
            connection.execSQL(
                "INSERT INTO financial_years (id, firmId, startDate, endDate, createdAt, updatedAt) " +
                    "VALUES ('fy-1', 'firm-1', '2026-04-01', '2027-03-31', 1, 1)",
            )
            connection.execSQL(
                "INSERT INTO vouchers (id, firmId, financialYearId, type, date, status, " +
                    "placeOfSupplyStateCode, subtotal, discount, taxable, tax, roundOff, total, " +
                    "createdAt, updatedAt, revision) VALUES ('voucher-1', 'firm-1', 'fy-1', 'sale', " +
                    "'2026-05-10', 'draft', '27', 10000, 0, 10000, 1800, 0, 12390, 1, 1, 1)",
            )
            connection.execSQL(
                "INSERT INTO voucher_charges (id, voucherId, name, amount, taxRateUnits, " +
                    "cessRateUnits, taxAmount, total, sortOrder) " +
                    "VALUES ('charge-1', 'voucher-1', 'Freight', 500, 180000, 0, 90, 590, 0)",
            )
        }
        helper.runMigrationsAndValidate(
            OPENBILL_DATABASE_VERSION,
            DatabaseMigrations.migrationsFor(3),
        ).use { connection ->
            val columns = mutableMapOf<String, Pair<Long, String?>>()
            connection.prepare("PRAGMA table_info(voucher_charges)").use { statement ->
                while (statement.step()) {
                    columns[statement.getText(1)] =
                        statement.getLong(3) to (if (statement.isNull(4)) null else statement.getText(4))
                }
            }
            assertEquals(1L to "'explicit_rate'", columns["taxBasis"])
            assertEquals(1L to "'exclusive'", columns["taxMode"])
            assertEquals(1L to "'taxable'", columns["treatment"])
            assertEquals(0L to null, columns["hsnSac"])

            connection.prepare(
                "SELECT taxBasis, taxMode, treatment, hsnSac, total FROM voucher_charges WHERE id = 'charge-1'",
            ).use { statement ->
                assertTrue(statement.step())
                assertEquals("explicit_rate", statement.getText(0))
                assertEquals("exclusive", statement.getText(1))
                assertEquals("taxable", statement.getText(2))
                assertTrue(statement.isNull(3))
                assertEquals(590L, statement.getLong(4))
            }
        }
    }

    @Test
    fun schemaV4MigratesToV5AndAddsChargePortions() {
        helper.createDatabase(4).use { connection ->
            connection.execSQL(
                "INSERT INTO firms (id, name, stateCode, addressLine1, city, createdAt, updatedAt, revision) " +
                    "VALUES ('firm-1', 'Firm', '27', 'Line 1', 'Pune', 1, 1, 1)",
            )
            connection.execSQL(
                "INSERT INTO financial_years (id, firmId, startDate, endDate, createdAt, updatedAt) " +
                    "VALUES ('fy-1', 'firm-1', '2026-04-01', '2027-03-31', 1, 1)",
            )
            connection.execSQL(
                "INSERT INTO vouchers (id, firmId, financialYearId, type, date, status, " +
                    "placeOfSupplyStateCode, subtotal, discount, taxable, tax, roundOff, total, " +
                    "createdAt, updatedAt, revision) VALUES ('voucher-1', 'firm-1', 'fy-1', 'sale', " +
                    "'2026-05-10', 'draft', '27', 10000, 0, 10000, 1800, 0, 12390, 1, 1, 1)",
            )
            connection.execSQL(
                "INSERT INTO voucher_charges (id, voucherId, name, amount, taxRateUnits, " +
                    "cessRateUnits, taxAmount, total, sortOrder, taxBasis, taxMode, treatment) " +
                    "VALUES ('charge-1', 'voucher-1', 'Freight', 500, 180000, 0, 90, 590, 0, " +
                    "'principal_supply', 'exclusive', 'taxable')",
            )
        }
        helper.runMigrationsAndValidate(
            OPENBILL_DATABASE_VERSION,
            DatabaseMigrations.migrationsFor(4),
        ).use { connection ->
            connection.prepare("SELECT COUNT(*) FROM voucher_charge_portions").use { statement ->
                assertTrue(statement.step())
                assertEquals(0L, statement.getLong(0))
            }
            connection.execSQL(
                "INSERT INTO voucher_charge_portions (id, chargeId, voucherId, portionIndex, treatment, " +
                    "taxRateUnits, cessRateUnits, taxableValue, taxAmount, total) " +
                    "VALUES ('charge-1:0', 'charge-1', 'voucher-1', 0, 'taxable', 180000, 0, 500, 90, 590)",
            )
            connection.prepare("SELECT total FROM voucher_charge_portions WHERE chargeId = 'charge-1'").use {
                assertTrue(it.step())
                assertEquals(590L, it.getLong(0))
            }
            connection.prepare("SELECT taxBasis, total FROM voucher_charges WHERE id = 'charge-1'").use {
                assertTrue(it.step())
                assertEquals("principal_supply", it.getText(0))
                assertEquals(590L, it.getLong(1))
            }
        }
    }

    @Test
    fun migrationChainHasNoGaps() {
        val migrations = DatabaseMigrations.migrationsFor(1)
        assertEquals(4, migrations.size)
        assertEquals(1, migrations[0].startVersion)
        assertEquals(2, migrations[0].endVersion)
        assertEquals(2, migrations[1].startVersion)
        assertEquals(3, migrations[1].endVersion)
        assertEquals(3, migrations[2].startVersion)
        assertEquals(4, migrations[2].endVersion)
        assertEquals(4, migrations[3].startVersion)
        assertEquals(5, migrations[3].endVersion)
        assertTrue(DatabaseMigrations.migrationsFor(5).isEmpty())
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
            "voucher_charge_portions",
            "voucher_charges",
            "voucher_lines",
            "voucher_number_sequences",
            "vouchers",
        )
    }
}
