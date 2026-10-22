package app.openbill.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "payment_details",
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
        Index(value = ["accountId"]),
        Index(value = ["firmId"]),
    ],
)
data class PaymentDetailsEntity(
    @PrimaryKey val voucherId: String,
    val firmId: String,
    val accountId: String,
    val method: String,
    val instrumentNumber: String?,
    val instrumentDate: String?,
    val bankName: String?,
    val branch: String?,
    val upiId: String?,
    val reference: String?,
    val notes: String?,
    val createdAt: Long,
    val updatedAt: Long,
)
