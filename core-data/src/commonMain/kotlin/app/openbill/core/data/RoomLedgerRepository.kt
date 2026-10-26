package app.openbill.core.data

import app.openbill.core.database.DatabaseTransactionRunner
import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.database.dao.PartyLedgerRow
import app.openbill.core.domain.model.Account
import app.openbill.core.domain.model.AccountTransaction
import app.openbill.core.domain.model.PartyMovement
import app.openbill.core.domain.model.AccountCode
import app.openbill.core.domain.model.FirmLedgerAccounts
import app.openbill.core.domain.model.JournalEntry
import app.openbill.core.domain.model.OpeningBalanceType
import app.openbill.core.domain.model.PartyBalance
import app.openbill.core.domain.model.PartyLedger
import app.openbill.core.domain.model.PartyLedgerEntry
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.repository.LedgerRepository
import app.openbill.core.model.Money
import app.openbill.core.model.Ulid
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate

class RoomLedgerRepository(
    private val database: OpenBillDatabase,
    private val transactions: DatabaseTransactionRunner = DatabaseTransactionRunner(database),
) : LedgerRepository {
    private val accounts get() = database.ledgerDao()
    private val parties get() = database.partyDao()

    override suspend fun ensureSystemAccounts(
        firmId: String,
        at: Instant,
    ): FirmLedgerAccounts = transactions.write {
        ensureSystemAccountsLocked(firmId, at)
    }

    internal suspend fun ensureSystemAccountsLocked(
        firmId: String,
        at: Instant,
    ): FirmLedgerAccounts {
        val resolved = AccountCode.entries.associateWith { code ->
            val existing = accounts.findAccountByCode(firmId, code.name)
            if (existing != null) {
                val account = existing.toDomain()
                require(account.deletedAt == null) {
                    "System account ${code.name} is deleted"
                }
                account
            } else {
                val nameConflict = accounts.findAccountByName(firmId, code.accountName)
                require(nameConflict == null) {
                    "Account ${code.accountName} already exists without system code ${code.name}"
                }
                val created = Account(
                    id = Ulid.create(at.toEpochMilliseconds()).toString(),
                    firmId = firmId,
                    name = code.accountName,
                    type = code.accountType,
                    code = code,
                    isDefault = code == AccountCode.CASH,
                    createdAt = at,
                    updatedAt = at,
                )
                accounts.insertAccountIfAbsent(created.toEntity(null))
                accounts.findAccountByCode(firmId, code.name)?.toDomain()
                    ?: throw IllegalStateException("Failed to create system account ${code.name}")
            }
        }
        return FirmLedgerAccounts(
            accountsReceivable = resolved.getValue(AccountCode.AR),
            accountsPayable = resolved.getValue(AccountCode.AP),
            outputGst = resolved.getValue(AccountCode.OUTPUT_GST),
            inputGst = resolved.getValue(AccountCode.INPUT_GST),
            roundOff = resolved.getValue(AccountCode.ROUND_OFF),
            sales = resolved.getValue(AccountCode.SALES),
            purchases = resolved.getValue(AccountCode.PURCHASES),
            cash = resolved.getValue(AccountCode.CASH),
        )
    }

    override suspend fun findAccount(firmId: String, code: AccountCode): Account? =
        accounts.findAccountByCode(firmId, code.name)?.toDomain()

    override suspend fun findAccountById(id: String): Account? =
        accounts.findAccountById(id)?.toDomain()

    override fun observeAccounts(firmId: String): Flow<List<Account>> =
        accounts.observeAccounts(firmId).map { values -> values.map { it.toDomain() } }

    override suspend fun saveAccount(account: Account) {
        transactions.write {
            val existing = accounts.findAccountById(account.id)
            val entity = account.toEntity(existing)
            if (existing == null) {
                accounts.insertAccount(entity)
            } else {
                accounts.updateAccount(entity)
            }
        }
    }

    override suspend fun journalEntriesForVoucher(voucherId: String): List<JournalEntry> =
        accounts.entriesForVoucher(voucherId).map { it.toDomain() }

    override fun observePartyLedger(
        firmId: String,
        partyId: String,
        fromDate: LocalDate,
        toDate: LocalDate,
    ): Flow<PartyLedger> = combine(
        parties.observeById(partyId),
        accounts.observePartyControlTotalsBefore(firmId, partyId, fromDate.toString()),
        accounts.observePartyLedgerEntries(
            firmId,
            partyId,
            fromDate.toString(),
            toDate.toString(),
        ),
    ) { party, before, rows ->
        require(party != null) { "Unknown party: $partyId" }
        require(party.firmId == firmId) { "Party belongs to a different firm" }
        val opening = signedOpening(party) + (Money.of(before.debit) - Money.of(before.credit))
        PartyLedger(
            firmId = firmId,
            partyId = partyId,
            openingBalance = opening,
            entries = rows.map { it.toDomain() },
        )
    }

    override suspend fun partyBalance(
        firmId: String,
        partyId: String,
        asOf: LocalDate,
    ): PartyBalance {
        val party = parties.findById(partyId)
            ?: throw IllegalArgumentException("Unknown party: $partyId")
        require(party.firmId == firmId) { "Party belongs to a different firm" }
        val totals = accounts.partyControlTotalsThrough(
            firmId,
            partyId,
            asOf.toString(),
        )
        val opening = signedOpening(party)
        val debit = Money.of(totals.debit)
        val credit = Money.of(totals.credit)
        return PartyBalance(
            debit = debit + if (opening > Money.ZERO) opening else Money.ZERO,
            credit = credit + if (opening < Money.ZERO) -opening else Money.ZERO,
        )
    }

    override fun observePartyMovements(firmId: String): Flow<List<PartyMovement>> =
        accounts.observePartyMovements(firmId).map { rows ->
            rows.map {
                PartyMovement(
                    partyId = it.partyId,
                    voucherId = it.voucherId,
                    voucherType = decodeEnum<VoucherType>(it.voucherType, "voucher type"),
                    voucherNumber = it.voucherNumber,
                    date = LocalDate.parse(it.date),
                    net = Money.of(it.debit - it.credit),
                )
            }
        }

    override fun observeAccountBalances(firmId: String): Flow<Map<String, Money>> = combine(
        accounts.observeAccounts(firmId),
        accounts.observeAccountTotals(firmId),
    ) { accountRows, totals ->
        val byId = totals.associateBy { it.accountId }
        accountRows.associate { a ->
            val t = byId[a.id]
            a.id to Money.of(a.openingBalance + (t?.debit ?: 0L) - (t?.credit ?: 0L))
        }
    }

    override fun observeAccountTransactions(accountId: String): Flow<List<AccountTransaction>> =
        accounts.observeAccountEntries(accountId).map { rows ->
            rows.map {
                AccountTransaction(
                    entry = it.entry.toDomain(),
                    voucherType = decodeEnum<VoucherType>(it.voucherType, "voucher type"),
                    voucherNumber = it.voucherNumber,
                    partyId = it.partyId,
                )
            }
        }

    override suspend fun setCleared(entryId: String, clearedOn: LocalDate?) {
        val changed = accounts.setCleared(entryId, clearedOn?.toString())
        require(changed == 1) { "Unknown journal entry: $entryId" }
    }

    private fun signedOpening(party: app.openbill.core.database.PartyEntity): Money {
        val opening = Money.of(party.openingBalance)
        return when (decodeEnum<OpeningBalanceType>(party.openingBalanceType, "opening balance type")) {
            OpeningBalanceType.DEBIT -> opening
            OpeningBalanceType.CREDIT -> -opening
        }
    }

    private fun PartyLedgerRow.toDomain(): PartyLedgerEntry = PartyLedgerEntry(
        id = id,
        voucherId = voucherId,
        voucherNumber = voucherNumber,
        voucherType = decodeEnum<VoucherType>(voucherType, "voucher type"),
        date = LocalDate.parse(date),
        accountId = accountId,
        accountCode = decodeEnum<AccountCode>(accountCode, "account code"),
        narration = narration,
        debit = Money.of(debit),
        credit = Money.of(credit),
        createdAt = createdAt.toInstant(),
    )
}
