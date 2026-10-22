package app.openbill.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.openbill.core.database.VoucherNumberSequenceEntity

@Dao
interface VoucherNumberSequenceDao {
    @Query(
        "SELECT * FROM voucher_number_sequences " +
            "WHERE firmId = :firmId AND documentType = :documentType " +
            "AND financialYearId = :financialYearId LIMIT 1",
    )
    suspend fun get(
        firmId: String,
        documentType: String,
        financialYearId: String,
    ): VoucherNumberSequenceEntity?

    @Upsert
    suspend fun upsert(sequence: VoucherNumberSequenceEntity)

    @Query(
        "UPDATE voucher_number_sequences SET nextNumber = nextNumber + 1, updatedAt = :updatedAt " +
            "WHERE firmId = :firmId AND documentType = :documentType " +
            "AND financialYearId = :financialYearId",
    )
    suspend fun incrementNextNumber(
        firmId: String,
        documentType: String,
        financialYearId: String,
        updatedAt: Long,
    ): Int

    @Transaction
    suspend fun increment(
        firmId: String,
        documentType: String,
        financialYearId: String,
        updatedAt: Long,
    ): Long {
        val sequence = get(firmId, documentType, financialYearId)
            ?: throw IllegalStateException(
                "Missing voucher number sequence for $firmId/$documentType/$financialYearId",
            )
        check(
            incrementNextNumber(firmId, documentType, financialYearId, updatedAt) == 1,
        ) {
            "Voucher number sequence for $firmId/$documentType/$financialYearId disappeared"
        }
        return sequence.nextNumber
    }
}
