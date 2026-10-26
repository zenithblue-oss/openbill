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
}
