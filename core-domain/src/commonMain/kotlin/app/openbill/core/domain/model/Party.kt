package app.openbill.core.domain.model

import app.openbill.core.model.Money
import kotlin.time.Instant
import kotlinx.datetime.plus

data class PartyAddress(
    val id: String,
    val kind: AddressKind,
    val label: String? = null,
    val line1: String,
    val line2: String? = null,
    val city: String,
    val stateCode: String,
    val pincode: String? = null,
    val country: String = "IN",
    val isDefault: Boolean = false,
    /** Ship-to GSTIN (shipping addresses); null = unregistered site or the party's own GSTIN. */
    val gstin: String? = null,
) {
    init {
        require(id.isNotBlank()) { "PartyAddress id cannot be blank" }
        require(line1.isNotBlank()) { "PartyAddress line1 cannot be blank" }
        require(city.isNotBlank()) { "PartyAddress city cannot be blank" }
        require(stateCode.length == 2) { "PartyAddress state code must have two characters" }
    }
}

/** Special sale rate for [partyId] and [itemId]; new invoice lines start at it instead of the item price. */
data class PartyItemPrice(val partyId: String, val itemId: String, val price: Money) {
    init {
        require(price >= Money.ZERO) { "Party price cannot be negative" }
    }
}

data class Party(
    val id: String,
    val firmId: String,
    val name: String,
    val type: PartyType,
    val phone: String? = null,
    val email: String? = null,
    val gstin: String? = null,
    val stateCode: String? = null,
    val openingBalance: Money = Money.ZERO,
    val openingBalanceType: OpeningBalanceType = OpeningBalanceType.DEBIT,
    val creditLimit: Money? = null,
    val notes: String? = null,
    val addresses: List<PartyAddress> = emptyList(),
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
    /** ISO 4217 code (not INR) new export/SEZ invoices to this party start in; null for rupees. */
    val defaultCurrency: String? = null,
    /** Credit terms: a sale's due date defaults to its date plus these days. */
    val creditDays: Int? = null,
    /** User-defined group for filtering and reports; null = none. */
    val category: String? = null,
) {
    init {
        require(id.isNotBlank()) { "Party id cannot be blank" }
        require(creditDays == null || creditDays in 0..365) { "Credit days must be 0 to 365" }
        require(creditLimit == null || creditLimit >= Money.ZERO) { "Credit limit cannot be negative" }
        require(category == null || (category.isNotBlank() && category.length <= 40)) { "Group is 1 to 40 characters" }
        defaultCurrency?.let { require(ExportDetails.isCurrencyCode(it) && it != "INR") { "Default currency must be a 3-letter code other than INR" } }
        require(firmId.isNotBlank()) { "Party firmId cannot be blank" }
        require(name.isNotBlank()) { "Party name cannot be blank" }
        require(addresses.map { it.id }.distinct().size == addresses.size) {
            "Party address ids must be unique"
        }
        require(addresses.groupBy { it.kind }.values.all { list -> list.count { it.isDefault } <= 1 }) {
            "Party can have at most one default address of each kind"
        }
        require(updatedAt >= createdAt) { "Party updatedAt cannot precede createdAt" }
    }

    /** Due date of a document dated [date] under this party's credit terms; null without terms. */
    fun dueDateFor(date: kotlinx.datetime.LocalDate): kotlinx.datetime.LocalDate? =
        creditDays?.let { date.plus(kotlinx.datetime.DatePeriod(days = it)) }

    /** State where goods are delivered (default, else first, shipping address); decides place of supply for goods (IGST Act s.10(1)). */
    fun shipToStateCode(): String? {
        val ship = addresses.filter { it.kind == AddressKind.SHIPPING }
        return (ship.firstOrNull { it.isDefault } ?: ship.firstOrNull())?.stateCode
    }
}
