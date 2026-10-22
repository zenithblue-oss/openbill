package app.openbill.core.database

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "firms",
    indices = [
        Index(value = ["gstin"]),
        Index(value = ["deletedAt"]),
    ],
)
data class FirmEntity(
    @PrimaryKey val id: String,
    val name: String,
    val legalName: String?,
    val gstin: String?,
    val stateCode: String,
    val addressLine1: String,
    val addressLine2: String?,
    val city: String,
    val pincode: String?,
    val phone: String?,
    val email: String?,
    val logoPath: String?,
    val signaturePath: String?,
    val bankName: String?,
    val bankAccountNumber: String?,
    val bankIfsc: String?,
    val bankBranch: String?,
    @Embedded val sync: SyncMetadata,
)
