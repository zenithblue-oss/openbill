package app.openbill.core.database

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "vouchers",
    foreignKeys = [
        ForeignKey(
            entity = FirmEntity::class,
            parentColumns = ["id"],
            childColumns = ["firmId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = FinancialYearEntity::class,
            parentColumns = ["id"],
            childColumns = ["financialYearId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = PartyEntity::class,
            parentColumns = ["id"],
            childColumns = ["partyId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = VoucherEntity::class,
            parentColumns = ["id"],
            childColumns = ["referenceVoucherId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["firmId", "date"]),
        Index(value = ["firmId", "type", "status"]),
        Index(value = ["firmId", "partyId"]),
        Index(value = ["financialYearId"]),
        Index(value = ["partyId"]),
        Index(value = ["referenceVoucherId"]),
        Index(value = ["firmId", "type", "financialYearId", "number"], unique = true),
        Index(value = ["deletedAt"]),
    ],
)
data class VoucherEntity(
    @PrimaryKey val id: String,
    val firmId: String,
    val financialYearId: String,
    val type: String,
    val number: String?,
    val date: String,
    val dueDate: String?,
    val partyId: String?,
    val referenceVoucherId: String?,
    val status: String,
    val placeOfSupplyStateCode: String,
    val subtotal: Long,
    val discount: Long,
    val taxable: Long,
    val tax: Long,
    val roundOff: Long,
    val total: Long,
    val notes: String?,
    val cancellationReason: String?,
    val postedAt: Long?,
    val cancelledAt: Long?,
    val restoredAt: Long?,
    @Embedded val sync: SyncMetadata,
)

@Entity(
    tableName = "voucher_lines",
    foreignKeys = [
        ForeignKey(
            entity = VoucherEntity::class,
            parentColumns = ["id"],
            childColumns = ["voucherId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = GodownEntity::class,
            parentColumns = ["id"],
            childColumns = ["godownId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = BatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["batchId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["voucherId", "lineNumber"], unique = true),
        Index(value = ["itemId"]),
        Index(value = ["godownId"]),
        Index(value = ["batchId"]),
    ],
)
data class VoucherLineEntity(
    @PrimaryKey val id: String,
    val voucherId: String,
    val lineNumber: Int,
    val itemId: String?,
    val description: String,
    val hsnSac: String?,
    val quantity: Long,
    val rate: Long,
    val discount: Long,
    val taxRateUnits: Long,
    val cessRateUnits: Long,
    val taxMode: String,
    val treatment: String,
    val taxable: Long,
    val tax: Long,
    val total: Long,
    val godownId: String?,
    val batchId: String?,
    val stockDirection: String?,
    @Embedded val sync: SyncMetadata,
)

@Entity(
    tableName = "voucher_charges",
    foreignKeys = [
        ForeignKey(
            entity = VoucherEntity::class,
            parentColumns = ["id"],
            childColumns = ["voucherId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["voucherId", "sortOrder"], unique = true),
    ],
)
data class VoucherChargeEntity(
    @PrimaryKey val id: String,
    val voucherId: String,
    val name: String,
    val amount: Long,
    val taxRateUnits: Long,
    val cessRateUnits: Long,
    val taxAmount: Long,
    val total: Long,
    val sortOrder: Int,
)

@Entity(
    tableName = "voucher_number_sequences",
    primaryKeys = ["firmId", "documentType", "financialYearId"],
    foreignKeys = [
        ForeignKey(
            entity = FirmEntity::class,
            parentColumns = ["id"],
            childColumns = ["firmId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = FinancialYearEntity::class,
            parentColumns = ["id"],
            childColumns = ["financialYearId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["financialYearId"]),
    ],
)
data class VoucherNumberSequenceEntity(
    val firmId: String,
    val documentType: String,
    val financialYearId: String,
    val nextNumber: Long,
    val prefix: String,
    val padding: Int,
    val createdAt: Long,
    val updatedAt: Long,
)
