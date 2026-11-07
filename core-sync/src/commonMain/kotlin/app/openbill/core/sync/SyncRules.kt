package app.openbill.core.sync

import app.openbill.core.data.RoomVoucherRepository
import app.openbill.core.database.OpenBillDatabase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Server-side validation (docs/10-server.md section 6.7), run on the server's own database (same
 * schema and DAOs as the app) inside the transaction that applies one op group. A violation
 * rolls the group back; the author gets the server's rows back as corrections.
 */
object SyncRules {
    class Rejected(reason: String) : Exception(reason)

    /** Only these allocation ops broke a limit: the rest of the group (say, the posted payment itself) stands. */
    class AllocationsRejected(val opIds: Set<String>, reason: String) : Exception(reason)

    private val lifecycle = setOf("status", "cancellationReason", "cancelledAt", "restoredAt", "shortClosedAt", "shortCloseNote")
    private val volatile = setOf("updatedAt", "revision")
    private val children = setOf("voucher_lines", "voucher_charges", "voucher_charge_portions")
    private val postings = setOf("journal_entries", "stock_movements")

    /** What one group did so far: vouchers it posts, vouchers it steps (cancel, restore), vouchers whose entries it writes. */
    class Group {
        val fresh = HashSet<String>()
        val stepped = HashSet<String>()
        val posted = HashSet<String>()
    }

    /**
     * Before [op] is applied. A posted or cancelled voucher and its lines never change, except
     * lifecycle fields (cancel, restore, short-close), and only from the revision the server
     * holds: of two devices cancelling one invoice offline, the second is refused, so its
     * reversal entries never double. [fresh] collects vouchers this group posts.
     */
    suspend fun before(rows: SyncRows, op: SyncOperation, group: Group) {
        val fresh = group.fresh
        when (op.entity) {
            "vouchers" -> {
                val current = rows.read("vouchers", rows.key("vouchers", op.payload))
                if (current == null || SyncRows.text(current, "status") == "draft") {
                    fresh += op.entityId
                    return
                }
                val name = SyncRows.text(current, "number") ?: op.entityId
                if (op.kind == SyncOperation.Kind.DELETE) throw Rejected("Posted voucher $name cannot be deleted")
                val changed = op.payload.keys.filter { current[it] != op.payload[it] }
                val altered = changed.filter { it !in lifecycle && it !in volatile }
                if (altered.isNotEmpty()) throw Rejected("Posted voucher $name cannot be altered ($altered)")
                // Every device write here is a lifecycle step from the revision it saw; two devices
                // restoring at one clock time write equal rows, so equality proves nothing. Only a
                // relayed server correction that changes nothing carries no revision.
                val unchangedCorrection = changed.isEmpty() && op.deviceId == SyncRows.SERVER_DEVICE
                if (!unchangedCorrection && op.baseRevision != current["revision"]?.jsonPrimitive?.longOrNull) {
                    throw Rejected("Voucher $name was changed on another device first")
                }
                if (!unchangedCorrection) group.stepped += op.entityId
            }
            in postings -> {
                val current = rows.read(op.entity, rows.key(op.entity, op.payload))
                if (op.kind == SyncOperation.Kind.UPSERT && current != null) {
                    // Bank reconciliation marks an entry cleared; nothing else on an entry changes.
                    val changed = (op.payload.keys + current.keys).filter { current[it] != op.payload[it] }
                    if (changed.all { it == "clearedOn" }) return
                }
                val voucherId = SyncRows.text(op.payload, "voucherId") ?: current?.let { SyncRows.text(it, "voucherId") } ?: return
                val parent = rows.read("vouchers", JsonObject(mapOf("id" to JsonPrimitive(voucherId)))) ?: return
                if (SyncRows.text(parent, "status") != "draft") group.posted += voucherId
            }
            in children -> {
                val current = rows.read(op.entity, rows.key(op.entity, op.payload))
                val voucherId = SyncRows.text(op.payload, "voucherId") ?: current?.let { SyncRows.text(it, "voucherId") } ?: return
                if (voucherId in fresh) return
                val parent = rows.read("vouchers", JsonObject(mapOf("id" to JsonPrimitive(voucherId)))) ?: return
                if (SyncRows.text(parent, "status") == "draft") return
                if (op.kind == SyncOperation.Kind.UPSERT && current == op.payload) return
                throw Rejected("Lines of posted voucher ${SyncRows.text(parent, "number") ?: voucherId} cannot change")
            }
        }
    }

    /**
     * After the group: entries of an existing voucher change only with a lifecycle step of it in
     * the same group, each touched voucher's journal balances, and no allocation exceeds a
     * payment or an invoice.
     */
    suspend fun after(rows: SyncRows, database: OpenBillDatabase, ops: List<SyncOperation>, group: Group) {
        (group.posted - group.fresh - group.stepped).firstOrNull()?.let { throw Rejected("Entries of voucher $it cannot change without a cancel or restore") }
        val upserts = ops.filter { it.kind == SyncOperation.Kind.UPSERT }
        upserts.filter { it.entity == "journal_entries" }.mapNotNull { SyncRows.text(it.payload, "voucherId") }.distinct().forEach { id ->
            val net = rows.long("SELECT COALESCE(SUM(debit), 0) - COALESCE(SUM(credit), 0) FROM journal_entries WHERE voucherId = ?1", id)
            if (net != 0L) throw Rejected("The journal of voucher $id does not balance (off by $net)")
        }
        val allocations = database.paymentAllocationDao()
        val vouchers = database.voucherDao()
        suspend fun fits(id: String): Boolean {
            val voucher = vouchers.findById(id) ?: return false
            return allocations.totalAllocatedForPayment(id) <= voucher.total &&
                allocations.effectiveTotalAllocatedForInvoice(id) <= voucher.total
        }
        val bad = upserts.filter { it.entity == "payment_allocations" }.filter { op ->
            listOf("paymentVoucherId", "invoiceVoucherId").any { side ->
                val id = SyncRows.text(op.payload, side) ?: return@any true
                vouchers.findById(id)?.status != "posted" || !fits(id)
            }
        }
        if (bad.isNotEmpty()) throw AllocationsRejected(bad.map { it.opId }.toSet(), "Allocation exceeds what is left on the payment or invoice, or one of them is no longer posted")
        // A restored payment counts again: it must still fit every invoice it settled.
        upserts.filter { it.entity == "vouchers" && SyncRows.text(it.payload, "status") == "posted" }.forEach { op ->
            val touched = listOf(op.entityId) + rows.texts("SELECT invoiceVoucherId FROM payment_allocations WHERE paymentVoucherId = ?1", op.entityId)
            if (!touched.all { fits(it) }) throw Rejected("Voucher ${SyncRows.text(op.payload, "number") ?: op.entityId} would over-allocate an invoice")
        }
    }

    /**
     * Derived caches are not in ops; every replica recomputes them from the rows it now has:
     * an order's fulfilment, for each voucher written and the order it converts.
     */
    suspend fun refreshDerived(database: OpenBillDatabase, ops: List<SyncOperation>) {
        val orders = ops.filter { it.entity == "vouchers" && it.kind == SyncOperation.Kind.UPSERT }
            .flatMap { listOfNotNull(it.entityId, SyncRows.text(it.payload, "referenceVoucherId")) }.distinct()
        if (orders.isEmpty()) return
        val repository = RoomVoucherRepository(database)
        orders.forEach { repository.refreshOrderFulfilment(it) }
    }
}
