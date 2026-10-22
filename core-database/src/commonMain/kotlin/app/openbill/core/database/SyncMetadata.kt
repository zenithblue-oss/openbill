package app.openbill.core.database

data class SyncMetadata(
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val revision: Long,
    val originDeviceId: String?,
)
