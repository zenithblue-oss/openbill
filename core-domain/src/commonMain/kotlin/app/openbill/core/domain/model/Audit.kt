package app.openbill.core.domain.model

import kotlin.time.Instant

/** One append-only audit record (voucher posted/cancelled/restored, books locked, ...). */
data class AuditEvent(
    val id: String,
    val firmId: String,
    val entityType: String,
    val entityId: String,
    val voucherId: String? = null,
    val action: String,
    val detailsJson: String? = null,
    val actor: String? = null,
    val createdAt: Instant,
)
