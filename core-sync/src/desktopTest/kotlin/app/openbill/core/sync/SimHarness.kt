package app.openbill.core.sync

import androidx.room.Room
import androidx.room.useReaderConnection
import app.openbill.core.data.roomRepositories
import app.openbill.core.database.OpenBillDatabase
import app.openbill.core.database.SyncTriggers
import app.openbill.core.database.openBillDatabase
import app.openbill.core.domain.model.AllocationRequest
import app.openbill.core.domain.model.FinancialYear
import app.openbill.core.domain.model.Firm
import app.openbill.core.domain.model.FirmSettings
import app.openbill.core.domain.model.Godown
import app.openbill.core.domain.model.Item
import app.openbill.core.domain.model.Party
import app.openbill.core.domain.model.PartyType
import app.openbill.core.domain.model.PaymentDetails
import app.openbill.core.domain.model.PaymentMethod
import app.openbill.core.domain.model.UnitOfMeasure
import app.openbill.core.domain.model.Voucher
import app.openbill.core.domain.model.VoucherLine
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.repository.Repositories
import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import app.openbill.core.model.Rate
import app.openbill.core.model.Ulid
import java.io.File
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** A business day on real repositories and real SQLite files, for the sync tests and the simulation (docs/10-server.md 16). */
internal const val WS = "ws-1"
internal const val FIRM = "firm-1"
internal const val FY = "fy-1"
internal val BASE: Instant = Instant.parse("2026-06-01T04:30:00Z")
internal val CUSTOMERS = listOf("cust-1", "cust-2", "cust-3", "cust-4")
internal val ITEMS = listOf("item-widget", "item-bolt", "item-syrup")

/** A transport that loses requests and responses, repeats calls, and repeats, reorders and truncates pulled pages. */
internal class FlakyTransport(private val server: SyncTransport, private val random: Random, var faults: Double) : SyncTransport {
    var calls = 0
        private set

    private fun roll() = random.nextDouble() < faults

    override suspend fun push(workspaceId: String, ops: List<SyncOperation>): PushResponse {
        calls++
        if (roll()) throw SyncTransportException("request lost")
        val first = server.push(workspaceId, ops)
        if (roll()) throw SyncTransportException("response lost")
        return if (roll()) server.push(workspaceId, ops) else first
    }

    override suspend fun pull(workspaceId: String, after: Long, limit: Int): PullResponse {
        calls++
        if (roll()) throw SyncTransportException("request lost")
        val page = server.pull(workspaceId, after, if (roll()) random.nextInt(1, limit + 1) else limit)
        if (!roll() || page.ops.isEmpty()) return page
        val messed = (page.ops + page.ops.take(random.nextInt(page.ops.size + 1))).shuffled(random).dropLast(random.nextInt(page.ops.size))
        return page.copy(ops = messed)
    }

    override suspend fun snapshot(workspaceId: String) = server.snapshot(workspaceId)
}

/** [link]: how the device reaches [server] (in process, or over HTTP in the contract test). */
internal class SimDevice(val name: String, val dir: File, val series: String, private val server: SyncServer, seed: Long, link: SyncTransport = server) {
    val random = Random(seed)
    val transport = FlakyTransport(link, Random(seed + 1), faults = 0.0)
    val file = File(dir, "openbill.db")
    val blobs = FileBlobRefs(File(dir, "blobs"))
    lateinit var db: OpenBillDatabase
    lateinit var repos: Repositories
    lateinit var client: SyncClient
    var online = true

    /** Wall clock of this device: skewed by up to two hours, a few minutes per step. */
    val skew = (random.nextInt(-120, 121)).minutes
    var step = 0
    fun now(): Instant = BASE + skew + (step * 3).minutes
    fun today(): LocalDate = now().toLocalDateTime(TimeZone.UTC).date

    fun open() {
        dir.mkdirs()
        db = openBillDatabase(Room.databaseBuilder<OpenBillDatabase>(name = file.path))
        repos = db.roomRepositories()
        client = SyncClient(db, transport, WS, blobs = blobs, random = Random(random.nextLong()), sleep = {}, pageSize = 50)
    }

