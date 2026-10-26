package app.openbill.core.domain.usecase

import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherStatus
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

/**
 * A new unnumbered draft with this document's party, lines and charges, dated [date]. Serial
 * numbers and resolved batch rows are dropped: a unit cannot be sold twice, and the batch is
 * picked again for the new document.
 */
fun Voucher.duplicateAsDraft(id: String, date: LocalDate, financialYearId: String, newId: (Int) -> String, at: Instant): Voucher =
    copy(
        id = id, financialYearId = financialYearId, number = null, date = date, dueDate = null, referenceVoucherId = null,
        status = VoucherStatus.DRAFT, cancellationReason = null, postedAt = null, cancelledAt = null, restoredAt = null,
        createdAt = at, updatedAt = at, deletedAt = null,
        lines = lines.mapIndexed { i, l -> l.copy(id = newId(i), batchId = null, serialNumbers = emptyList()) },
        charges = charges.mapIndexed { i, c -> c.copy(id = newId(lines.size + i)) },
    )
