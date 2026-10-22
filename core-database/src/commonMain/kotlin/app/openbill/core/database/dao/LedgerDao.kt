package app.openbill.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import app.openbill.core.database.AccountEntity
import app.openbill.core.database.JournalEntryEntity
import kotlinx.coroutines.flow.Flow

data class AccountTotals(
    val debit: Long,
    val credit: Long,
)

@Dao
interface LedgerDao {
    @Insert
    suspend fun insertAccount(account: AccountEntity)

    @Update
    suspend fun updateAccount(account: AccountEntity)

    @Query("SELECT * FROM accounts WHERE id = :id LIMIT 1")
    suspend fun findAccountById(id: String): AccountEntity?

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
}
