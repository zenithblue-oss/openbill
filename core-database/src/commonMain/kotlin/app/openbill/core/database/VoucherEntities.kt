package app.openbill.core.database

import androidx.room.ColumnInfo
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
        Index(value = ["firmId", "partyId", "type", "status", "date"]),
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
    @ColumnInfo(defaultValue = "NULL") val referenceNumber: String? = null,
    @ColumnInfo(defaultValue = "NULL") val ledgerAccountId: String? = null,
    // v12: invoice document fields (all optional; existing rows keep their meaning).
    @ColumnInfo(defaultValue = "0") val reverseCharge: Boolean = false,
    @ColumnInfo(defaultValue = "NULL") val transporterName: String? = null,
    @ColumnInfo(defaultValue = "NULL") val transporterId: String? = null,
    @ColumnInfo(defaultValue = "NULL") val transportMode: String? = null,
    @ColumnInfo(defaultValue = "NULL") val vehicleNumber: String? = null,
    @ColumnInfo(defaultValue = "NULL") val transportDocNumber: String? = null,
    @ColumnInfo(defaultValue = "NULL") val transportDocDate: String? = null,
    @ColumnInfo(defaultValue = "NULL") val transportDistanceKm: Int? = null,
    /** GSTR-1 code: WPAY, WOPAY, SEZWP or SEZWOP. */
    @ColumnInfo(defaultValue = "NULL") val exportType: String? = null,
    @ColumnInfo(defaultValue = "NULL") val shippingBillNumber: String? = null,
    @ColumnInfo(defaultValue = "NULL") val shippingBillDate: String? = null,
    @ColumnInfo(defaultValue = "NULL") val exportPortCode: String? = null,
    @ColumnInfo(defaultValue = "NULL") val lutReference: String? = null,
    @ColumnInfo(defaultValue = "NULL") val currencyNote: String? = null,
    @ColumnInfo(defaultValue = "NULL") val ecommerceGstin: String? = null,
    @ColumnInfo(defaultValue = "NULL") val poNumber: String? = null,
    @ColumnInfo(defaultValue = "NULL") val poDate: String? = null,
    @ColumnInfo(defaultValue = "NULL") val ewayBillNumber: String? = null,
    /** One "label<TAB>value" pair per line. */
    @ColumnInfo(defaultValue = "NULL") val customFields: String? = null,
    /** v13, orders only: `partial` or `complete` once invoiced/billed; NULL while nothing is. */
    @ColumnInfo(defaultValue = "NULL") val fulfilment: String? = null,
    /** v14, POS: epoch millis the draft was put on hold at the counter; NULL otherwise. */
    @ColumnInfo(defaultValue = "NULL") val heldAt: Long? = null,
    /** v15, foreign-currency invoices: ISO 4217 code; NULL for rupees. */
    @ColumnInfo(defaultValue = "NULL") val currency: String? = null,
    /** v15: rupees per unit of [currency], scaled like quantities (4 decimals). */
    @ColumnInfo(defaultValue = "NULL") val exchangeRate: Long? = null,
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
    @ColumnInfo(defaultValue = "NULL") val batchNumber: String? = null,
    @ColumnInfo(defaultValue = "NULL") val expiryDate: String? = null,
    /** Newline-separated serial/IMEI numbers. */
    @ColumnInfo(defaultValue = "NULL") val serialNumbers: String? = null,
    @ColumnInfo(defaultValue = "NULL") val toGodownId: String? = null,
    /** v12: bonus quantity (scaled like [quantity]) that moves stock without value. */
    @ColumnInfo(defaultValue = "0") val freeQuantity: Long = 0,
    /** v12: false when the line's GST is not claimable as input credit. */
    @ColumnInfo(defaultValue = "1") val itcEligible: Boolean = true,
    /** v13: the order line an invoice/bill line fulfils (no foreign key: orders keep their lines). */
    @ColumnInfo(defaultValue = "NULL") val sourceLineId: String? = null,
    /** v15, foreign-currency invoices: unit rate and discount as entered, in paise-like minor units. */
    @ColumnInfo(defaultValue = "NULL") val foreignRate: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val foreignDiscount: Long? = null,
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
    /** `non_taxable`, `explicit_rate`, or `principal_supply`. Pre-v4 rows were explicit. */
    @ColumnInfo(defaultValue = "explicit_rate") val taxBasis: String = "explicit_rate",
    @ColumnInfo(defaultValue = "exclusive") val taxMode: String = "exclusive",
    /** Applied treatment, lowercase `TaxTreatment` name. */
    @ColumnInfo(defaultValue = "taxable") val treatment: String = "taxable",
    val hsnSac: String? = null,
    /** v15, foreign-currency invoices: the amount as entered, in minor units of the currency. */
    @ColumnInfo(defaultValue = "NULL") val foreignAmount: Long? = null,
)

/**
 * One rate portion of a proportionally apportioned charge. Rows exist only when a charge was
 * split across more than one rate group; a charge without rows is a single portion described
 * by its own columns. Portion sums equal the parent charge's taxable, tax and total.
 */
@Entity(
    tableName = "voucher_charge_portions",
    foreignKeys = [
        ForeignKey(
            entity = VoucherChargeEntity::class,
            parentColumns = ["id"],
            childColumns = ["chargeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["chargeId", "portionIndex"], unique = true),
        Index(value = ["voucherId"]),
    ],
)
data class VoucherChargePortionEntity(
    @PrimaryKey val id: String,
    val chargeId: String,
    /** Denormalized for per-voucher loading. */
    val voucherId: String,
    val portionIndex: Int,
    /** Applied treatment, lowercase `TaxTreatment` name. */
    val treatment: String,
    val taxRateUnits: Long,
    val cessRateUnits: Long,
    val taxableValue: Long,
    val taxAmount: Long,
    val total: Long,
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
