package app.openbill.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.openbill.core.database.PaymentAllocationEntity

@Dao
interface PaymentAllocationDao {
    @Insert
    suspend fun insertAll(allocations: List<PaymentAllocationEntity>)

    @Query(
        "SELECT * FROM payment_allocations WHERE paymentVoucherId = :paymentVoucherId " +
            "ORDER BY createdAt, id",
    )
    suspend fun allocationsForPayment(paymentVoucherId: String): List<PaymentAllocationEntity>

    @Query(
        "SELECT * FROM payment_allocations WHERE invoiceVoucherId = :invoiceVoucherId " +
            "ORDER BY createdAt, id",
    )
    suspend fun allocationsForInvoice(invoiceVoucherId: String): List<PaymentAllocationEntity>

    @Query(
        "SELECT COALESCE(SUM(amount), 0) FROM payment_allocations " +
            "WHERE invoiceVoucherId = :invoiceVoucherId",
    )
    suspend fun totalAllocatedForInvoice(invoiceVoucherId: String): Long
}
