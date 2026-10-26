package app.openbill.core.database

import androidx.room.Embedded
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "payment_allocations",
    foreignKeys = [
        ForeignKey(
            entity = FirmEntity::class,
            parentColumns = ["id"],
            childColumns = ["firmId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = VoucherEntity::class,
            parentColumns = ["id"],
            childColumns = ["paymentVoucherId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = VoucherEntity::class,
            parentColumns = ["id"],
            childColumns = ["invoiceVoucherId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["firmId"]),
        Index(value = ["paymentVoucherId", "invoiceVoucherId"], unique = true),
        Index(value = ["invoiceVoucherId"]),
    ],
)
data class PaymentAllocationEntity(
    @PrimaryKey val id: String,
    val firmId: String,
    val paymentVoucherId: String,
    val invoiceVoucherId: String,
    val amount: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "stock_movements",
    foreignKeys = [
        ForeignKey(
            entity = FirmEntity::class,
            parentColumns = ["id"],
            childColumns = ["firmId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = GodownEntity::class,
            parentColumns = ["id"],
            childColumns = ["godownId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = BatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["batchId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = VoucherEntity::class,
            parentColumns = ["id"],
            childColumns = ["voucherId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = VoucherLineEntity::class,
            parentColumns = ["id"],
            childColumns = ["voucherLineId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["firmId", "itemId", "godownId", "date"]),
        Index(value = ["voucherId"]),
        Index(value = ["voucherLineId"]),
        Index(value = ["batchId"]),
        Index(value = ["itemId"]),
        Index(value = ["godownId"]),
    ],
)
data class StockMovementEntity(
    @PrimaryKey val id: String,
    val firmId: String,
    val itemId: String,
    val godownId: String,
    val batchId: String?,
    val voucherId: String,
    val voucherLineId: String?,
    val date: String,
    val quantity: Long,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "NULL") val unitCost: Long? = null,
)

@Entity(
    tableName = "accounts",
    foreignKeys = [
        ForeignKey(
            entity = FirmEntity::class,
            parentColumns = ["id"],
            childColumns = ["firmId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["firmId", "name"], unique = true),
        Index(value = ["firmId", "code"], unique = true),
        Index(value = ["firmId", "type"]),
        Index(value = ["firmId", "isDefault"]),
        Index(value = ["deletedAt"]),
    ],
)
data class AccountEntity(
    @PrimaryKey val id: String,
    val firmId: String,
    val name: String,
    val type: String,
    val code: String?,
    val openingBalance: Long,
    val isDefault: Boolean,
    @Embedded val sync: SyncMetadata,
)

@Entity(
    tableName = "journal_entries",
    foreignKeys = [
        ForeignKey(
            entity = FirmEntity::class,
            parentColumns = ["id"],
            childColumns = ["firmId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = VoucherEntity::class,
            parentColumns = ["id"],
            childColumns = ["voucherId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["firmId", "date"]),
        Index(value = ["voucherId"]),
        Index(value = ["accountId"]),
    ],
)
data class JournalEntryEntity(
    @PrimaryKey val id: String,
    val firmId: String,
    val voucherId: String,
    val accountId: String,
    val date: String,
    val debit: Long,
    val credit: Long,
    val narration: String?,
    val createdAt: Long,
)
