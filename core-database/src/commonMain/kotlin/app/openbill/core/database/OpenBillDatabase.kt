package app.openbill.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
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
import app.openbill.core.database.dao.SerialNumberDao
import app.openbill.core.database.dao.StockMovementDao
import app.openbill.core.database.dao.VoucherDao
import app.openbill.core.database.dao.VoucherNumberSequenceDao

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
        VoucherNumberSequenceEntity::class,
        PaymentAllocationEntity::class,
        StockMovementEntity::class,
        AccountEntity::class,
        JournalEntryEntity::class,
        AuditEventEntity::class,
        AttachmentEntity::class,
    ],
    version = 1,
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

    abstract fun stockMovementDao(): StockMovementDao

    abstract fun ledgerDao(): LedgerDao

    abstract fun auditDao(): AuditDao

    abstract fun attachmentDao(): AttachmentDao
}

fun openBillDatabase(
    builder: RoomDatabase.Builder<OpenBillDatabase>,
): OpenBillDatabase = builder
    .setDriver(BundledSQLiteDriver())
    .build()
