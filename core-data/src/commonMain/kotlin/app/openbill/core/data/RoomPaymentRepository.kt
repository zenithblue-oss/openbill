package app.openbill.core.data

import app.openbill.core.database.AuditEventEntity
import app.openbill.core.database.DatabaseTransactionRunner
import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.domain.model.AllocationRequest
import app.openbill.core.domain.model.OutstandingInvoice
import app.openbill.core.domain.model.PaymentAllocation
import app.openbill.core.domain.model.PaymentDetails
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.model.isPayment
import app.openbill.core.domain.model.isPaymentAccount
import app.openbill.core.domain.repository.PaymentRepository
import app.openbill.core.domain.service.PaymentAllocationPolicy
import app.openbill.core.model.Money
import app.openbill.core.model.Ulid
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate

class RoomPaymentRepository(
    private val database: OpenBillDatabase,
    private val transactions: DatabaseTransactionRunner = DatabaseTransactionRunner(database),
) : PaymentRepository {
    private val details get() = database.paymentDetailsDao()
    private val allocations get() = database.paymentAllocationDao()
    private val vouchers get() = database.voucherDao()
    private val accounts get() = database.ledgerDao()
    private val policy = PaymentAllocationPolicy()

    override suspend fun savePaymentDetails(details: PaymentDetails) {
        transactions.write {
            val voucher = loadVoucher(details.voucherId)
                ?: throw IllegalArgumentException("Unknown voucher: ${details.voucherId}")
            require(voucher.firmId == details.firmId) {
                "Payment details belong to a different firm"
            }
            require(voucher.type.isPayment()) { "Voucher is not a payment" }
            require(voucher.status == VoucherStatus.DRAFT) {
                "Payment details can only be saved for drafts"
            }
            val account = accounts.findAccountById(details.accountId)
                ?: throw IllegalArgumentException("Unknown account: ${details.accountId}")
            require(account.firmId == voucher.firmId) {
                "Payment account belongs to a different firm"
            }
            val domainAccount = account.toDomain()
            require(domainAccount.deletedAt == null) { "Payment account is deleted" }
            require(domainAccount.type.isPaymentAccount) {
                "Payment account must be a cash, bank, or asset account"
            }
            val existing = this.details.findForVoucher(details.voucherId)
            val normalized = details.normalized().copy(
                createdAt = existing?.createdAt?.toInstant() ?: details.createdAt,
            )
            this.details.upsert(normalized.toEntity())
        }
    }

    override suspend fun findPaymentDetails(voucherId: String): PaymentDetails? =
        details.findForVoucher(voucherId)?.toDomain()

    override fun observePaymentDetails(voucherId: String): Flow<PaymentDetails?> =
        details.observeForVoucher(voucherId).map { it?.toDomain() }

    override suspend fun deletePaymentDetails(voucherId: String) {
        transactions.write {
            details.deleteForDraftVoucher(voucherId)
        }
    }

    override suspend fun allocate(
        paymentVoucherId: String,
        requests: List<AllocationRequest>,
        at: Instant,
    ): List<PaymentAllocation> = transactions.write {
        val payment = loadVoucher(paymentVoucherId)
            ?: throw IllegalArgumentException("Unknown voucher: $paymentVoucherId")
        policy.requireAllocatablePayment(payment)
        require(details.findForVoucher(paymentVoucherId) != null) {
            "Payment details are required before allocation"
        }
        val validated = policy.validateRequests(requests)
        val allocated = Money.of(allocations.totalAllocatedForPayment(paymentVoucherId))
        policy.requireWithinPayment(payment, allocated, validated)
        val existingInvoices = allocations.allocationsForPayment(paymentVoucherId)
            .mapTo(mutableSetOf()) { it.invoiceVoucherId }
        policy.requireNotAlreadyAllocated(existingInvoices, validated)

        val created = validated.map { request ->
            val invoice = loadVoucher(request.invoiceVoucherId)
                ?: throw IllegalArgumentException("Unknown voucher: ${request.invoiceVoucherId}")
            policy.requireInvoice(payment, invoice)
            val effective = Money.of(
                allocations.effectiveTotalAllocatedForInvoice(request.invoiceVoucherId),
            )
            policy.requireWithinInvoice(invoice, effective, request)
            PaymentAllocation(
                id = Ulid.create(at.toEpochMilliseconds()).toString(),
                firmId = payment.firmId,
                paymentVoucherId = payment.id,
                invoiceVoucherId = invoice.id,
                amount = request.amount,
                createdAt = at,
                updatedAt = at,
            )
        }
        allocations.insertAll(created.map { it.toEntity() })
        database.auditDao().insert(
            AuditEventEntity(
                id = Ulid.create(at.toEpochMilliseconds()).toString(),
                firmId = payment.firmId,
                entityType = "voucher",
                entityId = payment.id,
                voucherId = payment.id,
                action = "payment.allocated",
                detailsJson = null,
                actor = null,
                createdAt = at.toEpochMilliseconds(),
            ),
        )
        created
    }

    override fun observeAllocationsForPayment(
        paymentVoucherId: String,
    ): Flow<List<PaymentAllocation>> =
        allocations.observeForPayment(paymentVoucherId).map { values ->
            values.map { it.toDomain() }
        }

    override fun observeAllocationsForInvoice(
        invoiceVoucherId: String,
    ): Flow<List<PaymentAllocation>> =
        allocations.observeForInvoice(invoiceVoucherId).map { values ->
            values.map { it.toDomain() }
        }

    override suspend fun allocatedTotalForPayment(paymentVoucherId: String): Money =
        Money.of(allocations.totalAllocatedForPayment(paymentVoucherId))

    override suspend fun invoiceOutstanding(invoiceVoucherId: String): Money {
        val invoice = vouchers.findById(invoiceVoucherId)
            ?: throw IllegalArgumentException("Unknown voucher: $invoiceVoucherId")
        val allocated = allocations.effectiveTotalAllocatedForInvoice(invoiceVoucherId)
        return Money.of(maxOf(0, invoice.total - allocated))
    }

    override fun observeInvoiceOutstanding(invoiceVoucherId: String): Flow<Money> = combine(
        vouchers.observeById(invoiceVoucherId),
        allocations.observeEffectiveTotalAllocatedForInvoice(invoiceVoucherId),
    ) { invoice, allocated ->
        require(invoice != null) { "Unknown voucher: $invoiceVoucherId" }
        Money.of(maxOf(0, invoice.total - allocated))
    }

    override suspend fun outstandingInvoices(
        firmId: String,
        partyId: String,
        type: VoucherType,
    ): List<OutstandingInvoice> {
        require(type == VoucherType.SALE || type == VoucherType.PURCHASE) {
            "Outstanding invoices must be sales or purchases"
        }
        return allocations.outstandingInvoices(
            firmId = firmId,
            partyId = partyId,
            type = type.name.lowercase(),
        ).map { row ->
            OutstandingInvoice(
                voucherId = row.voucherId,
                number = row.number,
                date = LocalDate.parse(row.date),
                dueDate = row.dueDate?.let(LocalDate::parse),
                partyId = row.partyId,
                type = decodeEnum<VoucherType>(row.type, "voucher type"),
                total = Money.of(row.total),
                allocated = Money.of(row.allocated),
            )
        }
    }

    private suspend fun loadVoucher(id: String): Voucher? {
        val entity = vouchers.findById(id) ?: return null
        return entity.toDomain(vouchers.linesFor(id), vouchers.chargesFor(id))
    }
}
