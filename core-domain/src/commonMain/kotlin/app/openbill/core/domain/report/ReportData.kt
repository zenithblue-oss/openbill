package app.openbill.core.domain.report

import app.openbill.core.domain.model.Account
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

/** Loads a [ReportData] snapshot of [firmId] for [period]. */
suspend fun Repositories.loadReportData(firmId: String, period: ReportPeriod): ReportData {
    val firm = firms.findFirm(firmId) ?: throw IllegalArgumentException("Unknown firm: $firmId")
    val accounts = ledger.observeAccounts(firmId).first()
    return ReportData(
        firm = firm,
        settings = firms.findSettings(firmId),
        period = period,
        vouchers = vouchers.observeVouchers(firmId, period.from, period.to).first().filter { it.deletedAt == null },
        parties = parties.observeParties(firmId).first(),
        items = catalog.observeItems(firmId).first(),
        units = catalog.observeUnits(firmId).first(),
        accounts = accounts,
        journal = accounts.flatMap { ledger.observeAccountTransactions(it.id).first() },
        stock = stock.observeFirmMovements(firmId).first(),
        partyMovements = ledger.observePartyMovements(firmId).first(),
    )
}