    fun close() = db.close()

    /** Joins the workspace from the server's snapshot (section 6.6). */
    suspend fun join() {
        val snapshot = server.snapshot(WS)
        dir.mkdirs()
        file.writeBytes(snapshot.database)
        SyncSnapshots.prepare(file)
        open()
        SyncOutbox(db).enable(series, snapshot.seq, snapshot.epoch)
    }

    suspend fun sync(): SyncReport? = if (online) client.sync() else null
}

internal suspend fun seedFirm(repos: Repositories) {
    val at = BASE
    repos.firms.saveFirm(Firm(id = FIRM, name = "Sim Traders", stateCode = "27", addressLine1 = "1 Market Road", city = "Pune", gstin = "27ABCDE1234F1Z5", createdAt = at, updatedAt = at))
    repos.firms.saveSettings(FirmSettings(firmId = FIRM, invoicePrefix = "INV", defaultGodownId = "godown-1", negativeStockAllowed = true))
    repos.firms.saveFinancialYear(FinancialYear(FY, FIRM, LocalDate(2026, 4, 1), LocalDate(2027, 3, 31)))
    repos.catalog.saveUnit(UnitOfMeasure(id = "unit-1", firmId = FIRM, name = "Piece", symbol = "pc", createdAt = at, updatedAt = at))
    repos.catalog.saveGodown(Godown(id = "godown-1", firmId = FIRM, name = "Main", isDefault = true, createdAt = at, updatedAt = at))
    ITEMS.forEachIndexed { i, id ->
        repos.catalog.saveItem(
            Item(
                id = id, firmId = FIRM, name = id.removePrefix("item-"), unitId = "unit-1", hsnSac = "7326", taxRate = Rate.ofPercent(18),
                salePrice = Money.of(1_000L * (i + 1)), purchasePrice = Money.of(800L * (i + 1)), trackInventory = true, trackBatch = id == "item-syrup",
                createdAt = at, updatedAt = at,
            ),
        )
    }
    CUSTOMERS.forEach { repos.parties.saveParty(Party(id = it, firmId = FIRM, name = "Customer $it", type = PartyType.CUSTOMER, stateCode = "27", createdAt = at, updatedAt = at)) }
    repos.parties.saveParty(Party(id = "supp-1", firmId = FIRM, name = "Supplier", type = PartyType.SUPPLIER, stateCode = "27", createdAt = at, updatedAt = at))
}

/** Invoice of [lines] (item, qty) at 18% GST: taxable = rate x qty, total = taxable x 1.18 (rates are multiples of 100 paise). */
internal fun bill(id: String, type: VoucherType, partyId: String, date: LocalDate, at: Instant, lines: List<Triple<String, Long, Long>>, batch: String? = null): Voucher {
    val built = lines.mapIndexed { i, (item, qty, rate) ->
        val taxable = rate * qty
        VoucherLine(
            id = "$id-l$i", lineNumber = i + 1, itemId = item, description = item, quantity = Quantity.of(qty), rate = Money.of(rate),
            taxRate = Rate.ofPercent(18), taxable = Money.of(taxable), tax = Money.of(taxable * 18 / 100), total = Money.of(taxable * 118 / 100),
            batchNumber = batch.takeIf { item == "item-syrup" },
        )
    }
    val taxable = built.sumOf { it.taxable.paise }
    val tax = built.sumOf { it.tax.paise }
    return Voucher(
        id = id, firmId = FIRM, financialYearId = FY, type = type, date = date, partyId = partyId, status = VoucherStatus.DRAFT,
        placeOfSupplyStateCode = "27", lines = built, subtotal = Money.of(taxable), taxable = Money.of(taxable), tax = Money.of(tax),
        total = Money.of(taxable + tax), createdAt = at, updatedAt = at,
    )
}

