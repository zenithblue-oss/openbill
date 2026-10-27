package app.openbill.core.domain.repository

import app.openbill.core.domain.model.Account
import app.openbill.core.domain.model.AccountTransaction
import app.openbill.core.domain.model.PartyMovement
import app.openbill.core.domain.service.AgeingSums
import app.openbill.core.domain.service.PartyAgeing
import app.openbill.core.model.Money
import app.openbill.core.domain.model.AccountCode
import app.openbill.core.domain.model.FirmLedgerAccounts
import app.openbill.core.domain.model.JournalEntry
import app.openbill.core.domain.model.PartyBalance
import app.openbill.core.domain.model.PartyLedger
import kotlin.time.Instant
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate

interface LedgerRepository {
    suspend fun ensureSystemAccounts(firmId: String, at: Instant): FirmLedgerAccounts

    suspend fun findAccount(firmId: String, code: AccountCode): Account?

    suspend fun findAccountById(id: String): Account?

    fun observeAccounts(firmId: String): Flow<List<Account>>

    suspend fun saveAccount(account: Account)

    suspend fun journalEntriesForVoucher(voucherId: String): List<JournalEntry>

    /** Journal lines of many vouchers in one go (Room batches the ids). */
    suspend fun journalEntriesForVouchers(voucherIds: List<String>): List<JournalEntry> = voucherIds.flatMap { journalEntriesForVoucher(it) }

    fun observePartyLedger(
        firmId: String,
        partyId: String,
        fromDate: LocalDate,
        toDate: LocalDate,
    ): Flow<PartyLedger>

    suspend fun partyBalance(firmId: String, partyId: String, asOf: LocalDate): PartyBalance

    /** Receivable/payable movements of every party (posted, non-deleted vouchers). */
    fun observePartyMovements(firmId: String): Flow<List<PartyMovement>>

    /**
     * Signed balance of every active party (positive = they owe us): opening balance plus
     * posted receivable/payable movements dated through [asOf]. Aggregated in SQL; the
     * dashboard uses it instead of [observePartyMovements].
     */
    fun observePartyBalances(firmId: String, asOf: LocalDate): Flow<Map<String, Money>>

    /** [AgeingSums] of every party with posted receivable/payable lines through [asOf] (opening excluded); Room sums in SQL. */
    suspend fun partyAgeingSums(firmId: String, asOf: LocalDate): Map<String, AgeingSums> =
        observePartyMovements(firmId).first().groupBy { it.partyId }.mapValues { (_, moves) -> PartyAgeing.sums(moves, asOf) }

    /** Balance of every account: opening balance plus debits minus credits. */
    fun observeAccountBalances(firmId: String): Flow<Map<String, Money>>

    /** Journal lines of one account, oldest first. */
    fun observeAccountTransactions(accountId: String): Flow<List<AccountTransaction>>

    /** Debits minus credits per account of the firm over journal lines dated [from]..[to] (summed in SQL). */
    suspend fun accountNets(firmId: String, from: LocalDate, to: LocalDate): Map<String, Money> =
        observeAccounts(firmId).first().associate { a ->
            a.id to observeAccountTransactions(a.id).first().filter { it.entry.date in from..to }
                .fold(Money.ZERO) { t, x -> t + x.entry.debit - x.entry.credit }
        }

    /** The newest [limit] journal lines of one account, newest first (paged account ledger). */
    fun observeAccountTransactionsPage(accountId: String, limit: Int): Flow<List<AccountTransaction>> =
        observeAccountTransactions(accountId).map { it.asReversed().take(limit) }

    /** Debits minus credits of one account: all lines, and lines cleared on a bank statement. */
    fun observeAccountNet(accountId: String): Flow<Pair<Money, Money>> =
        observeAccountTransactions(accountId).map { list ->
            list.fold(Money.ZERO) { t, x -> t + x.entry.debit - x.entry.credit } to
                list.filter { it.entry.clearedOn != null }.fold(Money.ZERO) { t, x -> t + x.entry.debit - x.entry.credit }
        }

    /** Marks a journal entry cleared on [clearedOn] (bank reconciliation), or uncleared when null. */
    suspend fun setCleared(entryId: String, clearedOn: LocalDate?)
}
