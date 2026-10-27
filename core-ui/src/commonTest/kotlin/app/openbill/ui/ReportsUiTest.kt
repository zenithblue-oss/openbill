package app.openbill.ui

import app.openbill.core.domain.report.ColumnKind
import app.openbill.core.domain.report.ReportColumn
import app.openbill.core.domain.report.ReportTable
import app.openbill.ui.components.countLabel
import app.openbill.ui.nav.Route
import app.openbill.ui.pdf.DrawOp
import app.openbill.ui.reports.ReportPdf
import app.openbill.ui.reports.reportCell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportsUiTest {
    @Test
    fun reportRoutesSurviveTheirKeys() {
        listOf(Route.Reports, Route.AuditLog, Route.BooksLock, Route.Report("gstr1", "2026-10-01", "2026-10-31"), Route.Report("party-statement", "2026-04-01", "2027-03-31", "p1"))
            .forEach { assertEquals(it, Route.fromKey(it.key)) }
    }

    @Test
    fun cellsUseIndianGroupingAndDisplayDates() {
        assertEquals("1,23,456.50", reportCell(ColumnKind.MONEY, "123456.50"))
        assertEquals("−12.00", reportCell(ColumnKind.MONEY, "-12.00"))
        assertEquals("05-10-2026", reportCell(ColumnKind.DATE, "2026-10-05"))
    }

    @Test
    fun progressCountsUseIndianGrouping() {
        assertEquals("43% · 17,00,000 of 38,95,400", app.openbill.core.model.Progress("x", 1_700_000, 3_895_400).countLabel())
    }

    @Test
    fun pdfPaginatesLongReports() {
        val t = ReportTable("Day book", listOf(ReportColumn("n", "No"), ReportColumn("a", "Amount", ColumnKind.MONEY)), List(200) { listOf("V$it", "10.00") }, listOf("Total", "2000.00"))
        val layout = ReportPdf.layout(t, "Firm", "Oct 2026") { s, _, size -> s.length * size * 0.5f }
        assertTrue(layout.pages.size > 1)
        assertTrue(layout.pages.last().ops.filterIsInstance<DrawOp.Text>().any { it.text == "2,000.00" })
    }
}
