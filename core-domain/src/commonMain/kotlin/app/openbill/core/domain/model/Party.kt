package app.openbill.core.domain.model

import app.openbill.core.model.Money
import kotlin.time.Instant

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
) {
    init {
        require(id.isNotBlank()) { "Party id cannot be blank" }
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
}
