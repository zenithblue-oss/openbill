package app.openbill.core.domain.usecase

import app.openbill.core.domain.model.AllocationRequest
import app.openbill.core.domain.model.PaymentAllocation
import app.openbill.core.domain.repository.PaymentRepository
import kotlin.time.Instant

class AllocatePayment(private val payments: PaymentRepository) {
    suspend operator fun invoke(
        paymentVoucherId: String,
        requests: List<AllocationRequest>,
        at: Instant,
    ): List<PaymentAllocation> {
        require(requests.isNotEmpty()) { "At least one allocation is required" }
        return payments.allocate(paymentVoucherId, requests, at)
    }
}
