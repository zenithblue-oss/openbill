package app.openbill.core.domain.repository

import app.openbill.core.domain.model.AllocationRequest
import app.openbill.core.domain.model.OutstandingInvoice
import app.openbill.core.domain.model.PaymentAllocation
import app.openbill.core.domain.model.PaymentDetails
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.model.Money
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

interface PaymentRepository {
    suspend fun savePaymentDetails(details: PaymentDetails)

    suspend fun findPaymentDetails(voucherId: String): PaymentDetails?

    fun observePaymentDetails(voucherId: String): Flow<PaymentDetails?>

    suspend fun deletePaymentDetails(voucherId: String)

    suspend fun allocate(
        paymentVoucherId: String,
        requests: List<AllocationRequest>,
        at: Instant,
    ): List<PaymentAllocation>

    fun observeAllocationsForPayment(paymentVoucherId: String): Flow<List<PaymentAllocation>>

    fun observeAllocationsForInvoice(invoiceVoucherId: String): Flow<List<PaymentAllocation>>

    suspend fun allocatedTotalForPayment(paymentVoucherId: String): Money

    suspend fun invoiceOutstanding(invoiceVoucherId: String): Money

    fun observeInvoiceOutstanding(invoiceVoucherId: String): Flow<Money>

    suspend fun outstandingInvoices(
        firmId: String,
        partyId: String,
        type: VoucherType,
    ): List<OutstandingInvoice>
}
