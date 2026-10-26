package app.openbill.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.openbill.core.database.AccountEntity
import app.openbill.core.database.JournalEntryEntity
import kotlinx.coroutines.flow.Flow

data class AccountTotals(
    val debit: Long,
    val credit: Long,
)

data class PartyControlTotals(
    val debit: Long,
    val credit: Long,
)

data class PartyLedgerRow(
    val id: String,
    val voucherId: String,
    val voucherNumber: String?,
    val voucherType: String,
    val date: String,
    val accountId: String,
    val accountCode: String,
    val narration: String?,
    val debit: Long,
    val credit: Long,
    val createdAt: Long,
)

data class AccountBalanceRow(
    val accountId: String,
    val debit: Long,
    val credit: Long,
)

data class PartyMovementRow(
    val partyId: String,
    val voucherId: String,
    val voucherType: String,
    val voucherNumber: String?,
    val date: String,
    val debit: Long,
    val credit: Long,
)

data class AccountEntryRow(
    @androidx.room.Embedded val entry: JournalEntryEntity,
    val voucherType: String,
    val voucherNumber: String?,
    val partyId: String?,
)

@Dao
interface LedgerDao {
    @Query(
        "SELECT accountId, COALESCE(SUM(debit), 0) AS debit, COALESCE(SUM(credit), 0) AS credit " +
            "FROM journal_entries WHERE firmId = :firmId GROUP BY accountId",
    )
    fun observeAccountTotals(firmId: String): Flow<List<AccountBalanceRow>>

    @Query(
        "SELECT j.*, v.type AS voucherType, v.number AS voucherNumber, v.partyId AS partyId " +
            "FROM journal_entries j INNER JOIN vouchers v ON v.id = j.voucherId " +
            "WHERE j.accountId = :accountId ORDER BY j.date, j.createdAt, j.id",
    )
    fun observeAccountEntries(accountId: String): Flow<List<AccountEntryRow>>

    @Query("UPDATE journal_entries SET clearedOn = :clearedOn WHERE id = :id")
    suspend fun setCleared(id: String, clearedOn: String?): Int

    @Query(
        "SELECT v.partyId AS partyId, j.voucherId AS voucherId, v.type AS voucherType, " +
            "v.number AS voucherNumber, j.date AS date, j.debit AS debit, j.credit AS credit " +
            "FROM journal_entries j " +
            "INNER JOIN vouchers v ON v.id = j.voucherId " +
            "INNER JOIN accounts a ON a.id = j.accountId " +
            "WHERE j.firmId = :firmId AND v.partyId IS NOT NULL " +
            "AND v.status = 'posted' AND v.deletedAt IS NULL " +
            "AND a.code IN ('AR', 'AP') ORDER BY j.date, j.createdAt, j.id",
    )
    fun observePartyMovements(firmId: String): Flow<List<PartyMovementRow>>

