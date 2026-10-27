package app.openbill.core.domain.report

import app.openbill.core.domain.model.AccountTransaction
import app.openbill.core.domain.repository.Repositories
import app.openbill.core.domain.model.VoucherStatus
import kotlinx.coroutines.flow.first
import app.openbill.core.model.OnProgress
import app.openbill.core.model.Progress

/*
 * Reports whose output grows with the number of vouchers (registers, Tally XML) stream week
 * by week and write straight to [Appendable], so a year at 1M invoices never sits in memory
 * (docs/14-performance.md). Output is identical to the in-memory [Reports] and [TallyXml].
 */

/** Firm masters (parties, items, units, accounts) with no vouchers or history. */
suspend fun Repositories.reportMasters(firmId: String, period: ReportPeriod): ReportData = ReportData(
    firm = firms.findFirm(firmId) ?: throw IllegalArgumentException("Unknown firm: $firmId"),
    settings = firms.findSettings(firmId),
    period = period,
    vouchers = emptyList(),
    parties = parties.observeParties(firmId).first(),
    items = catalog.observeItems(firmId).first(),
    units = catalog.observeUnits(firmId).first(),
    accounts = ledger.observeAccounts(firmId).first(),
    journal = emptyList(),
    stock = emptyList(),
    partyMovements = emptyList(),
)

/**
 * Calls [block] for each [days]-day slice of [masters]' period, oldest first, with the slice's
 * non-deleted vouchers and, if [withJournal], their journal lines (in account order, as the
 * full snapshot has them).
 */
suspend fun Repositories.forEachReportSlice(
    masters: ReportData,
    withJournal: Boolean = false,
    days: Int = 7,
    block: suspend (ReportData) -> Unit,
) {
    val accountOrder = masters.accounts.withIndex().associate { it.value.id to it.index }
    masters.period.slices(days).forEach { slice ->
        val list = vouchers.observeVouchers(masters.firm.id, slice.from, slice.to).first().filter { it.deletedAt == null }
        val journal = if (!withJournal) emptyList() else {
            val byId = list.associateBy { it.id }
            ledger.journalEntriesForVouchers(list.filter { it.status == VoucherStatus.POSTED }.map { it.id })
                .sortedBy { accountOrder[it.accountId] ?: Int.MAX_VALUE }
                .map { e -> byId.getValue(e.voucherId).let { AccountTransaction(e, it.type, it.number, it.partyId) } }
        }
        block(
            ReportData(
                masters.firm, masters.settings, slice, list, masters.parties, masters.items, masters.units,
                masters.accounts, journal, emptyList(), emptyList(),
            ),
        )
    }
}

/** The sales or purchase register of [period] as CSV, exactly as `Reports.register(...).toCsv()`; progress counts weeks. */
suspend fun Repositories.writeRegisterCsv(firmId: String, period: ReportPeriod, sales: Boolean, out: Appendable, onProgress: OnProgress = {}) {
    onProgress(Progress("Reading masters"))
    val masters = reportMasters(firmId, period)
    val r = Reports.Register(masters, sales)
    val weeks = period.slices().size.toLong()
    var done = 0L
    out.append(ReportTable.csvRow(r.columns.map { it.label })).append("\r\n")
    onProgress(Progress(REGISTER_STAGE, 0, weeks))
    forEachReportSlice(masters) { slice ->
        slice.posted.filter { it.type in r.types }.forEach { out.append(ReportTable.csvRow(r.row(it))).append("\r\n") }
        onProgress(Progress(REGISTER_STAGE, ++done, weeks))
    }
    out.append(ReportTable.csvRow(r.totals())).append("\r\n")
}

/**
 * Tally XML of [period], exactly as `TallyXml.export(loadReportData(...))`. Two passes, since
 * ledger masters come first; progress counts weeks over both passes.
 */
suspend fun Repositories.writeTallyXml(firmId: String, period: ReportPeriod, out: Appendable, onProgress: OnProgress = {}) {
    onProgress(Progress("Reading masters"))
    val masters = reportMasters(firmId, period)
    val total = 2L * period.slices().size
    var done = 0L
    onProgress(Progress(TALLY_STAGE, 0, total))
    val ledgers = LinkedHashMap<String, TallyXml.Ledger>()
    forEachReportSlice(masters, withJournal = true) {
        TallyXml.collectMasters(TallyXml.vouchersOf(it), ledgers)
        onProgress(Progress(TALLY_STAGE, ++done, total))
    }
    TallyXml.appendHeader(out, masters, ledgers.values)
    forEachReportSlice(masters, withJournal = true) { slice ->
        TallyXml.vouchersOf(slice).forEach { (v, lines) -> TallyXml.appendVoucher(out, slice, v, lines) }
        onProgress(Progress(TALLY_STAGE, ++done, total))
    }
    TallyXml.appendFooter(out)
}

private const val REGISTER_STAGE = "Writing register (weeks)"
private const val TALLY_STAGE = "Writing Tally XML (weeks, two passes)"
