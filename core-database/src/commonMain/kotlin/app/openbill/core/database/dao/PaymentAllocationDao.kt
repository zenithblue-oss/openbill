package app.openbill.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.openbill.core.database.PaymentAllocationEntity
import kotlinx.coroutines.flow.Flow

data class OutstandingInvoiceRow(
    val voucherId: String,
    val number: String?,
    val date: String,
    val dueDate: String?,
    val partyId: String,
    val type: String,
    val total: Long,
    val allocated: Long,
)

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
        "SELECT * FROM payment_allocations WHERE paymentVoucherId = :paymentVoucherId " +
            "ORDER BY createdAt, id",
    )
    fun observeForPayment(paymentVoucherId: String): Flow<List<PaymentAllocationEntity>>

    @Query(
        "SELECT * FROM payment_allocations WHERE invoiceVoucherId = :invoiceVoucherId " +
            "ORDER BY createdAt, id",
    )
    suspend fun allocationsForInvoice(invoiceVoucherId: String): List<PaymentAllocationEntity>

    @Query(
        "SELECT * FROM payment_allocations WHERE invoiceVoucherId = :invoiceVoucherId " +
            "ORDER BY createdAt, id",
    )
    fun observeForInvoice(invoiceVoucherId: String): Flow<List<PaymentAllocationEntity>>

    @Query(
        "SELECT COALESCE(SUM(amount), 0) FROM payment_allocations " +
            "WHERE invoiceVoucherId = :invoiceVoucherId",
    )
    suspend fun totalAllocatedForInvoice(invoiceVoucherId: String): Long

    @Query(
        "SELECT COALESCE(SUM(pa.amount), 0) FROM payment_allocations pa " +
            "INNER JOIN vouchers payment ON payment.id = pa.paymentVoucherId " +
            "WHERE pa.invoiceVoucherId = :invoiceVoucherId " +
            "AND payment.status = 'posted' AND payment.deletedAt IS NULL",
    )
    suspend fun effectiveTotalAllocatedForInvoice(invoiceVoucherId: String): Long

    @Query(
        "SELECT COALESCE(SUM(pa.amount), 0) FROM payment_allocations pa " +
            "INNER JOIN vouchers payment ON payment.id = pa.paymentVoucherId " +
            "WHERE pa.invoiceVoucherId = :invoiceVoucherId " +
            "AND payment.status = 'posted' AND payment.deletedAt IS NULL",
    )
    fun observeEffectiveTotalAllocatedForInvoice(invoiceVoucherId: String): Flow<Long>

    @Query(
        "SELECT COALESCE(SUM(amount), 0) FROM payment_allocations " +
            "WHERE paymentVoucherId = :paymentVoucherId",
    )
    suspend fun totalAllocatedForPayment(paymentVoucherId: String): Long

    @Query(
        "SELECT COALESCE(SUM(amount), 0) FROM payment_allocations " +
            "WHERE paymentVoucherId = :paymentVoucherId",
    )
    fun observeTotalAllocatedForPayment(paymentVoucherId: String): Flow<Long>

    @Query(
        "SELECT v.id AS voucherId, v.number AS number, v.date AS date, " +
            "v.dueDate AS dueDate, v.partyId AS partyId, v.type AS type, " +
            "v.total AS total, " +
            "COALESCE((" +
            "SELECT SUM(pa.amount) FROM payment_allocations pa " +
            "INNER JOIN vouchers payment ON payment.id = pa.paymentVoucherId " +
            "WHERE pa.invoiceVoucherId = v.id " +
            "AND payment.status = 'posted' AND payment.deletedAt IS NULL" +
            "), 0) AS allocated " +
            "FROM vouchers v " +
            "WHERE v.firmId = :firmId AND v.partyId = :partyId AND v.type = :type " +
            "AND v.status = 'posted' AND v.deletedAt IS NULL " +
            "AND v.total > COALESCE((" +
            "SELECT SUM(pa.amount) FROM payment_allocations pa " +
            "INNER JOIN vouchers payment ON payment.id = pa.paymentVoucherId " +
            "WHERE pa.invoiceVoucherId = v.id " +
            "AND payment.status = 'posted' AND payment.deletedAt IS NULL" +
            "), 0) " +
            "ORDER BY v.date, v.id",
    )
    suspend fun outstandingInvoices(
        firmId: String,
        partyId: String,
        type: String,
    ): List<OutstandingInvoiceRow>
}
