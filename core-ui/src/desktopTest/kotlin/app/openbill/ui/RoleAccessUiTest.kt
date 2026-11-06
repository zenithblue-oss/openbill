package app.openbill.ui

import app.openbill.core.domain.model.Access
import app.openbill.core.domain.model.AppUser
import app.openbill.core.domain.model.Permission
import app.openbill.core.domain.model.Role
import app.openbill.core.model.Money
import app.openbill.ui.invoice.LineForm
import app.openbill.ui.invoice.LineRules
import app.openbill.ui.nav.Route
import app.openbill.ui.nav.Tab
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class RoleAccessUiTest {
    @AfterTest
    fun reset() {
        Access.current = null
    }

    @Test
    fun screensNeedTheirPermission() {
        assertEquals(Permission.VIEW_REPORTS, Route.Reports.permission)
        assertEquals(Permission.SETTINGS, Route.BusinessEdit.permission)
        assertEquals(Permission.MANAGE_USERS, Route.Users.permission)
        assertEquals(Permission.CREATE_VOUCHERS, Route.InvoiceEditor(null).permission)
        assertEquals(Permission.MANAGE_MASTERS, Route.ItemEdit(null).permission)
        assertNull(Route.ItemEdit("i").permission, "viewing an item is fine; saving is checked")
        assertNull(Route.Home(Tab.INVOICES).permission)
        assertEquals(Route.Users, Route.fromKey(Route.Users.key))
    }

    @Test
    fun cashierKeepsTheItemPriceInTheLineDialog() {
        val form = LineForm(itemId = "i", description = "Widget", rate = "100.00", listPrices = listOf(Money.of(10_000)))
        assertEquals(emptyMap(), LineRules.validate(form) { "k" }.second)
        assertEquals(emptyMap(), LineRules.validate(form.copy(rate = "90")) { "k" }.second, "owner may change it")
        Access.current = AppUser("u", "Ravi", Role.CASHIER, pinHash = "h", createdAt = Instant.fromEpochMilliseconds(0))
        assertEquals(emptyMap(), LineRules.validate(form) { "k" }.second)
        assertEquals(setOf("rate"), LineRules.validate(form.copy(rate = "90")) { "k" }.second.keys)
        assertEquals(setOf("discount"), LineRules.validate(form.copy(discount = "5%")) { "k" }.second.keys)
        assertEquals(emptyMap(), LineRules.validate(form.copy(rate = "37", listPrices = emptyList())) { "k" }.second, "no price set: any rate")
    }
}
