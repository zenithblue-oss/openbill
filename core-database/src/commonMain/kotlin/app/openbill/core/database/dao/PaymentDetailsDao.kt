package app.openbill.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.openbill.core.database.PaymentDetailsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PaymentDetailsDao {
    @Upsert
    suspend fun upsert(details: PaymentDetailsEntity)

    @Query("SELECT * FROM payment_details WHERE voucherId = :voucherId LIMIT 1")
    suspend fun findForVoucher(voucherId: String): PaymentDetailsEntity?

    @Query("SELECT * FROM payment_details WHERE voucherId = :voucherId LIMIT 1")
    fun observeForVoucher(voucherId: String): Flow<PaymentDetailsEntity?>

    @Query("DELETE FROM payment_details WHERE voucherId = :voucherId")
    suspend fun delete(voucherId: String): Int

    @Query(
        "DELETE FROM payment_details WHERE voucherId = :voucherId AND EXISTS (" +
            "SELECT 1 FROM vouchers WHERE id = :voucherId AND status = 'draft')",
    )
    suspend fun deleteForDraftVoucher(voucherId: String): Int
}
