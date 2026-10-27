package app.openbill.core.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

object DatabaseMigrations {
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "ALTER TABLE voucher_lines ADD COLUMN stockDirection TEXT DEFAULT NULL",
            )
        }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `accounts` ADD COLUMN `code` TEXT")
            connection.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_accounts_firmId_code` " +
                    "ON `accounts` (`firmId`, `code`)",
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `payment_details` (" +
                    "`voucherId` TEXT NOT NULL, `firmId` TEXT NOT NULL, " +
                    "`accountId` TEXT NOT NULL, `method` TEXT NOT NULL, " +
                    "`instrumentNumber` TEXT, `instrumentDate` TEXT, `bankName` TEXT, " +
                    "`branch` TEXT, `upiId` TEXT, `reference` TEXT, `notes` TEXT, " +
                    "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`voucherId`), " +
                    "FOREIGN KEY(`firmId`) REFERENCES `firms`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                    "FOREIGN KEY(`voucherId`) REFERENCES `vouchers`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                    "FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE RESTRICT )",
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_payment_details_accountId` " +
                    "ON `payment_details` (`accountId`)",
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_payment_details_firmId` " +
                    "ON `payment_details` (`firmId`)",
            )
        }
    }

    /**
     * Adds the additional-charge tax basis, mode, applied treatment, and SAC code. Existing
     * charges were entered with an explicit rate on an exclusive amount, so they keep that
     * meaning.
     */
    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "ALTER TABLE `voucher_charges` ADD COLUMN `taxBasis` TEXT NOT NULL " +
                    "DEFAULT 'explicit_rate'",
            )
            connection.execSQL(
                "ALTER TABLE `voucher_charges` ADD COLUMN `taxMode` TEXT NOT NULL " +
                    "DEFAULT 'exclusive'",
            )
            connection.execSQL(
                "ALTER TABLE `voucher_charges` ADD COLUMN `treatment` TEXT NOT NULL " +
                    "DEFAULT 'taxable'",
            )
            connection.execSQL("ALTER TABLE `voucher_charges` ADD COLUMN `hsnSac` TEXT")
        }
    }

    /**
     * Adds per-rate portions for proportionally apportioned charges. Existing charges have no
     * portion rows, which means a single portion described by the charge itself.
     */
    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `voucher_charge_portions` (" +
                    "`id` TEXT NOT NULL, `chargeId` TEXT NOT NULL, `voucherId` TEXT NOT NULL, " +
                    "`portionIndex` INTEGER NOT NULL, `treatment` TEXT NOT NULL, " +
                    "`taxRateUnits` INTEGER NOT NULL, `cessRateUnits` INTEGER NOT NULL, " +
                    "`taxableValue` INTEGER NOT NULL, `taxAmount` INTEGER NOT NULL, " +
                    "`total` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`chargeId`) REFERENCES `voucher_charges`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            connection.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_voucher_charge_portions_chargeId_portionIndex` " +
                    "ON `voucher_charge_portions` (`chargeId`, `portionIndex`)",
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_voucher_charge_portions_voucherId` " +
                    "ON `voucher_charge_portions` (`voucherId`)",
            )
        }
    }

    /**
     * Inventory: item barcodes, inward unit cost on movements (weighted-average valuation),
     * per-line batch number/expiry, serial numbers and transfer destination, and the
     * supplier's bill number on vouchers. All nullable; existing rows keep their meaning
     * (movements without a cost are valued at the running average).
     */
    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `items` ADD COLUMN `barcode` TEXT DEFAULT NULL")
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_items_firmId_barcode` ON `items` (`firmId`, `barcode`)",
            )
            connection.execSQL("ALTER TABLE `stock_movements` ADD COLUMN `unitCost` INTEGER DEFAULT NULL")
            for (column in listOf("batchNumber", "expiryDate", "serialNumbers", "toGodownId")) {
                connection.execSQL("ALTER TABLE `voucher_lines` ADD COLUMN `$column` TEXT DEFAULT NULL")
            }
            connection.execSQL("ALTER TABLE `vouchers` ADD COLUMN `referenceNumber` TEXT DEFAULT NULL")
        }
    }

    /**
     * Money: the expense category / contra destination account on vouchers and the bank
     * reconciliation date on journal entries. Both nullable; existing rows are uncleared.
     */
    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `vouchers` ADD COLUMN `ledgerAccountId` TEXT DEFAULT NULL")
            connection.execSQL("ALTER TABLE `journal_entries` ADD COLUMN `clearedOn` TEXT DEFAULT NULL")
        }
    }

    /** Reports and compliance: the books lock date on firm settings (null = unlocked). */
    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `firm_settings` ADD COLUMN `lockDate` TEXT DEFAULT NULL")
        }
    }

    /** Parties and items: ship-to GSTIN per address, alternate unit with conversion per item. All nullable. */
    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `party_addresses` ADD COLUMN `gstin` TEXT DEFAULT NULL")
            connection.execSQL("ALTER TABLE `items` ADD COLUMN `alternateUnitId` TEXT DEFAULT NULL")
            connection.execSQL("ALTER TABLE `items` ADD COLUMN `alternateUnitFactor` INTEGER DEFAULT NULL")
        }
    }

    /**
     * Scale indexes (docs/14-performance.md): a party's vouchers by type and status in date order
     * (outstanding invoices, party ledgers), and an account's journal lines in date order (paged
     * account ledger). Each replaces an index that is its prefix.
     */
    private val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("DROP INDEX IF EXISTS `index_vouchers_firmId_partyId`")
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_vouchers_firmId_partyId_type_status_date` " +
                    "ON `vouchers` (`firmId`, `partyId`, `type`, `status`, `date`)",
            )
            connection.execSQL("DROP INDEX IF EXISTS `index_journal_entries_accountId`")
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_journal_entries_accountId_date_createdAt` " +
                    "ON `journal_entries` (`accountId`, `date`, `createdAt`)",
            )
        }
    }

    val all: List<Migration> = listOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)

    fun migrationsFor(
        startVersion: Int,
        endVersion: Int = OPENBILL_DATABASE_VERSION,
    ): List<Migration> {
        require(startVersion in 1..endVersion) {
            "Invalid migration range: $startVersion..$endVersion"
        }
        if (startVersion == endVersion) return emptyList()

        val selected = all
            .filter { it.startVersion >= startVersion && it.endVersion <= endVersion }
            .sortedBy { it.startVersion }

        var current = startVersion
        for (migration in selected) {
            require(migration.startVersion == current) {
                "Migration chain has a gap at version $current"
            }
            current = migration.endVersion
        }
        require(current == endVersion) {
            "Migration chain stops at version $current, expected $endVersion"
        }
        return selected
    }
}
