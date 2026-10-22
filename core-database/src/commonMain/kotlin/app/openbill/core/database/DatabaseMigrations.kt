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

    val all: List<Migration> = listOf(MIGRATION_1_2, MIGRATION_2_3)

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
