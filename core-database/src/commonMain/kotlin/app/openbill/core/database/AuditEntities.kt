package app.openbill.core.database

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "audit_events",
    foreignKeys = [
        ForeignKey(
            entity = FirmEntity::class,
            parentColumns = ["id"],
            childColumns = ["firmId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["firmId", "createdAt"]),
        Index(value = ["entityType", "entityId"]),
        Index(value = ["voucherId"]),
    ],
)
data class AuditEventEntity(
    @PrimaryKey val id: String,
    val firmId: String,
    val entityType: String,
    val entityId: String,
    val voucherId: String?,
    val action: String,
    val detailsJson: String?,
    val actor: String?,
    val createdAt: Long,
)

/** Install-wide sign-in users (not per firm). The fixed `owner` row signs in with the app-lock PIN. */
@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: String,
    val name: String,
    val role: String,
    val active: Boolean,
    val pinHash: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

object Users {
    const val CREATE_TABLE = "CREATE TABLE IF NOT EXISTS `users` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `role` TEXT NOT NULL, " +
        "`active` INTEGER NOT NULL, `pinHash` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"

    /** Every install, migrated or new, has its owner; history without a user belongs to them. */
    const val SEED_OWNER = "INSERT OR IGNORE INTO `users` (`id`, `name`, `role`, `active`, `pinHash`, `createdAt`, `updatedAt`) " +
        "VALUES ('owner', 'Owner', 'owner', 1, NULL, 0, 0)"
}

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = FirmEntity::class,
            parentColumns = ["id"],
            childColumns = ["firmId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["firmId", "entityType", "entityId"]),
        Index(value = ["deletedAt"]),
    ],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val firmId: String,
    val entityType: String,
    val entityId: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val storagePath: String,
    val sha256: String,
    @Embedded val sync: SyncMetadata,
)
