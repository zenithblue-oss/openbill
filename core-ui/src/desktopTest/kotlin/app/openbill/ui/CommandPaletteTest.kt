package app.openbill.ui

import app.openbill.ui.nav.Navigator
import app.openbill.ui.nav.Route
import app.openbill.ui.nav.filterCommands
import app.openbill.ui.nav.paletteCommands
import kotlin.test.Test
import kotlin.test.assertEquals

class CommandPaletteTest {
    @Test
    fun filterMatchesWordsAndRunsAction() {
        val nav = Navigator()
        val hits = filterCommands(paletteCommands(nav) {}, "stock low")
        assertEquals(listOf("Low stock"), hits.map { it.first })
        hits.single().second()
        assertEquals(Route.StockSummary(lowOnly = true), nav.current)
    }

    @Test
    fun modulesSwitchedOffLeaveThePalette() {
        val off = setOf(app.openbill.ui.settings.Module.POS, app.openbill.ui.settings.Module.DOCUMENTS)
        val labels = paletteCommands(Navigator(), off) {}.map { it.first }
        assertEquals(listOf("Recurring invoices"), labels.filter { it.startsWith("POS") || it == "Quotations" || it == "Recurring invoices" })
    }
}
