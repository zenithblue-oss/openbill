package app.openbill.core.domain.model

import app.openbill.core.model.RoundingMode
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

data class Firm(
    val id: String,
    val name: String,
    val legalName: String? = null,
    val gstin: String? = null,
    val stateCode: String,
    val addressLine1: String,
    val addressLine2: String? = null,
    val city: String,
    val pincode: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val logoPath: String? = null,
    val signaturePath: String? = null,
    val bankName: String? = null,
    val bankAccountNumber: String? = null,
    val bankIfsc: String? = null,
    val bankBranch: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
) {
    init {
        require(id.isNotBlank()) { "Firm id cannot be blank" }
        require(name.isNotBlank()) { "Firm name cannot be blank" }
        require(stateCode.length == 2) { "Firm state code must have two characters" }
        require(city.isNotBlank()) { "Firm city cannot be blank" }
        require(addressLine1.isNotBlank()) { "Firm address cannot be blank" }
        require(updatedAt >= createdAt) { "Firm updatedAt cannot precede createdAt" }
    }
}

data class FirmSettings(
    val firmId: String,
    val financialYearStartMonth: Int = 4,
    val invoicePrefix: String = "INV",
    val defaultGodownId: String? = null,
    val taxEnabled: Boolean = true,
    val roundToRupee: Boolean = true,
    val roundingMode: RoundingMode = RoundingMode.HALF_UP,
    val negativeStockAllowed: Boolean = false,
    /** Books lock: vouchers dated on or before this date cannot be posted, cancelled or restored. */
    val lockDate: LocalDate? = null,
    /** UPI and card payments (POS and payment entry) must carry a reference; off = optional. */
    val requirePaymentReference: Boolean = false,
    /** Owner's switch: managers may change this business's settings (never owner-only controls). */
    val managersChangeSettings: Boolean = false,
    /** Batches expiring within this many days are flagged on the dashboard; 0 = off. */
    val nearExpiryDays: Int = 30,
    /** Posting a sale that takes a party over its credit limit is blocked; off = warning only. */
    val blockOverCreditLimit: Boolean = false,
    /** Labels of the business's custom item fields, in order. */
    val itemFieldLabels: List<String> = emptyList(),
    /** Custom item field values print under the item name on documents. */
    val printItemFields: Boolean = false,
) {
    init {
        require(firmId.isNotBlank()) { "FirmSettings firmId cannot be blank" }
        require(nearExpiryDays in 0..365) { "Near-expiry window must be 0 to 365 days" }
        require(itemFieldLabels.size <= MAX_ITEM_FIELDS) { "At most $MAX_ITEM_FIELDS custom item fields" }
        require(itemFieldLabels.all { it.isNotBlank() && it.length <= 30 && '\t' !in it && '\n' !in it }) { "Item field labels are 1 to 30 characters" }
        require(itemFieldLabels.map { it.lowercase() }.distinct().size == itemFieldLabels.size) { "Item field labels must be different" }
        require(financialYearStartMonth in 1..12) {
            "Financial year start month must be between 1 and 12"
        }
        require(invoicePrefix.isNotBlank()) { "Invoice prefix cannot be blank" }
    }

    companion object {
        const val MAX_ITEM_FIELDS = 5
    }
}

data class FinancialYear(
    val id: String,
    val firmId: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val closedAt: Instant? = null,
) {
    init {
        require(id.isNotBlank()) { "FinancialYear id cannot be blank" }
        require(firmId.isNotBlank()) { "FinancialYear firmId cannot be blank" }
        require(startDate <= endDate) { "Financial year start date must not be after end date" }
    }
}

/**
 * Last date of the locked period: the later of the firm's lock date and the end of its latest
 * closed financial year; null when nothing is locked.
 */
fun booksLockedThrough(lockDate: LocalDate?, latestClosedYearEnd: LocalDate?): LocalDate? =
    listOfNotNull(lockDate, latestClosedYearEnd).maxOrNull()

/** Throws when [date] falls in the locked period ending [lockedThrough]. */
fun requireBooksOpen(date: LocalDate, lockedThrough: LocalDate?) {
    require(lockedThrough == null || date > lockedThrough) {
        "Books are locked up to $lockedThrough; vouchers dated $date cannot be changed"
    }
}
