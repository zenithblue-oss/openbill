package app.openbill.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.openbill.core.database.dao.AttachmentDao
import app.openbill.core.database.dao.AuditDao
import app.openbill.core.database.dao.BatchDao
import app.openbill.core.database.dao.CatalogDao
import app.openbill.core.database.dao.FinancialYearDao
import app.openbill.core.database.dao.FirmDao
import app.openbill.core.database.dao.FirmSettingsDao
import app.openbill.core.database.dao.ItemDao
import app.openbill.core.database.dao.LedgerDao
import app.openbill.core.database.dao.PartyDao
import app.openbill.core.database.dao.PaymentAllocationDao
import app.openbill.core.database.dao.PaymentDetailsDao
import app.openbill.core.database.dao.RecurringDao
import app.openbill.core.database.dao.SerialNumberDao
import app.openbill.core.database.dao.StockMovementDao
import app.openbill.core.database.dao.VoucherDao
import app.openbill.core.database.dao.VoucherNumberSequenceDao

const val OPENBILL_DATABASE_VERSION = 20

@Database(
    entities = [
        FirmEntity::class,
        FirmSettingsEntity::class,
        FinancialYearEntity::class,
        PartyEntity::class,
        PartyAddressEntity::class,
        ItemCategoryEntity::class,
        UnitEntity::class,
        GodownEntity::class,
        ItemEntity::class,
        BatchEntity::class,
        SerialNumberEntity::class,
        VoucherEntity::class,
        VoucherLineEntity::class,
        VoucherChargeEntity::class,
        VoucherChargePortionEntity::class,
        VoucherNumberSequenceEntity::class,
        PaymentAllocationEntity::class,
        PaymentDetailsEntity::class,
        StockMovementEntity::class,
        AccountEntity::class,
        JournalEntryEntity::class,
        AuditEventEntity::class,
        AttachmentEntity::class,
        RecurringScheduleEntity::class,
        UserEntity::class,
        PartyItemPriceEntity::class,
        SyncStateEntity::class,
        SyncOutboxEntity::class,
        SyncInboxEntity::class,
    ],
    version = OPENBILL_DATABASE_VERSION,
    exportSchema = true,
)
abstract class OpenBillDatabase : RoomDatabase() {
    abstract fun firmDao(): FirmDao

    abstract fun firmSettingsDao(): FirmSettingsDao

    abstract fun financialYearDao(): FinancialYearDao

    abstract fun partyDao(): PartyDao

    abstract fun catalogDao(): CatalogDao

    abstract fun itemDao(): ItemDao

    abstract fun batchDao(): BatchDao

    abstract fun serialNumberDao(): SerialNumberDao

    abstract fun voucherDao(): VoucherDao

    abstract fun voucherNumberSequenceDao(): VoucherNumberSequenceDao

    abstract fun paymentAllocationDao(): PaymentAllocationDao

    abstract fun paymentDetailsDao(): PaymentDetailsDao

    abstract fun stockMovementDao(): StockMovementDao

    abstract fun ledgerDao(): LedgerDao

    abstract fun auditDao(): AuditDao

    abstract fun attachmentDao(): AttachmentDao

    abstract fun recurringDao(): RecurringDao

    abstract fun userDao(): app.openbill.core.database.dao.UserDao

    abstract fun syncDao(): app.openbill.core.database.dao.SyncDao
}

private object AuditTriggerCallback : RoomDatabase.Callback() {
    override fun onOpen(connection: SQLiteConnection) {
        AuditTriggers.install(connection)
        // Seeding is not a business write: drop the sync triggers first so it is never recorded.
        SyncTriggers.drop(connection)
        connection.execSQL(Users.SEED_OWNER)
        // A new install, or a restored backup (whose sync identity was wiped), gets a fresh device id.
        connection.prepare("INSERT OR IGNORE INTO sync_state (id, deviceId, enabled, hlc) VALUES (1, ?, 0, 0)").use {
            it.bindText(1, app.openbill.core.model.Ulid.create(kotlin.time.Clock.System.now().toEpochMilliseconds()).toString())
            it.step()
        }
        SyncTriggers.install(connection)
    }
}

fun openBillDatabase(
    builder: RoomDatabase.Builder<OpenBillDatabase>,
): OpenBillDatabase = builder
    .setDriver(BundledSQLiteDriver())
    .addMigrations(*DatabaseMigrations.all.toTypedArray())
    .addCallback(AuditTriggerCallback)
    .build()
