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
        assertEquals(11, OPENBILL_DATABASE_VERSION)
        assertTrue(Path.of("schemas/app.openbill.core.database.OpenBillDatabase/1.json").exists())
        assertTrue(Path.of("schemas/app.openbill.core.database.OpenBillDatabase/2.json").exists())
        assertTrue(Path.of("schemas/app.openbill.core.database.OpenBillDatabase/3.json").exists())
        assertTrue(Path.of("schemas/app.openbill.core.database.OpenBillDatabase/4.json").exists())
        assertTrue(Path.of("schemas/app.openbill.core.database.OpenBillDatabase/5.json").exists())
        assertTrue(Path.of("schemas/app.openbill.core.database.OpenBillDatabase/6.json").exists())
        assertTrue(Path.of("schemas/app.openbill.core.database.OpenBillDatabase/7.json").exists())
        assertTrue(Path.of("schemas/app.openbill.core.database.OpenBillDatabase/8.json").exists())
        assertTrue(Path.of("schemas/app.openbill.core.database.OpenBillDatabase/9.json").exists())
        assertTrue(Path.of("schemas/app.openbill.core.database.OpenBillDatabase/11.json").exists())

        helper.createDatabase(9).use { connection ->
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
    fun schemaV5MigratesToV6AndKeepsInventoryRows() {
        helper.createDatabase(5).use { connection ->
            connection.execSQL(
                "INSERT INTO firms (id, name, stateCode, addressLine1, city, createdAt, updatedAt, revision) " +
                    "VALUES ('firm-1', 'Firm', '27', 'Line 1', 'Pune', 1, 1, 1)",
            )
            connection.execSQL(
                "INSERT INTO financial_years (id, firmId, startDate, endDate, createdAt, updatedAt) " +
                    "VALUES ('fy-1', 'firm-1', '2026-04-01', '2027-03-31', 1, 1)",
            )
            connection.execSQL(
                "INSERT INTO units (id, firmId, name, symbol, decimalPlaces, createdAt, updatedAt, revision) " +
                    "VALUES ('unit-1', 'firm-1', 'Pieces', 'pcs', 0, 1, 1, 1)",
            )
            connection.execSQL(
                "INSERT INTO godowns (id, firmId, name, isDefault, createdAt, updatedAt, revision) " +
                    "VALUES ('g-1', 'firm-1', 'Main', 1, 1, 1, 1)",
            )
            connection.execSQL(
                "INSERT INTO items (id, firmId, name, sku, unitId, isService, taxRateUnits, cessRateUnits, " +
                    "salePrice, purchasePrice, trackInventory, trackBatch, trackSerial, createdAt, updatedAt, revision) " +
                    "VALUES ('item-1', 'firm-1', 'Widget', 'W1', 'unit-1', 0, 0, 0, 1000, 800, 1, 0, 0, 1, 1, 1)",
            )
            connection.execSQL(
                "INSERT INTO vouchers (id, firmId, financialYearId, type, date, status, " +
                    "placeOfSupplyStateCode, subtotal, discount, taxable, tax, roundOff, total, " +
                    "createdAt, updatedAt, revision) VALUES ('voucher-1', 'firm-1', 'fy-1', 'purchase', " +
                    "'2026-05-10', 'draft', '27', 8000, 0, 8000, 0, 0, 8000, 1, 1, 1)",
            )
            connection.execSQL(
                "INSERT INTO stock_movements (id, firmId, itemId, godownId, voucherId, date, quantity, createdAt) " +
                    "VALUES ('m-1', 'firm-1', 'item-1', 'g-1', 'voucher-1', '2026-05-10', 100000, 1)",
            )
        }
        helper.runMigrationsAndValidate(
            OPENBILL_DATABASE_VERSION,
            DatabaseMigrations.migrationsFor(5),
        ).use { connection ->
            connection.prepare("SELECT quantity, unitCost FROM stock_movements WHERE id = 'm-1'").use {
                assertTrue(it.step())
                assertEquals(100000L, it.getLong(0))
                assertTrue(it.isNull(1))
            }
            connection.prepare("SELECT sku, barcode FROM items WHERE id = 'item-1'").use {
                assertTrue(it.step())
                assertEquals("W1", it.getText(0))
                assertTrue(it.isNull(1))
            }
            connection.execSQL("UPDATE items SET barcode = 'OB123' WHERE id = 'item-1'")
            connection.execSQL("UPDATE vouchers SET referenceNumber = 'SUP-9' WHERE id = 'voucher-1'")
            connection.prepare("SELECT referenceNumber FROM vouchers WHERE id = 'voucher-1'").use {
                assertTrue(it.step())
                assertEquals("SUP-9", it.getText(0))
            }
        }
    }

    @Test
    fun migrationChainHasNoGaps() {
        val migrations = DatabaseMigrations.migrationsFor(1)
        assertEquals(10, migrations.size)
        assertEquals(1, migrations[0].startVersion)
        assertEquals(2, migrations[0].endVersion)
        assertEquals(2, migrations[1].startVersion)
        assertEquals(3, migrations[1].endVersion)
        assertEquals(3, migrations[2].startVersion)
        assertEquals(4, migrations[2].endVersion)
        assertEquals(4, migrations[3].startVersion)
        assertEquals(5, migrations[3].endVersion)
        assertEquals(6, migrations[4].endVersion)
        assertEquals(7, migrations[5].endVersion)
        assertEquals(8, migrations[6].endVersion)
        assertEquals(9, migrations[7].endVersion)
        assertEquals(10, migrations[8].endVersion)
        assertEquals(11, migrations[9].endVersion)
        assertTrue(DatabaseMigrations.migrationsFor(11).isEmpty())
    }

    @Test
    fun schemaV6MigratesToV7AndKeepsLedgerRows() {
        helper.createDatabase(6).use { connection ->
            connection.execSQL(
                "INSERT INTO firms (id, name, stateCode, addressLine1, city, createdAt, updatedAt, revision) " +
                    "VALUES ('firm-1', 'Firm', '27', 'Line 1', 'Pune', 1, 1, 1)",
            )
            connection.execSQL(
                "INSERT INTO financial_years (id, firmId, startDate, endDate, createdAt, updatedAt) " +
                    "VALUES ('fy-1', 'firm-1', '2026-04-01', '2027-03-31', 1, 1)",
            )
            connection.execSQL(
                "INSERT INTO accounts (id, firmId, name, type, code, openingBalance, isDefault, createdAt, updatedAt, revision) " +
                    "VALUES ('cash', 'firm-1', 'Cash', 'cash', 'CASH', 0, 1, 1, 1, 1)",
            )
            connection.execSQL(
                "INSERT INTO vouchers (id, firmId, financialYearId, type, date, status, " +
                    "placeOfSupplyStateCode, subtotal, discount, taxable, tax, roundOff, total, " +
                    "createdAt, updatedAt, revision) VALUES ('voucher-1', 'firm-1', 'fy-1', 'payment_in', " +
                    "'2026-05-10', 'draft', '27', 0, 0, 0, 0, 0, 500, 1, 1, 1)",
            )
            connection.execSQL(
                "INSERT INTO journal_entries (id, firmId, voucherId, accountId, date, debit, credit, createdAt) " +
                    "VALUES ('j-1', 'firm-1', 'voucher-1', 'cash', '2026-05-10', 500, 0, 1)",
            )
        }
        helper.runMigrationsAndValidate(7, DatabaseMigrations.migrationsFor(6)).use { connection ->
            connection.prepare("SELECT debit, clearedOn FROM journal_entries WHERE id = 'j-1'").use {
                assertTrue(it.step())
                assertEquals(500L, it.getLong(0))
                assertTrue(it.isNull(1))
            }
            connection.prepare("SELECT ledgerAccountId FROM vouchers WHERE id = 'voucher-1'").use {
                assertTrue(it.step())
                assertTrue(it.isNull(0))
            }
            connection.execSQL("UPDATE journal_entries SET clearedOn = '2026-05-12' WHERE id = 'j-1'")
        }
    }

    @Test
    fun schemaV7MigratesToV8AndLeavesBooksUnlocked() {
        helper.createDatabase(7).use { connection ->
            connection.execSQL(
                "INSERT INTO firms (id, name, stateCode, addressLine1, city, createdAt, updatedAt, revision) " +
                    "VALUES ('firm-1', 'Firm', '27', 'Line 1', 'Pune', 1, 1, 1)",
            )
            connection.execSQL(
                "INSERT INTO firm_settings (firmId, financialYearStartMonth, invoicePrefix, taxEnabled, " +
                    "roundToRupee, roundingMode, negativeStockAllowed, createdAt, updatedAt) " +
                    "VALUES ('firm-1', 4, 'INV', 1, 1, 'half_up', 0, 1, 1)",
            )
        }
        helper.runMigrationsAndValidate(8, DatabaseMigrations.migrationsFor(7)).use { connection ->
            connection.prepare("SELECT lockDate FROM firm_settings WHERE firmId = 'firm-1'").use {
                assertTrue(it.step())
                assertTrue(it.isNull(0))
            }
        }
    }

    @Test
    fun schemaV8MigratesToV9WithEmptyShipToGstinAndAlternateUnit() {
        helper.createDatabase(8).use { connection ->
            connection.execSQL(
                "INSERT INTO firms (id, name, stateCode, addressLine1, city, createdAt, updatedAt, revision) " +
                    "VALUES ('firm-1', 'Firm', '27', 'Line 1', 'Pune', 1, 1, 1)",
            )
        }
        helper.runMigrationsAndValidate(9, DatabaseMigrations.migrationsFor(8)).use { connection ->
            connection.prepare("SELECT COUNT(*) FROM firms").use { assertTrue(it.step()); assertEquals(1L, it.getLong(0)) }
            connection.execSQL("UPDATE party_addresses SET gstin = NULL")
            connection.execSQL("UPDATE items SET alternateUnitId = NULL, alternateUnitFactor = NULL")
        }
    }

    @Test
    fun schemaV9MigratesToV10WithScaleIndexes() {
        helper.createDatabase(9).use { connection ->
            connection.execSQL(
                "INSERT INTO firms (id, name, stateCode, addressLine1, city, createdAt, updatedAt, revision) " +
                    "VALUES ('firm-1', 'Firm', '27', 'Line 1', 'Pune', 1, 1, 1)",
            )
        }
        helper.runMigrationsAndValidate(10, DatabaseMigrations.migrationsFor(9, 10)).use { connection ->
            val names = mutableListOf<String>()
            connection.prepare("SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'index_%' ORDER BY name").use {
                while (it.step()) names += it.getText(0)
            }
            assertTrue("index_vouchers_firmId_partyId_type_status_date" in names)
            assertTrue("index_journal_entries_accountId_date_createdAt" in names)
            assertTrue("index_vouchers_firmId_partyId" !in names)
            assertTrue("index_journal_entries_accountId" !in names)
            connection.prepare(
                "EXPLAIN QUERY PLAN SELECT * FROM vouchers WHERE firmId = 'f' AND partyId = 'p' " +
                    "AND type = 'sale' AND status = 'posted' AND deletedAt IS NULL ORDER BY date",
            ).use { s ->
                assertTrue(s.step())
                assertTrue("index_vouchers_firmId_partyId_type_status_date" in s.getText(3), s.getText(3))
            }
        }
    }

    @Test
    fun schemaV10MigratesToV11WithCoveringStockIndex() {
        helper.createDatabase(10).close()
        helper.runMigrationsAndValidate(11, DatabaseMigrations.migrationsFor(10)).use { connection ->
            connection.prepare(
                "EXPLAIN QUERY PLAN SELECT itemId, date < '2026-04-01', quantity, unitCost FROM stock_movements " +
                    "WHERE firmId = 'f' AND date <= '2027-03-31' ORDER BY itemId, date, createdAt, id",
            ).use { s ->
                assertTrue(s.step())
                assertTrue("COVERING INDEX index_stock_movements_firmId_itemId_date_createdAt_id_godownId_quantity_unitCost" in s.getText(3), s.getText(3))
                assertTrue(!s.step(), "no temp b-tree sort")
            }
        }
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
