package app.openbill.core.domain.repository

import app.openbill.core.domain.model.Account
import app.openbill.core.domain.model.AccountTransaction
import app.openbill.core.domain.model.PartyMovement
import app.openbill.core.model.Money
import app.openbill.core.domain.model.AccountCode
import app.openbill.core.domain.model.FirmLedgerAccounts
import app.openbill.core.domain.model.JournalEntry
import app.openbill.core.domain.model.PartyBalance
import app.openbill.core.domain.model.PartyLedger
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate

interface LedgerRepository {
    suspend fun ensureSystemAccounts(firmId: String, at: Instant): FirmLedgerAccounts

    suspend fun findAccount(firmId: String, code: AccountCode): Account?

    suspend fun findAccountById(id: String): Account?

    fun observeAccounts(firmId: String): Flow<List<Account>>

    suspend fun saveAccount(account: Account)

    suspend fun journalEntriesForVoucher(voucherId: String): List<JournalEntry>

    fun observePartyLedger(
        firmId: String,
        partyId: String,
        fromDate: LocalDate,
        toDate: LocalDate,
    ): Flow<PartyLedger>

    suspend fun partyBalance(firmId: String, partyId: String, asOf: LocalDate): PartyBalance

    /** Receivable/payable movements of every party (posted, non-deleted vouchers). */
    fun observePartyMovements(firmId: String): Flow<List<PartyMovement>>

    /** Balance of every account: opening balance plus debits minus credits. */
    fun observeAccountBalances(firmId: String): Flow<Map<String, Money>>

    /** Journal lines of one account, oldest first. */
    fun observeAccountTransactions(accountId: String): Flow<List<AccountTransaction>>

    /** Marks a journal entry cleared on [clearedOn] (bank reconciliation), or uncleared when null. */
    suspend fun setCleared(entryId: String, clearedOn: LocalDate?)
}
