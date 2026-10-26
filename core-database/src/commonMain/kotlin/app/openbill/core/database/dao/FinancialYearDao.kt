package app.openbill.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import app.openbill.core.database.FinancialYearEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FinancialYearDao {
    @Insert
    suspend fun insert(financialYear: FinancialYearEntity)

    @Update
    suspend fun update(financialYear: FinancialYearEntity)

    @Query("SELECT * FROM financial_years WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): FinancialYearEntity?

    @Query(
        "SELECT * FROM financial_years WHERE firmId = :firmId " +
            "ORDER BY startDate DESC, id",
    )
    fun observeForFirm(firmId: String): Flow<List<FinancialYearEntity>>

    @Query(
        "SELECT * FROM financial_years WHERE firmId = :firmId " +
            "AND :date BETWEEN startDate AND endDate ORDER BY startDate DESC LIMIT 1",
    )
    suspend fun findContaining(firmId: String, date: String): FinancialYearEntity?

    @Query(
        "SELECT * FROM financial_years WHERE :date BETWEEN startDate AND endDate " +
            "ORDER BY startDate DESC LIMIT 1",
    )
    suspend fun findContaining(date: String): FinancialYearEntity?

    /** End date of the latest closed financial year of the firm, or null. */
    @Query("SELECT MAX(endDate) FROM financial_years WHERE firmId = :firmId AND closedAt IS NOT NULL")
    suspend fun latestClosedEndDate(firmId: String): String?
}
