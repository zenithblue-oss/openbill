package app.openbill.core.domain.fake

import app.openbill.core.domain.model.AllocationRequest
import app.openbill.core.domain.model.OutstandingInvoice
import app.openbill.core.domain.model.PaymentAllocation
import app.openbill.core.domain.model.PaymentDetails
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.repository.PaymentRepository
import app.openbill.core.domain.repository.VoucherRepository
import app.openbill.core.domain.service.PaymentAllocationPolicy
import app.openbill.core.model.Money
import app.openbill.core.model.Ulid
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate

class InMemoryPaymentRepository(
    private val policy: PaymentAllocationPolicy = PaymentAllocationPolicy(),
) : PaymentRepository {
    private val details = MutableStateFlow<Map<String, PaymentDetails>>(emptyMap())
    private val allocations = MutableStateFlow<List<PaymentAllocation>>(emptyList())
    private val mutex = Mutex()
    private var vouchers: VoucherRepository? = null

    fun attachVouchers(vouchers: VoucherRepository) {
        this.vouchers = vouchers
    }

    override suspend fun savePaymentDetails(details: PaymentDetails) = mutex.withLock {
        val existing = this.details.value[details.voucherId]
        val normalized = details.normalized().copy(
            createdAt = existing?.createdAt ?: details.createdAt,
        )
        this.details.update { current -> current + (normalized.voucherId to normalized) }
    }

    override suspend fun findPaymentDetails(voucherId: String): PaymentDetails? =
        details.value[voucherId]

    override fun observePaymentDetails(voucherId: String): Flow<PaymentDetails?> =
        details.map { values -> values[voucherId] }

    override suspend fun deletePaymentDetails(voucherId: String) = mutex.withLock {
        val voucher = vouchers?.findVoucher(voucherId)
        if (voucher == null || voucher.status == VoucherStatus.DRAFT) {
            details.update { current -> current - voucherId }
        }
    }

    override suspend fun allocate(
        paymentVoucherId: String,
        requests: List<AllocationRequest>,
        at: Instant,
    ): List<PaymentAllocation> = mutex.withLock {
        val voucherRepository = requireVouchers()
        val payment = voucherRepository.findVoucher(paymentVoucherId)
            ?: throw IllegalArgumentException("Unknown payment: $paymentVoucherId")
        policy.requireAllocatablePayment(payment)
        require(details.value.containsKey(paymentVoucherId)) {
            "Payment details are required before allocation"
        }
        policy.validateRequests(requests)

        val existing = allocations.value
            .filter { it.paymentVoucherId == paymentVoucherId }
            .sortedWith(compareBy<PaymentAllocation> { it.createdAt }.thenBy { it.id })
        val existingTotal = existing.fold(Money.ZERO) { total, allocation ->
            total + allocation.amount
        }
        policy.requireWithinPayment(payment, existingTotal, requests)
        policy.requireNotAlreadyAllocated(existing.map { it.invoiceVoucherId }.toSet(), requests)

        val invoices = requests.associate { request ->
            val invoice = voucherRepository.findVoucher(request.invoiceVoucherId)
                ?: throw IllegalArgumentException("Unknown invoice: ${request.invoiceVoucherId}")
            policy.requireInvoice(payment, invoice)
            val allocated = effectiveAllocatedForInvoice(invoice.id, voucherRepository)
            policy.requireWithinInvoice(invoice, allocated, request)
            invoice.id to invoice
        }
        check(invoices.size == requests.size)

        val created = requests.map { request ->
            PaymentAllocation(
                id = Ulid.create(at.toEpochMilliseconds()).toString(),
                firmId = payment.firmId,
                paymentVoucherId = payment.id,
                invoiceVoucherId = request.invoiceVoucherId,
                amount = request.amount,
                createdAt = at,
                updatedAt = at,
            )
        }
        allocations.update { current -> current + created }
        created
    }

    override fun observeAllocationsForPayment(paymentVoucherId: String): Flow<List<PaymentAllocation>> =
        allocations.map { values ->
            values.filter { it.paymentVoucherId == paymentVoucherId }
                .sortedWith(compareBy<PaymentAllocation> { it.createdAt }.thenBy { it.id })
        }

    override fun observeAllocationsForInvoice(invoiceVoucherId: String): Flow<List<PaymentAllocation>> =
        allocations.map { values ->
            values.filter { it.invoiceVoucherId == invoiceVoucherId }
                .sortedWith(compareBy<PaymentAllocation> { it.createdAt }.thenBy { it.id })
        }

    override suspend fun allocatedTotalForPayment(paymentVoucherId: String): Money =
        allocations.value
            .filter { it.paymentVoucherId == paymentVoucherId }
            .fold(Money.ZERO) { total, allocation -> total + allocation.amount }

    override suspend fun invoiceOutstanding(invoiceVoucherId: String): Money {
        val invoice = requireVouchers().findVoucher(invoiceVoucherId)
            ?: throw IllegalArgumentException("Unknown invoice: $invoiceVoucherId")
        return invoice.total - effectiveAllocatedForInvoice(invoice.id, requireVouchers())
    }

    override fun observeInvoiceOutstanding(invoiceVoucherId: String): Flow<Money> {
        val voucherRepository = vouchers
            ?: return flow { emit(invoiceOutstanding(invoiceVoucherId)) }
        return flow {
            val invoice = voucherRepository.findVoucher(invoiceVoucherId)
                ?: throw IllegalArgumentException("Unknown invoice: $invoiceVoucherId")
            emitAll(
                combine(
                    allocations,
                    voucherRepository.observeVouchers(
                        invoice.firmId,
                        MIN_DATE,
                        MAX_DATE,
                    ),
                ) { allocationValues, voucherValues ->
                    val vouchersById = voucherValues.associateBy { it.id }
                    val effective = allocationValues
                        .filter { it.invoiceVoucherId == invoiceVoucherId }
                        .fold(Money.ZERO) { total, allocation ->
                            val payment = vouchersById[allocation.paymentVoucherId]
                            if (payment?.status == VoucherStatus.POSTED && payment.deletedAt == null) {
                                total + allocation.amount
                            } else {
                                total
                            }
                        }
                    invoice.total - effective
                },
            )
        }
    }

    override suspend fun outstandingInvoices(
        firmId: String,
        partyId: String,
        type: VoucherType,
    ): List<OutstandingInvoice> {
        require(type == VoucherType.SALE || type == VoucherType.PURCHASE) {
            "Outstanding invoices must be sales or purchases"
        }
        val voucherRepository = requireVouchers()
        val invoices = voucherRepository
            .observeVouchers(firmId, MIN_DATE, MAX_DATE)
            .first()
            .filter {
                it.firmId == firmId &&
                    it.partyId == partyId &&
                    it.type == type &&
                    it.status == VoucherStatus.POSTED &&
                    it.deletedAt == null
            }
        return invoices.mapNotNull { invoice ->
            val allocated = effectiveAllocatedForInvoice(invoice.id, voucherRepository)
            if (invoice.total <= allocated) {
                null
            } else {
                OutstandingInvoice(
                    voucherId = invoice.id,
                    number = invoice.number,
                    date = invoice.date,
                    dueDate = invoice.dueDate,
                    partyId = partyId,
                    type = invoice.type,
                    total = invoice.total,
                    allocated = allocated,
                )
            }
        }.sortedWith(compareBy<OutstandingInvoice> { it.date }.thenBy { it.voucherId })
    }

    private suspend fun effectiveAllocatedForInvoice(
        invoiceVoucherId: String,
        voucherRepository: VoucherRepository,
    ): Money {
        var allocated = Money.ZERO
        for (allocation in allocations.value) {
            if (allocation.invoiceVoucherId != invoiceVoucherId) continue
            val payment = voucherRepository.findVoucher(allocation.paymentVoucherId)
            if (payment == null) continue
            if (payment.status == VoucherStatus.POSTED && payment.deletedAt == null) {
                allocated += allocation.amount
            }
        }
        return allocated
    }

    private fun requireVouchers(): VoucherRepository = vouchers
        ?: throw IllegalStateException("No voucher repository is attached")

    private companion object {
        val MIN_DATE = LocalDate(1, 1, 1)
        val MAX_DATE = LocalDate(9999, 12, 31)
    }
}