/** Receipt of [amount] from [partyId] into the firm's cash account, allocated to [invoiceId]; one group when [together]. */
internal suspend fun receive(repos: Repositories, id: String, partyId: String, amount: Long, invoiceId: String?, date: LocalDate, at: Instant, together: Boolean) {
    val cash = repos.ledger.ensureSystemAccounts(FIRM, at).cash
    suspend fun run() {
        repos.vouchers.saveDraft(
            Voucher(
                id = id, firmId = FIRM, financialYearId = FY, type = VoucherType.PAYMENT_IN, date = date, partyId = partyId, status = VoucherStatus.DRAFT,
                placeOfSupplyStateCode = "27", total = Money.of(amount), createdAt = at, updatedAt = at,
            ),
        )
        repos.payments.savePaymentDetails(PaymentDetails(voucherId = id, firmId = FIRM, accountId = cash.id, method = PaymentMethod.CASH, createdAt = at, updatedAt = at))
        repos.vouchers.post(id, at)
        if (invoiceId != null) repos.payments.allocate(id, listOf(AllocationRequest(invoiceId, Money.of(amount))), at)
    }
    if (together) repos.transaction { run() } else run()
}

internal fun newId(device: SimDevice) = Ulid.create(device.now().toEpochMilliseconds(), device.random).toString()

internal suspend fun OpenBillDatabase.query(sql: String, vararg args: String): List<List<String?>> = useReaderConnection { c ->
    c.usePrepared(sql) { s ->
        args.forEachIndexed { i, a -> s.bindText(i + 1, a) }
        buildList { while (s.step()) add((0 until s.getColumnCount()).map { if (s.isNull(it)) null else s.getText(it) }) }
    }
}

internal suspend fun OpenBillDatabase.ids(sql: String, vararg args: String): List<String> = query(sql, *args).map { it[0]!! }

/**
 * Every synced row except device-local ones (drafts and what hangs off them), as canonical JSON,
 * file references as content refs: equal dumps mean converged replicas.
 */
internal suspend fun OpenBillDatabase.canonicalDump(blobs: BlobRefs): Map<String, List<String>> {
    val tables = useReaderConnection { c ->
        c.usePrepared("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name") { s ->
            buildList { while (s.step()) add(s.getText(0)) }
        }
    }.filter { it !in SyncTriggers.LOCAL_TABLES }
    return tables.associateWith { table ->
        val shape = useReaderConnection { c -> c.usePrepared("PRAGMA table_info(`$table`)") { s -> buildList { while (s.step()) add(s.getText(1) to s.getLong(5)) } } }
        val shared = SyncTriggers.Shape(shape.map { it.first }, shape.filter { it.second > 0 }.sortedBy { it.second }.map { it.first }).shared(table)
        val local = SyncTriggers.localCondition(table, "r")?.let { "WHERE NOT ($it)" }.orEmpty()
        val paths = BlobRefs.PATH_COLUMNS[table].orEmpty()
        query("SELECT ${SyncTriggers.json("r", shared)} FROM `$table` r $local").map { row ->
            val o = kotlinx.serialization.json.Json.parseToJsonElement(row[0]!!) as kotlinx.serialization.json.JsonObject
            kotlinx.serialization.json.JsonObject(
                o.mapValues { (k, v) ->
                    if (k in paths && v is kotlinx.serialization.json.JsonPrimitive && v.isString) kotlinx.serialization.json.JsonPrimitive(blobs.refOf(v.content)) else v
                }.toSortedMap(),
            ).toString()
        }.sorted()
    }
}

/** Rows only one side has, per table; empty when converged. */
internal fun dumpDiff(expected: Map<String, List<String>>, actual: Map<String, List<String>>): String = (expected.keys + actual.keys).sorted().mapNotNull { t ->
    val e = expected[t].orEmpty()
    val a = actual[t].orEmpty()
    if (e == a) null else "$t: only expected ${(e - a.toSet()).take(3)}; only actual ${(a - e.toSet()).take(3)}"
}.joinToString("\n")

internal suspend fun parties(repos: Repositories): List<Party> = repos.parties.observeParties(FIRM).first()
