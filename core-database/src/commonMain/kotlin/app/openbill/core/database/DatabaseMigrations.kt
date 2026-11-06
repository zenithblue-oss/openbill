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

    private val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("DROP INDEX IF EXISTS `index_stock_movements_firmId_itemId_godownId_date`")
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_stock_movements_firmId_itemId_date_createdAt_id_godownId_quantity_unitCost` " +
                    "ON `stock_movements` (`firmId`, `itemId`, `date`, `createdAt`, `id`, `godownId`, `quantity`, `unitCost`)",
            )
        }
    }

    /**
     * Invoice document fields: reverse charge, transport, export/SEZ, e-commerce operator, PO,
     * e-way bill number and custom fields on vouchers; free quantity and ITC eligibility on
     * lines. Existing rows: no reverse charge, no free quantity, ITC eligible.
     */
    private val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `vouchers` ADD COLUMN `reverseCharge` INTEGER NOT NULL DEFAULT 0")
            connection.execSQL("ALTER TABLE `vouchers` ADD COLUMN `transportDistanceKm` INTEGER DEFAULT NULL")
            for (column in listOf(
                "transporterName", "transporterId", "transportMode", "vehicleNumber", "transportDocNumber", "transportDocDate",
                "exportType", "shippingBillNumber", "shippingBillDate", "exportPortCode", "lutReference", "currencyNote",
                "ecommerceGstin", "poNumber", "poDate", "ewayBillNumber", "customFields",
            )) {
                connection.execSQL("ALTER TABLE `vouchers` ADD COLUMN `$column` TEXT DEFAULT NULL")
            }
            connection.execSQL("ALTER TABLE `voucher_lines` ADD COLUMN `freeQuantity` INTEGER NOT NULL DEFAULT 0")
            connection.execSQL("ALTER TABLE `voucher_lines` ADD COLUMN `itcEligible` INTEGER NOT NULL DEFAULT 1")
        }
    }

    /**
     * Sales/purchase orders and recurring invoices: order fulfilment on vouchers, the order line
     * an invoice line fulfils, and the schedules table. Existing rows: no fulfilment, no source line.
     */
    private val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `vouchers` ADD COLUMN `fulfilment` TEXT DEFAULT NULL")
            connection.execSQL("ALTER TABLE `voucher_lines` ADD COLUMN `sourceLineId` TEXT DEFAULT NULL")
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `recurring_schedules` (`id` TEXT NOT NULL, `firmId` TEXT NOT NULL, " +
                    "`templateVoucherId` TEXT NOT NULL, `name` TEXT NOT NULL, `frequency` TEXT NOT NULL, `repeatEvery` INTEGER NOT NULL, " +
                    "`startDate` TEXT NOT NULL, `endDate` TEXT, `maxCount` INTEGER, `nextIndex` INTEGER NOT NULL, " +
                    "`generatedCount` INTEGER NOT NULL, `autoPost` INTEGER NOT NULL, `paused` INTEGER NOT NULL, `lastError` TEXT, " +
                    "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`firmId`) REFERENCES `firms`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_schedules_firmId` ON `recurring_schedules` (`firmId`)")
        }
    }

    /** POS held bills: when a draft was put on hold. Existing rows: not held. */
    private val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `vouchers` ADD COLUMN `heldAt` INTEGER DEFAULT NULL")
        }
    }

    /** Foreign-currency invoices: currency and rate on vouchers, foreign amounts on lines and charges. Existing rows: rupees. */
    private val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `vouchers` ADD COLUMN `currency` TEXT DEFAULT NULL")
            connection.execSQL("ALTER TABLE `vouchers` ADD COLUMN `exchangeRate` INTEGER DEFAULT NULL")
            connection.execSQL("ALTER TABLE `voucher_lines` ADD COLUMN `foreignRate` INTEGER DEFAULT NULL")
            connection.execSQL("ALTER TABLE `voucher_lines` ADD COLUMN `foreignDiscount` INTEGER DEFAULT NULL")
            connection.execSQL("ALTER TABLE `voucher_charges` ADD COLUMN `foreignAmount` INTEGER DEFAULT NULL")
        }
    }

    /** Local users: the table plus the owner row, so existing history and the app-lock holder become the owner's. */
    private val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(Users.CREATE_TABLE)
            connection.execSQL(Users.SEED_OWNER)
        }
    }

    val all: List<Migration> = listOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16)

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
