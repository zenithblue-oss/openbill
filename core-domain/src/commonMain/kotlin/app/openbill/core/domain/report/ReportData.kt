package app.openbill.core.domain.report

import app.openbill.core.domain.model.Account
import app.openbill.core.domain.service.StockPeriodTotals
import app.openbill.core.domain.model.AccountType
import app.openbill.core.domain.model.AccountTransaction
import app.openbill.core.domain.model.Firm
import app.openbill.core.domain.model.FirmSettings
import app.openbill.core.domain.model.Item
import app.openbill.core.domain.model.OpeningBalanceType
import app.openbill.core.domain.model.Party
import app.openbill.core.domain.model.PartyMovement
import app.openbill.core.domain.model.StockMovement
import app.openbill.core.domain.model.UnitOfMeasure
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.repository.Repositories
import app.openbill.core.model.Money
import app.openbill.core.model.RoundingMode
import kotlinx.coroutines.flow.first

/**
 * Everything a report reads, loaded once: vouchers of the period, masters, and the full
 * journal, stock and party movement history (for opening balances and costing). Reports are
 * pure functions of this snapshot, so app, CLI and tests produce identical output.
 */
class ReportData(
    val firm: Firm,
    val settings: FirmSettings?,
    val period: ReportPeriod,
    /** Non-deleted vouchers dated in [period], any status. */
    val vouchers: List<Voucher>,
    val parties: List<Party>,
    val items: List<Item>,
    val units: List<UnitOfMeasure>,
    val accounts: List<Account>,
    /** Every journal line of the firm, all dates. */
    val journal: List<AccountTransaction>,
    /** Every stock movement of the firm, all dates. */
    val stock: List<StockMovement>,
    /** Receivable/payable movements of posted vouchers, all dates. */
    val partyMovements: List<PartyMovement>,
    /** Per-item stock totals for [period]; when set, stock summary, low stock and P&L use it instead of [stock]. */
    val stockTotals: Map<String, StockPeriodTotals>? = null,
    /** Debits minus credits per account in [period]; when set, P&L uses it instead of [journal]. */
    val accountNets: Map<String, Money>? = null,
) {
    val from get() = period.from
    val to get() = period.to
    val rounding: RoundingMode get() = settings?.roundingMode ?: RoundingMode.HALF_UP

    val partyById: Map<String, Party> by lazy { parties.associateBy { it.id } }
    val itemById: Map<String, Item> by lazy { items.associateBy { it.id } }
    val accountById: Map<String, Account> by lazy { accounts.associateBy { it.id } }
    val unitById: Map<String, UnitOfMeasure> by lazy { units.associateBy { it.id } }

    /** Posted vouchers of the period, oldest first. */
    val posted: List<Voucher> by lazy {
        vouchers.filter { it.status == VoucherStatus.POSTED }
            .sortedWith(compareBy<Voucher>({ it.date }, { it.number ?: "" }, { it.id }))
    }

    fun partyName(id: String?): String = id?.let { partyById[it]?.name } ?: ""
}

/** Party master opening balance, signed: positive = they owe us. */
val Party.signedOpening: Money
    get() = if (openingBalanceType == OpeningBalanceType.DEBIT) openingBalance else -openingBalance

/**
 * Loads a [ReportData] snapshot of [firmId] for [period]. With a [kind], only what that report
 * reads is loaded (the rest stays empty), so a register never pulls the full journal and stock
 * history into memory (docs/14-performance.md); without one, everything (GSTR-1 JSON, Tally).
 */
suspend fun Repositories.loadReportData(firmId: String, period: ReportPeriod, kind: ReportKind? = null): ReportData {
    val firm = firms.findFirm(firmId) ?: throw IllegalArgumentException("Unknown firm: $firmId")
    val accounts = ledger.observeAccounts(firmId).first()
    val all = kind == null
    val usesVouchers = all || kind !in setOf(
        ReportKind.CASH_BANK_BOOK, ReportKind.OUTSTANDING, ReportKind.PARTY_STATEMENT, ReportKind.STOCK_SUMMARY, ReportKind.LOW_STOCK,
        ReportKind.PROFIT_LOSS,
    )
    // Stock summary, low stock and P&L read SQL/streamed aggregates instead of the full history.
    val aggregates = kind == ReportKind.STOCK_SUMMARY || kind == ReportKind.LOW_STOCK || kind == ReportKind.PROFIT_LOSS
    val journalAccounts = when (kind) {
        null -> accounts
        ReportKind.CASH_BANK_BOOK -> accounts.filter { it.type == AccountType.CASH || it.type == AccountType.BANK }
        else -> emptyList()
    }
    val usesStock = all || kind == ReportKind.ITEM_PROFIT
    val usesPartyMovements = all || kind == ReportKind.OUTSTANDING || kind == ReportKind.PARTY_STATEMENT
    return ReportData(
        firm = firm,
        settings = firms.findSettings(firmId),
        period = period,
        vouchers = if (usesVouchers) vouchers.observeVouchers(firmId, period.from, period.to).first().filter { it.deletedAt == null } else emptyList(),
        parties = parties.observeParties(firmId).first(),
        items = catalog.observeItems(firmId).first(),
        units = catalog.observeUnits(firmId).first(),
        accounts = accounts,
        journal = journalAccounts.flatMap { ledger.observeAccountTransactions(it.id).first() },
        stock = if (usesStock) stock.observeFirmMovements(firmId).first() else emptyList(),
        partyMovements = if (usesPartyMovements) ledger.observePartyMovements(firmId).first() else emptyList(),
        stockTotals = if (aggregates) stock.stockPeriodTotals(firmId, period.from, period.to) else null,
        accountNets = if (kind == ReportKind.PROFIT_LOSS) ledger.accountNets(firmId, period.from, period.to) else null,
    )
}
