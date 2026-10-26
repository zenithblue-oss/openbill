package app.openbill.core.database

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "firm_settings",
    foreignKeys = [
        ForeignKey(
            entity = FirmEntity::class,
            parentColumns = ["id"],
            childColumns = ["firmId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class FirmSettingsEntity(
    @PrimaryKey val firmId: String,
    val financialYearStartMonth: Int,
    val invoicePrefix: String,
    val defaultGodownId: String?,
    val taxEnabled: Boolean,
    val roundToRupee: Boolean,
    val roundingMode: String,
    val negativeStockAllowed: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    /** Books lock: vouchers dated on or before this ISO date cannot be posted or cancelled. */
    @ColumnInfo(defaultValue = "NULL") val lockDate: String? = null,
)

@Entity(
    tableName = "financial_years",
    foreignKeys = [
        ForeignKey(
            entity = FirmEntity::class,
            parentColumns = ["id"],
            childColumns = ["firmId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["firmId", "startDate"], unique = true),
        Index(value = ["firmId", "endDate"]),
    ],
)
data class FinancialYearEntity(
    @PrimaryKey val id: String,
    val firmId: String,
    val startDate: String,
    val endDate: String,
    val closedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "parties",
    foreignKeys = [
        ForeignKey(
            entity = FirmEntity::class,
            parentColumns = ["id"],
            childColumns = ["firmId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["firmId", "name"]),
        Index(value = ["firmId", "phone"]),
        Index(value = ["firmId", "gstin"]),
        Index(value = ["deletedAt"]),
    ],
)
data class PartyEntity(
    @PrimaryKey val id: String,
    val firmId: String,
    val name: String,
    val type: String,
    val phone: String?,
    val email: String?,
    val gstin: String?,
    val stateCode: String?,
    val openingBalance: Long,
    val openingBalanceType: String,
    val creditLimit: Long?,
    val notes: String?,
    @Embedded val sync: SyncMetadata,
)

@Entity(
    tableName = "party_addresses",
    foreignKeys = [
        ForeignKey(
            entity = PartyEntity::class,
            parentColumns = ["id"],
            childColumns = ["partyId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["partyId"]),
        Index(value = ["partyId", "kind", "isDefault"]),
    ],
)
data class PartyAddressEntity(
    @PrimaryKey val id: String,
    val partyId: String,
    val kind: String,
    val label: String?,
    val line1: String,
    val line2: String?,
    val city: String,
    val stateCode: String,
    val pincode: String?,
    val country: String,
    val isDefault: Boolean,
    @Embedded val sync: SyncMetadata,
)

@Entity(
    tableName = "item_categories",
    foreignKeys = [
        ForeignKey(
            entity = FirmEntity::class,
            parentColumns = ["id"],
            childColumns = ["firmId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ItemCategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["firmId", "name"]),
        Index(value = ["parentId"]),
        Index(value = ["deletedAt"]),
    ],
)
data class ItemCategoryEntity(
    @PrimaryKey val id: String,
    val firmId: String,
    val name: String,
    val parentId: String?,
    @Embedded val sync: SyncMetadata,
)

@Entity(
    tableName = "units",
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
        Index(value = ["deletedAt"]),
    ],
)
data class UnitEntity(
    @PrimaryKey val id: String,
    val firmId: String,
    val name: String,
    val symbol: String,
    val decimalPlaces: Int,
    @Embedded val sync: SyncMetadata,
)

@Entity(
    tableName = "godowns",
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
        Index(value = ["firmId", "isDefault"]),
        Index(value = ["deletedAt"]),
    ],
)
data class GodownEntity(
    @PrimaryKey val id: String,
    val firmId: String,
    val name: String,
    val address: String?,
    val isDefault: Boolean,
    @Embedded val sync: SyncMetadata,
)

@Entity(
    tableName = "items",
    foreignKeys = [
        ForeignKey(
            entity = FirmEntity::class,
            parentColumns = ["id"],
            childColumns = ["firmId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ItemCategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = UnitEntity::class,
            parentColumns = ["id"],
            childColumns = ["unitId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["firmId", "name"]),
        Index(value = ["firmId", "sku"]),
        Index(value = ["firmId", "barcode"]),
        Index(value = ["firmId", "hsnSac"]),
        Index(value = ["categoryId"]),
        Index(value = ["unitId"]),
        Index(value = ["deletedAt"]),
    ],
)
data class ItemEntity(
    @PrimaryKey val id: String,
    val firmId: String,
    val name: String,
    val sku: String?,
    val categoryId: String?,
    val unitId: String,
    val hsnSac: String?,
    val isService: Boolean,
    val taxRateUnits: Long,
    val cessRateUnits: Long,
    val salePrice: Long,
    val purchasePrice: Long,
    val mrp: Long?,
    val wholesalePrice: Long?,
    val trackInventory: Boolean,
    val trackBatch: Boolean,
    val trackSerial: Boolean,
    val lowStockLevel: Long?,
    val notes: String?,
    @Embedded val sync: SyncMetadata,
    @ColumnInfo(defaultValue = "NULL") val barcode: String? = null,
)

@Entity(
    tableName = "batches",
    foreignKeys = [
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
    ],
    indices = [
        Index(value = ["itemId", "godownId", "batchNumber"], unique = true),
        Index(value = ["godownId"]),
        Index(value = ["expiryDate"]),
        Index(value = ["deletedAt"]),
    ],
)
data class BatchEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val godownId: String,
    val batchNumber: String,
    val expiryDate: String?,
    @Embedded val sync: SyncMetadata,
)

@Entity(
    tableName = "serial_numbers",
    foreignKeys = [
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["itemId", "serialNumber"], unique = true),
        Index(value = ["status"]),
        Index(value = ["voucherLineId"]),
    ],
)
data class SerialNumberEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val serialNumber: String,
    val status: String,
    val voucherLineId: String?,
    @Embedded val sync: SyncMetadata,
)