    @Insert
    suspend fun insertAccount(account: AccountEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAccountIfAbsent(account: AccountEntity): Long

    @Update
    suspend fun updateAccount(account: AccountEntity)

    @Query("SELECT * FROM accounts WHERE id = :id LIMIT 1")
    suspend fun findAccountById(id: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE firmId = :firmId AND code = :code LIMIT 1")
    suspend fun findAccountByCode(firmId: String, code: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE firmId = :firmId AND name = :name LIMIT 1")
    suspend fun findAccountByName(firmId: String, name: String): AccountEntity?

    @Query(
        "SELECT * FROM accounts WHERE firmId = :firmId AND deletedAt IS NULL " +
            "ORDER BY name COLLATE NOCASE, id",
    )
    fun observeAccounts(firmId: String): Flow<List<AccountEntity>>

    @Insert
    suspend fun insertEntries(entries: List<JournalEntryEntity>)

    @Query(
        "SELECT * FROM journal_entries WHERE voucherId = :voucherId " +
            "ORDER BY date, createdAt, id",
    )
    suspend fun entriesForVoucher(voucherId: String): List<JournalEntryEntity>

    @Query(
        "SELECT COALESCE(SUM(debit), 0) AS debit, COALESCE(SUM(credit), 0) AS credit " +
            "FROM journal_entries WHERE accountId = :accountId",
    )
    suspend fun totalsForAccount(accountId: String): AccountTotals

    @Query(
        "SELECT COALESCE(SUM(debit), 0) AS debit, COALESCE(SUM(credit), 0) AS credit " +
            "FROM journal_entries WHERE accountId = :accountId " +
            "AND date >= :fromDate AND date <= :toDate",
    )
    suspend fun totalsForAccountInRange(
        accountId: String,
        fromDate: String,
        toDate: String,
    ): AccountTotals

    @Query(
        "SELECT j.id AS id, j.voucherId AS voucherId, v.number AS voucherNumber, " +
            "v.type AS voucherType, j.date AS date, j.accountId AS accountId, " +
            "a.code AS accountCode, j.narration AS narration, j.debit AS debit, " +
            "j.credit AS credit, j.createdAt AS createdAt " +
            "FROM journal_entries j " +
            "INNER JOIN vouchers v ON v.id = j.voucherId " +
            "INNER JOIN accounts a ON a.id = j.accountId " +
            "WHERE j.firmId = :firmId AND v.partyId = :partyId " +
            "AND v.status = 'posted' AND v.deletedAt IS NULL " +
            "AND a.code IN ('AR', 'AP') " +
            "AND j.date >= :fromDate AND j.date <= :toDate " +
            "ORDER BY j.date, j.createdAt, j.id",
    )
    suspend fun partyLedgerEntries(
        firmId: String,
        partyId: String,
        fromDate: String,
        toDate: String,
    ): List<PartyLedgerRow>

    @Query(
        "SELECT j.id AS id, j.voucherId AS voucherId, v.number AS voucherNumber, " +
            "v.type AS voucherType, j.date AS date, j.accountId AS accountId, " +
            "a.code AS accountCode, j.narration AS narration, j.debit AS debit, " +
            "j.credit AS credit, j.createdAt AS createdAt " +
            "FROM journal_entries j " +
            "INNER JOIN vouchers v ON v.id = j.voucherId " +
            "INNER JOIN accounts a ON a.id = j.accountId " +
            "WHERE j.firmId = :firmId AND v.partyId = :partyId " +
            "AND v.status = 'posted' AND v.deletedAt IS NULL " +
            "AND a.code IN ('AR', 'AP') " +
            "AND j.date >= :fromDate AND j.date <= :toDate " +
            "ORDER BY j.date, j.createdAt, j.id",
    )
    fun observePartyLedgerEntries(
        firmId: String,
        partyId: String,
        fromDate: String,
        toDate: String,
    ): Flow<List<PartyLedgerRow>>

    @Query(
        "SELECT COALESCE(SUM(j.debit), 0) AS debit, COALESCE(SUM(j.credit), 0) AS credit " +
            "FROM journal_entries j " +
            "INNER JOIN vouchers v ON v.id = j.voucherId " +
            "INNER JOIN accounts a ON a.id = j.accountId " +
            "WHERE j.firmId = :firmId AND v.partyId = :partyId " +
            "AND v.status = 'posted' AND v.deletedAt IS NULL " +
            "AND a.code IN ('AR', 'AP') AND j.date < :beforeDate",
    )
    suspend fun partyControlTotalsBefore(
        firmId: String,
        partyId: String,
        beforeDate: String,
    ): PartyControlTotals

    @Query(
        "SELECT COALESCE(SUM(j.debit), 0) AS debit, COALESCE(SUM(j.credit), 0) AS credit " +
            "FROM journal_entries j " +
            "INNER JOIN vouchers v ON v.id = j.voucherId " +
            "INNER JOIN accounts a ON a.id = j.accountId " +
            "WHERE j.firmId = :firmId AND v.partyId = :partyId " +
            "AND v.status = 'posted' AND v.deletedAt IS NULL " +
            "AND a.code IN ('AR', 'AP') AND j.date < :beforeDate",
    )
    fun observePartyControlTotalsBefore(
        firmId: String,
        partyId: String,
        beforeDate: String,
    ): Flow<PartyControlTotals>

    @Query(
        "SELECT COALESCE(SUM(j.debit), 0) AS debit, COALESCE(SUM(j.credit), 0) AS credit " +
            "FROM journal_entries j " +
            "INNER JOIN vouchers v ON v.id = j.voucherId " +
            "INNER JOIN accounts a ON a.id = j.accountId " +
            "WHERE j.firmId = :firmId AND v.partyId = :partyId " +
            "AND v.status = 'posted' AND v.deletedAt IS NULL " +
            "AND a.code IN ('AR', 'AP') AND j.date <= :throughDate",
    )
    suspend fun partyControlTotalsThrough(
        firmId: String,
        partyId: String,
        throughDate: String,
    ): PartyControlTotals
}
