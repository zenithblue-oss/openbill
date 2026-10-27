package app.openbill.core.domain.model

import app.openbill.core.tax.TaxContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class PlaceOfSupplyTest {
    private val now = Instant.fromEpochSeconds(0)
    private fun addr(id: String, state: String, kind: AddressKind = AddressKind.SHIPPING, default: Boolean = false) =
        PartyAddress(id = id, kind = kind, line1 = "L", city = "C", stateCode = state, isDefault = default)

    private fun party(vararg a: PartyAddress) =
        Party("p", "f", "P", PartyType.CUSTOMER, stateCode = "27", addresses = a.toList(), createdAt = now, updatedAt = now)

    @Test
    fun shipToInAnotherStateMakesTheSaleInterState() {
        val p = party(addr("b", "27", AddressKind.BILLING), addr("s", "29"))
        assertEquals("29", p.shipToStateCode())
        assertFalse(TaxContext("27", p.shipToStateCode() ?: p.stateCode!!).isIntraState)
    }

    @Test
    fun defaultShippingWinsAndNoShippingFallsBack() {
        assertEquals("33", party(addr("s1", "29"), addr("s2", "33", default = true)).shipToStateCode())
        val billingOnly = party(addr("b", "29", AddressKind.BILLING))
        assertNull(billingOnly.shipToStateCode())
        assertTrue(TaxContext("27", billingOnly.shipToStateCode() ?: billingOnly.stateCode!!).isIntraState)
    }
}
