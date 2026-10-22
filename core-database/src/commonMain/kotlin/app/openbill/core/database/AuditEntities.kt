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
