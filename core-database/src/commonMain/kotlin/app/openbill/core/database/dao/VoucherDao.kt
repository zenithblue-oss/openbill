package app.openbill.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import app.openbill.core.database.VoucherChargeEntity
import app.openbill.core.database.VoucherEntity
import app.openbill.core.database.VoucherLineEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VoucherDao {
    @Insert
    suspend fun insert(voucher: VoucherEntity)

    @Update
    suspend fun update(voucher: VoucherEntity)

    @Insert
    suspend fun insertLines(lines: List<VoucherLineEntity>)

    @Insert
    suspend fun insertCharges(charges: List<VoucherChargeEntity>)

    @Query("SELECT * FROM vouchers WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): VoucherEntity?

    @Query("SELECT * FROM vouchers WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<VoucherEntity?>

    @Query(
        "SELECT * FROM vouchers WHERE firmId = :firmId AND deletedAt IS NULL " +
            "AND date >= :fromDate AND date <= :toDate " +
            "ORDER BY date DESC, createdAt DESC, id",
    )
    fun observeByFirmAndDateRange(
        firmId: String,
        fromDate: String,
        toDate: String,
    ): Flow<List<VoucherEntity>>

    @Query(
        "SELECT * FROM vouchers WHERE firmId = :firmId AND partyId = :partyId " +
            "AND type = :type AND status = 'posted' AND deletedAt IS NULL " +
            "ORDER BY date, createdAt, id",
    )
    suspend fun postedForPartyAndType(
        firmId: String,
        partyId: String,
        type: String,
    ): List<VoucherEntity>

    @Query(
        "SELECT * FROM vouchers WHERE firmId = :firmId AND partyId = :partyId " +
            "AND type = :type AND status = 'posted' AND deletedAt IS NULL " +
            "ORDER BY date, createdAt, id",
    )
    fun observePostedForPartyAndType(
        firmId: String,
        partyId: String,
        type: String,
    ): Flow<List<VoucherEntity>>

    @Query("SELECT * FROM voucher_lines WHERE voucherId = :voucherId ORDER BY lineNumber")
    suspend fun linesFor(voucherId: String): List<VoucherLineEntity>

    @Query("SELECT * FROM voucher_charges WHERE voucherId = :voucherId ORDER BY sortOrder")
    suspend fun chargesFor(voucherId: String): List<VoucherChargeEntity>

    @Query("SELECT COUNT(*) FROM voucher_lines WHERE voucherId = :voucherId")
    suspend fun lineCount(voucherId: String): Int

    @Query("DELETE FROM voucher_lines WHERE voucherId = :voucherId")
    suspend fun deleteLinesFor(voucherId: String): Int

    @Query("DELETE FROM voucher_charges WHERE voucherId = :voucherId")
    suspend fun deleteChargesFor(voucherId: String): Int

    @Query("DELETE FROM vouchers WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query(
        "SELECT * FROM vouchers WHERE firmId = :firmId AND status = 'draft' " +
            "AND deletedAt IS NOT NULL ORDER BY deletedAt DESC, id",
    )
    fun observeRecycleBin(firmId: String): Flow<List<VoucherEntity>>

    @Query(
        "UPDATE vouchers SET deletedAt = :at, updatedAt = :at, revision = revision + 1 " +
            "WHERE id = :id AND status = 'draft' AND deletedAt IS NULL",
    )
    suspend fun softDeleteDraft(id: String, at: Long): Int

    @Query(
        "UPDATE vouchers SET deletedAt = NULL, updatedAt = :at, revision = revision + 1 " +
            "WHERE id = :id AND status = 'draft' AND deletedAt IS NOT NULL",
    )
    suspend fun restoreDraft(id: String, at: Long): Int

    @Query(
        "DELETE FROM vouchers WHERE id = :id AND status = 'draft' AND deletedAt IS NOT NULL",
    )
    suspend fun purgeSoftDeletedDraft(id: String): Int

    @Query("UPDATE vouchers SET status = :status, updatedAt = :updatedAt WHERE id = :voucherId")
    suspend fun updateStatus(voucherId: String, status: String, updatedAt: Long): Int

    @Query("UPDATE vouchers SET number = :number, updatedAt = :updatedAt WHERE id = :voucherId")
    suspend fun assignNumber(voucherId: String, number: String, updatedAt: Long): Int

    @Transaction
    suspend fun insertWithLines(
        voucher: VoucherEntity,
        lines: List<VoucherLineEntity> = emptyList(),
        charges: List<VoucherChargeEntity> = emptyList(),
    ) {
        insert(voucher)
        insertLines(lines)
        insertCharges(charges)
    }
}
