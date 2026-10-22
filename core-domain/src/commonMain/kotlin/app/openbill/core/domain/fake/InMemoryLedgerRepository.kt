package app.openbill.core.domain.fake

import app.openbill.core.domain.model.Account
import app.openbill.core.domain.model.AccountCode
import app.openbill.core.domain.model.FirmLedgerAccounts
import app.openbill.core.domain.model.JournalEntry
import app.openbill.core.domain.model.OpeningBalanceType
import app.openbill.core.domain.model.Party
import app.openbill.core.domain.model.PartyBalance
import app.openbill.core.domain.model.PartyLedger
import app.openbill.core.domain.model.PartyLedgerEntry
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.repository.LedgerRepository
import app.openbill.core.domain.repository.PartyRepository
import app.openbill.core.domain.repository.VoucherRepository
import app.openbill.core.model.Money
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate

class InMemoryLedgerRepository(
    private val parties: PartyRepository? = null,
) : LedgerRepository {
    private val accounts = MutableStateFlow<List<Account>>(emptyList())
    private val entries = MutableStateFlow<List<JournalEntry>>(emptyList())
    private val mutex = Mutex()
    private var vouchers: VoucherRepository? = null

    fun attachVouchers(vouchers: VoucherRepository) {
        this.vouchers = vouchers
    }

    suspend fun record(entries: List<JournalEntry>) = mutex.withLock {
        if (entries.isNotEmpty()) {
            this.entries.update { current -> current + entries }
        }
    }

    override suspend fun ensureSystemAccounts(
        firmId: String,
        at: Instant,
    ): FirmLedgerAccounts = mutex.withLock {
        val current = accounts.value
        val created = AccountCode.entries.associateWith { code ->
            current.firstOrNull { it.firmId == firmId && it.code == code }
                ?: run {
                    val nameConflict = current.firstOrNull {
                        it.firmId == firmId && it.name == code.accountName
                    }
                    require(nameConflict == null) {
                        "Account ${code.accountName} already exists without system code ${code.name}"
                    }
                    Account(
                        id = "account-$firmId-${code.name.lowercase()}",
                        firmId = firmId,
                        name = code.accountName,
                        type = code.accountType,
                        code = code,
                        isDefault = code == AccountCode.CASH,
                        createdAt = at,
                        updatedAt = at,
                    )
                }
        }
        accounts.update { values ->
            val createdIds = created.values.map { it.id }.toSet()
            values.filterNot { it.id in createdIds } + created.values
        }
        FirmLedgerAccounts(
            accountsReceivable = created.getValue(AccountCode.AR),
            accountsPayable = created.getValue(AccountCode.AP),
            outputGst = created.getValue(AccountCode.OUTPUT_GST),
            inputGst = created.getValue(AccountCode.INPUT_GST),
            roundOff = created.getValue(AccountCode.ROUND_OFF),
            sales = created.getValue(AccountCode.SALES),
            purchases = created.getValue(AccountCode.PURCHASES),
            cash = created.getValue(AccountCode.CASH),
        )
    }

    override suspend fun findAccount(firmId: String, code: AccountCode): Account? =
        accounts.value.firstOrNull { it.firmId == firmId && it.code == code }

    override suspend fun findAccountById(id: String): Account? =
        accounts.value.firstOrNull { it.id == id }

    override fun observeAccounts(firmId: String): Flow<List<Account>> =
        accounts.map { values ->
            values.filter { it.firmId == firmId && it.deletedAt == null }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }

    override suspend fun saveAccount(account: Account) = mutex.withLock {
        accounts.update { current -> current.filterNot { it.id == account.id } + account }
    }

    override suspend fun journalEntriesForVoucher(voucherId: String): List<JournalEntry> =
        entries.value.filter { it.voucherId == voucherId }
            .sortedWith(
                compareBy<JournalEntry> { it.date }
                    .thenBy { it.createdAt }
                    .thenBy { it.id },
            )

    override fun observePartyLedger(
        firmId: String,
        partyId: String,
        fromDate: LocalDate,
        toDate: LocalDate,
    ): Flow<PartyLedger> {
        val partyFlow = parties?.observeParty(partyId) ?: flowOf(null)
        val voucherFlow = vouchers?.observeVouchers(firmId, MIN_DATE, MAX_DATE)
            ?: flowOf(emptyList())
        return combine(
            partyFlow,
            accounts,
            entries,
            voucherFlow,
        ) { party, accountValues, entryValues, voucherValues ->
            val accountsById = accountValues.associateBy { it.id }
            val vouchersById = voucherValues.associateBy { it.id }
            val relevant = entryValues.filter { entry ->
                val voucher = vouchersById[entry.voucherId]
                val account = accountsById[entry.accountId]
                voucher != null &&
                    voucher.firmId == firmId &&
                    voucher.partyId == partyId &&
                    voucher.status == VoucherStatus.POSTED &&
                    voucher.deletedAt == null &&
                    account?.code in CONTROL_CODES
            }
            val before = relevant
                .filter { it.date < fromDate }
                .fold(Money.ZERO) { total, entry -> total + entry.net() }
            val partyOpening = signedOpening(party)
            val rows = relevant
                .filter { it.date >= fromDate && it.date <= toDate }
                .map { entry ->
                    val voucher = vouchersById.getValue(entry.voucherId)
                    val account = accountsById.getValue(entry.accountId)
                    PartyLedgerEntry(
                        id = entry.id,
                        voucherId = entry.voucherId,
                        voucherNumber = voucher.number,
                        voucherType = voucher.type,
                        date = entry.date,
                        accountId = entry.accountId,
                        accountCode = requireNotNull(account.code),
                        narration = entry.narration,
                        debit = entry.debit,
                        credit = entry.credit,
                        createdAt = entry.createdAt,
                    )
                }
                .sortedWith(
                    compareBy<PartyLedgerEntry> { it.date }
                        .thenBy { it.createdAt }
                        .thenBy { it.id },
                )
            PartyLedger(
                firmId = firmId,
                partyId = partyId,
                openingBalance = partyOpening + before,
                entries = rows,
            )
        }
    }

    override suspend fun partyBalance(
        firmId: String,
        partyId: String,
        asOf: LocalDate,
    ): PartyBalance {
        val party = parties?.findParty(partyId)
            ?: throw IllegalArgumentException("Unknown party: $partyId")
        val voucherRepository = vouchers
            ?: throw IllegalStateException("No voucher repository is attached")
        val accountValues = accounts.value.associateBy { it.id }
        var debit = Money.ZERO
        var credit = Money.ZERO
        for (entry in entries.value) {
            if (entry.date > asOf) continue
            if (entry.firmId != firmId) continue
            val account = accountValues[entry.accountId] ?: continue
            if (account.code !in CONTROL_CODES) continue
            val voucher = voucherRepository.findVoucher(entry.voucherId) ?: continue
            if (voucher.partyId != partyId) continue
            if (voucher.status != VoucherStatus.POSTED || voucher.deletedAt != null) continue
            debit += entry.debit
            credit += entry.credit
        }
        val opening = signedOpening(party)
        return PartyBalance(
            debit = debit + if (opening > Money.ZERO) opening else Money.ZERO,
            credit = credit + if (opening < Money.ZERO) -opening else Money.ZERO,
        )
    }

    private fun signedOpening(party: Party?): Money {
        if (party == null) return Money.ZERO
        return when (party.openingBalanceType) {
            OpeningBalanceType.DEBIT -> party.openingBalance
            OpeningBalanceType.CREDIT -> -party.openingBalance
        }
    }

    private fun JournalEntry.net(): Money = debit - credit

    private companion object {
        val CONTROL_CODES = setOf(AccountCode.AR, AccountCode.AP)
        val MIN_DATE = LocalDate(1, 1, 1)
        val MAX_DATE = LocalDate(9999, 12, 31)
    }
}
