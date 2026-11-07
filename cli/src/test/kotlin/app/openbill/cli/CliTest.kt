package app.openbill.cli

import app.openbill.core.data.roomRepositories
import app.openbill.core.database.openBillDatabase
import app.openbill.core.domain.model.FinancialYear
import app.openbill.core.domain.model.Firm
import app.openbill.core.domain.model.FirmSettings
import app.openbill.core.domain.model.Godown
import app.openbill.core.domain.model.Item
import app.openbill.core.domain.model.Party
import app.openbill.core.domain.model.PartyType
import app.openbill.core.domain.model.RecurrenceFrequency
import app.openbill.core.domain.model.RecurringSchedule
import app.openbill.core.domain.model.UnitOfMeasure
import app.openbill.core.model.Money
import app.openbill.core.model.Rate
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate

class CliTest {
    private val root = Files.createTempDirectory("openbill-cli-test").toFile()
    private val dataDir = File(root, "data")

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    data class Result(val code: Int, val out: String, val err: String) {
        fun golden() = "exit: $code\n--- stdout\n$out--- stderr\n$err"
    }

    private var ids = 0

    private var password: CharArray? = null
    private val lock = HashMap<String, String>()
    private val stdin = ArrayDeque<String>()
    private var pinEnv: String? = null

    private fun run(vararg args: String, dir: File = dataDir): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val env = CliEnv(
            out = PrintStream(out, true, Charsets.UTF_8),
            err = PrintStream(err, true, Charsets.UTF_8),
            clock = object : Clock {
                override fun now() = Instant.fromEpochMilliseconds(1_790_000_000_000L)
            },
            newId = { "ID%04d".format(++ids) },
            interactive = false,
            appVersion = "test",
            defaultDataDir = { dir },
            backupPassword = password,
            readSecret = { null },
            readLine = { stdin.removeFirstOrNull() },
            user = null,
            pin = pinEnv,
            newPin = null,
            lockStore = object : LockStore {
                override fun get(key: String) = lock[key]
                override fun put(key: String, value: String) {
                    lock[key] = value
                }
            },
        )
        val code = runCli(args.toList(), env)
        fun norm(s: ByteArrayOutputStream) = s.toString(Charsets.UTF_8).replace("\r\n", "\n")
        return Result(code, norm(out), norm(err))
    }

    private fun assertGolden(name: String, r: Result) {
        val dir = File(System.getProperty("openbill.goldenDir") ?: "src/test/resources/golden")
        val f = File(dir, "$name.txt")
        if (System.getProperty("openbill.updateGoldens") == "true" || !f.exists()) {
            dir.mkdirs()
            f.writeText(r.golden())
        }
        assertEquals(f.readText(), r.golden(), "golden $name")
    }

    private fun seed(dir: File = dataDir, firmName: String = "Sharma Traders") {
        dir.mkdirs()
        val db = openBillDatabase(File(dir, "openbill.db").absolutePath)
        val at = Instant.fromEpochMilliseconds(1_780_000_000_000L)
        runBlocking {
            val r = db.roomRepositories()
            r.firms.saveFirm(Firm(id = "F1", name = firmName, gstin = "27ABCDE1234F1Z5", stateCode = "27", addressLine1 = "1 Market Road", city = "Pune", createdAt = at, updatedAt = at))
            r.catalog.saveGodown(Godown(id = "G1", firmId = "F1", name = "Main", isDefault = true, createdAt = at, updatedAt = at))
            r.firms.saveSettings(FirmSettings(firmId = "F1", defaultGodownId = "G1", negativeStockAllowed = true))
            r.firms.saveFinancialYear(FinancialYear(id = "FY1", firmId = "F1", startDate = LocalDate(2026, 4, 1), endDate = LocalDate(2027, 3, 31)))
            r.catalog.saveUnit(UnitOfMeasure(id = "U1", firmId = "F1", name = "Piece", symbol = "pcs", createdAt = at, updatedAt = at))
            r.parties.saveParty(Party(id = "P1", firmId = "F1", name = "Acme Stores", type = PartyType.CUSTOMER, phone = "9876543210", gstin = "27AAAPL1234C1Z5", stateCode = "27", createdAt = at, updatedAt = at))
            r.parties.saveParty(Party(id = "P2", firmId = "F1", name = "Bharat Supplies", type = PartyType.SUPPLIER, stateCode = "29", createdAt = at, updatedAt = at))
            r.catalog.saveItem(Item(id = "I1", firmId = "F1", name = "Steel Bolt", sku = "BOLT", unitId = "U1", hsnSac = "7318", taxRate = Rate.ofPercent(18), salePrice = Money.parse("12.50"), createdAt = at, updatedAt = at))
            r.catalog.saveItem(Item(id = "I2", firmId = "F1", name = "Fitting Service", sku = "SVC", unitId = "U1", isService = true, trackInventory = false, taxRate = Rate.ofPercent(18), salePrice = Money.parse("1500.00"), createdAt = at, updatedAt = at))
        }
        db.close()
    }

    @Test
    fun goldenListings() {
        seed()
        assertGolden("firm-list", run("firm", "list"))
        assertGolden("party-list", run("party", "list"))
        assertGolden("party-list-json", run("--json", "party", "list"))
        assertGolden("item-list", run("item", "list"))
        assertGolden("export-parties-csv", run("export", "parties", "--format", "csv"))
    }

    @Test
    fun invoiceLifecycle() {
        seed()
        val created = run("invoice", "create", "--party", "Acme Stores", "--date", "2026-10-01", "--line", "BOLT:10", "--line", "SVC:1@1000.00")
        assertEquals(0, created.code, created.err)
        val id = created.out.trim()
        assertGolden("invoice-show-draft", run("invoice", "show", id))
        val posted = run("--json", "invoice", "post", id)
        assertGolden("invoice-post-json", posted)
        assertEquals(5, run("invoice", "post", id).code) // no longer a draft
        val number = run("-q", "invoice", "list", "--status", "posted").out.trim()
        assertEquals(2, run("invoice", "cancel", number, "--reason", "Wrong party").code) // needs --yes off a terminal
        assertEquals(0, run("--yes", "invoice", "cancel", number, "--reason", "Wrong party").code)
        assertGolden("invoice-list", run("invoice", "list"))
        assertEquals(4, run("invoice", "show", "NOPE").code)
    }

    @Test
    fun searchRestoreAndRecycleBin() {
        seed()
        fun ok(vararg a: String) = run(*a).also { assertEquals(0, it.code, it.err) }
        val number = ok("-q", "invoice", "create", "--party", "Acme Stores", "--date", "2026-10-01", "--line", "BOLT:2", "--post").out.trim()
        val all = ok("--json", "search", "Acme").out
        assertTrue("\"kind\": \"party\"" in all && "\"kind\": \"sale\"" in all, all)
        assertTrue(ok("search", "bolt", "--type", "items").out.contains("Steel Bolt"))
        assertTrue(ok("--json", "search", "Acme", "--type", "payments").out.trim() == "[]")
        assertEquals(2, run("search", "x", "--type", "nope").code)

        assertEquals(5, run("invoice", "restore", number, "--reason", "x").code) // posted, not cancelled
        ok("--yes", "invoice", "cancel", number, "--reason", "Wrong rate")
        assertEquals(2, run("--yes", "invoice", "restore", number).code) // needs --reason
        ok("--yes", "invoice", "restore", number, "--reason", "Rate was right")
        assertTrue(ok("invoice", "show", number).out.contains("posted"))
        assertTrue(ok("audit", "list").out.contains("restore"))

        val draft = ok("invoice", "create", "--party", "Acme Stores", "--date", "2026-10-02", "--line", "BOLT:1").out.trim()
        ok("invoice", "delete", draft)
        assertTrue(ok("-q", "invoice", "recycle-bin").out.contains(draft))
        ok("invoice", "restore", draft)
        assertTrue(ok("-q", "invoice", "recycle-bin").out.isBlank())
        ok("invoice", "delete", draft)
        assertEquals(2, run("invoice", "recycle-bin", "--purge", draft).code) // needs --yes
        ok("--yes", "invoice", "recycle-bin", "--purge", draft)
        assertEquals(4, run("invoice", "show", draft).code)
    }

    @Test
    fun pinHashMatchesTheAppFormat() {
        // PBKDF2-HMAC-SHA256, 1000 rounds, independently computed (Python hashlib).
        val stored = "1000:00112233445566778899aabbccddeeff:b8ad0f1dacec0beb4f418fadcb2ee5939ef45823391bb89692028dd1b67e9cef"
        assertTrue(Pins.verify("1234", stored))
        assertTrue(!Pins.verify("1235", stored) && !Pins.verify("1234", null) && !Pins.verify("1234", "junk"))
        assertTrue(Pins.verify("2468", Pins.hash("2468")))
        assertEquals("Use at least 4 digits", Pins.error("12"))
    }

    @Test
    fun usersSignInAndRolePermissions() {
        seed()
        fun ok(vararg a: String) = run(*a).also { assertEquals(0, it.code, it.err) }
        fun pinned(vararg pins: String) = stdin.apply { clear(); addAll(pins) }
        // Owner-only, and the owner PIN is the desktop app-lock PIN: not set yet.
        pinned("1234", "5678")
        assertEquals(8, run("--pin-stdin", "users", "add", "--name", "Ravi", "--role", "cashier", "--new-pin-stdin").code)
        lock[Pins.KEY_PIN] = Pins.hash("1234")
        assertEquals(2, run("--pin", "1234", "users", "list").code) // no PIN in argv
        pinned("1234", "56")
        assertEquals(3, run("--pin-stdin", "users", "add", "--name", "Ravi", "--role", "cashier", "--new-pin-stdin").code) // PIN too short
        pinned("1234", "5678")
        ok("--pin-stdin", "users", "add", "--name", "Ravi", "--role", "cashier", "--new-pin-stdin")
        pinned("1234", "9999")
        assertEquals(8, run("--pin-stdin", "users", "add", "--name", "Asha", "--role", "accountant", "--new-pin-stdin").code) // staff now: sign in
        pinned("1234", "9999")
        ok("--user", "Owner", "--pin-stdin", "users", "add", "--name", "Asha", "--role", "accountant", "--new-pin-stdin")

        // Staff exist: every command needs a signed-in user.
        assertEquals(8, run("party", "list").code)
        pinEnv = "5678"
        ok("--user", "Ravi", "party", "list")
        ok("--user", "ravi", "invoice", "create", "--party", "Acme Stores", "--date", "2026-10-01", "--line", "BOLT:2", "--post")
        assertEquals(8, run("--user", "Ravi", "invoice", "create", "--party", "Acme Stores", "--date", "2026-10-01", "--line", "BOLT:2@1.00").code) // cashier price
        assertEquals(8, run("--user", "Ravi", "report", "sales-register").code)
        assertEquals(8, run("--user", "Ravi", "audit", "list").code)
        assertEquals(8, run("--user", "Ravi", "export", "parties").code)
        assertEquals(8, run("--user", "Ravi", "backup", "create", "--out", File(root, "b").path).code)
        assertEquals(8, run("--user", "Ravi", "users", "list").code)
        val number = run("-q", "--user", "Ravi", "invoice", "list").out.trim()
        assertEquals(8, run("--yes", "--user", "Ravi", "invoice", "cancel", number, "--reason", "x").code)
        pinEnv = "9999"
        ok("--user", "Asha", "report", "sales-register")
        assertTrue(ok("--user", "Asha", "audit", "list", "--user", "Ravi").out.contains("voucher.posted"))
        assertTrue(ok("--user", "Asha", "audit", "list", "--user", "Asha").out.lines().none { it.contains("voucher.posted") })
        assertEquals(8, run("--user", "Asha", "party", "add", "--name", "Nope").code)
        assertEquals(8, run("--yes", "--user", "Asha", "db", "migrate").code) // owner only

        // Wrong PINs back off like the app (shared counter): 3 free tries, then a wait.
        pinEnv = "0000"
        repeat(3) { assertEquals(8, run("--user", "Asha", "party", "list").code) }
        pinEnv = "9999"
        assertTrue("wait" in run("--user", "Asha", "party", "list").err)
        lock[Pins.KEY_RETRY_AT] = "0"

        pinEnv = "1234"
        ok("--user", "Owner", "report", "sales-register")
        ok("--yes", "--user", "Owner", "users", "deactivate", "Ravi")
        ok("--user", "Owner", "users", "role", "Asha", "manager")
        assertTrue(ok("--json", "--user", "Owner", "users", "list").out.contains("\"role\": \"manager\""))
        pinEnv = "5678"
        assertEquals(8, run("--user", "Ravi", "party", "list").code) // deactivated
        pinEnv = null
        assertEquals(8, run("--user", "Asha", "party", "list").code) // no PIN off a terminal
    }

    @Test
    fun invoiceDocumentFields() {
        seed()
        fun ok(vararg a: String) = run(*a).also { assertEquals(0, it.code, it.err) }
        // Reverse charge: tax is computed but the party owes only the taxable value.
        ok("party", "add", "--name", "Kiran Traders", "--gstin", "27AAAPL1234C1ZE")
        val rc = ok("--json", "invoice", "create", "--party", "Kiran Traders", "--date", "2026-10-01", "--line", "BOLT:10+2", "--reverse-charge", "--post").out
        assertTrue("\"reverseCharge\": true" in rc && "\"tax\": \"22.50\"" in rc && "\"total\": \"125.00\"" in rc && "\"freeQuantity\": \"2\"" in rc, rc)
        // Export under LUT in dollars: zero-rated, place of supply 96, rupee amounts stored.
        val ex = ok(
            "--json", "invoice", "create", "--party", "Acme Stores", "--date", "2026-10-02", "--line", "SVC:1@100.00",
            "--export", "wopay", "--currency", "usd", "--exchange-rate", "83.50", "--port", "INBOM1",
            "--transporter", "Fast Roadways", "--transport-mode", "road", "--vehicle", "MH12 AB 1234", "--po-number", "PO-7", "--field", "Order ref=WEB-12",
        ).out
        assertTrue("\"placeOfSupply\": \"96\"" in ex && "\"tax\": \"0.00\"" in ex && "\"total\": \"8350.00\"" in ex && "\"currency\": \"USD\"" in ex && "\"foreignRate\": \"100.00\"" in ex, ex)
        assertTrue("\"Vehicle no.\": \"MH12AB1234\"" in ex && "\"Order ref\": \"WEB-12\"" in ex && "\"PO no.\": \"PO-7\"" in ex, ex)
        val id = Regex("\"id\": \"([^\"]+)\"").find(ex)!!.groupValues[1]
        assertTrue(ok("invoice", "show", id).out.contains("USD 100.00"))
        assertEquals(3, run("invoice", "create", "--party", "Acme Stores", "--line", "BOLT:1", "--date", "2026-10-02", "--eway-bill", "123").code) // 12 digits
        assertEquals(2, run("invoice", "create", "--party", "Acme Stores", "--line", "BOLT:1", "--date", "2026-10-02", "--currency", "USD").code) // needs --export
        assertEquals(3, run("invoice", "create", "--party", "Bharat Supplies", "--line", "BOLT:1", "--date", "2026-10-02", "--reverse-charge", "--post").code) // unregistered
    }

    @Test
    fun ordersConvertInPartAndShortClose() {
        seed()
        fun ok(vararg a: String) = run(*a).also { assertEquals(0, it.code, it.err) }
        val so = ok("-q", "order", "create", "--party", "Acme Stores", "--date", "2026-10-01", "--line", "BOLT:10", "--line", "SVC:2@1000.00", "--post").out.trim()
        assertEquals(5, run("order", "post", so).code)
        // Part delivery: 4 bolts now, no service yet.
        val inv = ok("-q", "order", "convert", so, "--qty", "1=4", "--qty", "2=0", "--date", "2026-10-05", "--post").out.trim()
        assertTrue(ok("invoice", "show", inv).out.contains("Steel Bolt"))
        val shown = ok("--json", "order", "show", so).out
        assertTrue("\"orderStatus\": \"partial\"" in shown && "\"delivered\": \"4\"" in shown && "\"pending\": \"6\"" in shown, shown)
        assertEquals(3, run("order", "convert", so, "--qty", "1=7").code) // only 6 pending
        // The rest goes to a draft; a second convert finds nothing left (the draft reserves it).
        ok("order", "convert", so, "--date", "2026-10-06")
        assertEquals(5, run("order", "convert", so).code)
        assertEquals(2, run("order", "short-close", so).code) // needs --yes
        ok("--yes", "order", "short-close", so, "--note", "Customer cancelled the rest")
        assertTrue(ok("--json", "order", "show", so).out.contains("\"orderStatus\": \"closed\""))
        assertEquals(5, run("--yes", "order", "short-close", so).code)
        // Purchase order to a bill that quotes it.
        val po = ok("-q", "order", "create", "--type", "purchase", "--party", "Bharat Supplies", "--date", "2026-10-01", "--line", "BOLT:5@8.00", "--post").out.trim()
        val bill = ok("--json", "order", "convert", po, "--date", "2026-10-02").out
        assertTrue("\"type\": \"purchase\"" in bill && "\"PO no.\": \"$po\"" in bill, bill)
        assertTrue(ok("order", "list", "--type", "purchase").out.contains(po))
        ok("--yes", "order", "cancel", po, "--reason", "Supplier out of stock")
    }

    @Test
    fun recurringRunIsIdempotentAndHeldBills() {
        seed()
        fun ok(vararg a: String) = run(*a).also { assertEquals(0, it.code, it.err) }
        val template = ok("invoice", "create", "--party", "Acme Stores", "--date", "2026-10-01", "--line", "SVC:1@500.00").out.trim()
        val held = ok("invoice", "create", "--party", "Acme Stores", "--date", "2026-10-01", "--line", "BOLT:1").out.trim()
        val db = openBillDatabase(File(dataDir, "openbill.db").absolutePath)
        val at = Instant.fromEpochMilliseconds(1_780_000_000_000L)
        runBlocking {
            val r = db.roomRepositories()
            r.recurring.saveSchedule(RecurringSchedule("R1", "F1", template, "Monthly service", RecurrenceFrequency.MONTHLY, startDate = LocalDate(2026, 10, 1), autoPost = true, createdAt = at, updatedAt = at))
            r.vouchers.saveDraft(r.vouchers.findVoucher(held)!!.copy(heldAt = at))
        }
        db.close()
        assertTrue(ok("recurring", "list").out.contains("Monthly service"))
        val first = ok("--json", "recurring", "run", "--today", "2026-12-15").out
        assertTrue("\"made\": 3" in first, first)
        assertTrue("\"made\": 0" in ok("--json", "recurring", "run", "--today", "2026-12-15").out)
        assertEquals(3, ok("-q", "invoice", "list", "--status", "posted").out.lines().count { it.isNotBlank() })
        ok("recurring", "pause", "Monthly service")
        assertEquals(5, run("recurring", "pause", "R1").code)
        assertTrue("\"made\": 0" in ok("--json", "recurring", "run", "--today", "2027-02-15").out)
        ok("recurring", "resume", "R1") // as of the fixed clock: no back-billing of paused months
        assertEquals(2, run("recurring", "delete", "R1").code) // needs --yes
        ok("--yes", "recurring", "delete", "R1")
        assertEquals(4, run("recurring", "show", "R1").code)

        assertTrue(ok("-q", "pos", "held", "list").out.contains(held))
        ok("--yes", "pos", "held", "discard", held)
        assertTrue(ok("-q", "pos", "held", "list").out.isBlank())
        assertTrue(ok("-q", "invoice", "recycle-bin").out.contains(held))
    }

    @Test
    fun paymentDiscountAndExchangeDifference() {
        seed()
        fun ok(vararg a: String) = run(*a).also { assertEquals(0, it.code, it.err) }
        val inv = ok("-q", "invoice", "create", "--party", "Acme Stores", "--date", "2026-10-01", "--line", "BOLT:10", "--post").out.trim() // 147.50
        val paid = ok("--json", "payment", "in", "--party", "Acme Stores", "--amount", "140.00", "--discount", "7.50", "--allocate", "$inv:140.00", "--date", "2026-10-02").out
        assertTrue("\"allocated\": \"147.50\"" in paid && "\"advance\": \"0.00\"" in paid && "\"discount\": \"7.50\"" in paid, paid)
        run("payment", "in", "--party", "Acme Stores", "--amount", "1.00", "--allocate", "$inv:1.00", "--date", "2026-10-02").let { assertTrue(it.code == 3 && "exceeds" in it.err, it.err) } // settled in full
        assertEquals(3, run("payment", "in", "--party", "Acme Stores", "--amount", "10.00", "--discount", "5.00", "--date", "2026-10-02").code) // nothing open to discount

        val usd = ok("-q", "invoice", "create", "--party", "Acme Stores", "--date", "2026-10-03", "--line", "SVC:1@100.00", "--export", "wopay", "--currency", "USD", "--exchange-rate", "83.50", "--post").out.trim()
        assertEquals(3, run("payment", "in", "--party", "Acme Stores", "--amount", "10000.00", "--settle-exchange", usd, "--date", "2026-10-04").code) // > 10% off
        assertEquals(2, run("payment", "in", "--party", "Acme Stores", "--amount", "8400.00", "--settle-exchange", usd, "--auto").code)
        val fx = ok("--json", "payment", "in", "--party", "Acme Stores", "--amount", "8400.00", "--settle-exchange", usd, "--date", "2026-10-04").out
        assertTrue("\"exchange\": \"gain\"" in fx && "\"exchangeDifference\": \"-50.00\"" in fx && "\"allocated\": \"8350.00\"" in fx, fx)
        assertEquals(0, run("db", "check").code)
    }

    @Test
    fun partyTermsSpecialRatesAndItemDetails() {
        seed()
        fun ok(vararg a: String) = run(*a).also { assertEquals(0, it.code, it.err) }
        val p = ok("--json", "party", "update", "Acme Stores", "--credit-limit", "5000", "--credit-days", "30", "--group", "Retail", "--currency", "usd").out
        assertTrue("\"creditLimit\": \"5000.00\"" in p && "\"creditDays\": 30" in p && "\"group\": \"Retail\"" in p && "\"defaultCurrency\": \"USD\"" in p, p)
        assertEquals(3, run("party", "update", "Acme Stores", "--credit-days", "400").code)
        assertTrue("creditDays" !in ok("--json", "party", "update", "Acme Stores", "--credit-days", "none").out)

        ok("party", "price", "set", "Acme Stores", "BOLT", "10.00")
        assertTrue("10.00" in ok("party", "price", "list", "Acme Stores").out)
        val inv = ok("--json", "invoice", "create", "--party", "Acme Stores", "--date", "2026-10-01", "--line", "BOLT:10").out
        assertTrue("\"total\": \"118.00\"" in inv, inv) // special rate, not the list price
        ok("party", "price", "remove", "Acme Stores", "BOLT")
        assertEquals(4, run("party", "price", "remove", "Acme Stores", "BOLT").code)

        val i = ok("--json", "item", "update", "BOLT", "--low-stock", "5", "--reorder-qty", "20", "--category", "Hardware").out
        assertTrue("\"category\": \"Hardware\"" in i && "\"lowStockLevel\": \"5\"" in i && "\"reorderQuantity\": \"20\"" in i, i)
        assertTrue("Hardware" in ok("item", "category", "list").out)
        assertEquals(5, run("item", "category", "add", "hardware").code)
        assertEquals(3, run("item", "update", "BOLT", "--reorder-qty", "0").code)
        assertEquals(3, run("item", "update", "BOLT", "--field", "Colour=Red").code) // no custom fields set up
        assertEquals(0, run("db", "check").code)
    }

    @Test
    fun attachmentExportAndEveryReportListed() {
        seed()
        val zip = File(root, "att.zip")
        assertEquals(4, run("export", "attachments", "--out", zip.path).code) // none yet
        val id = run("-q", "party", "add", "--name", "Zed Traders").out.trim()
        File(dataDir, "attachments/$id").apply { mkdirs() }.resolve("gst.png").writeBytes(byteArrayOf(1, 2, 3))
        val r = run("--json", "export", "attachments", "--out", zip.path)
        assertTrue(r.code == 0 && "\"files\": 1" in r.out, r.err + r.out)
        val names = java.util.zip.ZipFile(zip).use { z -> z.entries().toList().map { it.name } }
        assertEquals(listOf("Party_${id}/gst.png", "index.csv"), names) // the app's folder names
        assertEquals(listOf("att.zip"), root.list()!!.filter { it.startsWith("att.zip") }) // no temp file left
        val list = run("--json", "report", "list").out
        app.openbill.core.domain.report.ReportKind.entries.forEach { assertTrue("\"${it.key}\"" in list, it.key) }
    }

    @Test
    fun exitCodes() {
        seed()
        assertEquals(2, run("party", "add").code) // missing --name
        assertEquals(3, run("party", "add", "--name", "X", "--gstin", "BADGSTIN").code)
        assertEquals(4, run("party", "show", "Nobody").code)
        assertEquals(4, run("--data-dir", File(root, "empty").path, "party", "list").code)
        assertEquals(0, run("db", "check").code)
        assertEquals(0, run("version").code)
    }

    @Test
    fun lockRefusesWritesButAllowsReads() {
        seed()
        val held = InstanceLock.tryAcquire(dataDir)!!
        try {
            assertEquals(6, run("party", "add", "--name", "Locked Out").code)
            val list = run("party", "list")
            assertEquals(0, list.code, list.err)
            assertTrue("Acme Stores" in list.out)
            assertEquals(6, run("--yes", "db", "migrate").code)
        } finally {
            held.release()
        }
        assertEquals(0, run("party", "add", "--name", "Now Fine").code)
    }

    @Test
    fun importDryRunAndAllOrNothing() {
        seed()
        val csv = File(root, "parties.csv").apply {
            writeText("name,type,phone,gstin\r\n\"Gupta, Sons\",customer,99,\r\nBad Co,dealer,,\r\nNo Gst,both,,27XX\r\n")
        }
        assertGolden("import-parties-errors", run("import", "parties", csv.path, "--dry-run"))
        csv.writeText("name,type\nNew One,customer\n\"Gupta, Sons\",both\n")
        assertEquals(0, run("import", "parties", csv.path, "--dry-run").code)
        assertEquals(2, run("-q", "party", "list").out.lines().count { it.isNotBlank() })
        assertEquals(0, run("import", "parties", csv.path).code)
        assertEquals(4, run("-q", "party", "list").out.lines().count { it.isNotBlank() })
        val items = File(root, "items.csv").apply { writeText("name,sku,unit,gst_rate,sale_price\nNut,NUT,pcs,18,2.00\nWasher,WSH,box,5,1.00\n") }
        assertEquals(3, run("import", "items", items.path).code) // unknown unit 'box'
        assertEquals(2, run("-q", "item", "list").out.lines().count { it.isNotBlank() })

        // Opening stock columns, shared with the app's import: one posted "Opening stock" adjustment.
        items.writeText("name,sku,unit,gst_rate,sale_price,opening_qty,opening_cost,godown,batch,expiry\nNut,NUT,pcs,18,2.00,40,1.50,Main,B1,2027-01-31\nWasher,WSH,pcs,5,1.00,7,,,,\nPin,PIN,pcs,5,1.00,,,,,\n")
        assertEquals(0, run("import", "items", items.path, "--dry-run").code)
        val imported = run("import", "items", items.path)
        assertEquals(0, imported.code, imported.err)
        assertTrue("2 with opening stock" in imported.out, imported.out)
        assertTrue(run("stock", "ledger", "NUT").out.contains("40"))
        assertTrue(run("stock", "ledger", "WSH").out.contains("7"))
        items.writeText("name,sku,unit,gst_rate,sale_price,opening_qty,godown\nBolt2,B2,pcs,18,2.00,5,Nowhere\n")
        assertEquals(3, run("import", "items", items.path).code) // unknown godown
    }

    @Test
    fun backupRoundTripAndReadOnlyMode() {
        seed()
        val out = File(root, "backups")
        val created = run("-q", "backup", "create", "--out", out.path)
        assertEquals(0, created.code, created.err)
        val archive = File(created.out.trim())
        assertTrue(archive.isFile && archive.name.startsWith("backup-sharma-traders-"))
        assertEquals(0, run("backup", "verify", archive.path).code)

        // Read-only access to the archive; writes rejected.
        assertEquals(run("party", "list").out, run("--backup", archive.path, "party", "list").out)
        assertEquals(8, run("--backup", archive.path, "party", "add", "--name", "X").code)

        val other = File(root, "other")
        seed(other, firmName = "Other Firm")
        assertEquals(2, run("backup", "restore", archive.path, dir = other).code) // no --yes off a terminal
        val restored = run("--yes", "backup", "restore", archive.path, dir = other)
        assertEquals(0, restored.code, restored.err)
        assertTrue("Sharma Traders" in run("firm", "list", dir = other).out)
        assertTrue(other.listFiles()!!.any { it.name.startsWith("restore-safety-") })
    }

    @Test
    fun dbInfoAndBackupInspect() {
        seed()
        val info = run("--json", "db", "info")
        assertEquals(0, info.code, info.err)
        assertTrue("\"schemaVersion\": ${app.openbill.core.database.OPENBILL_DATABASE_VERSION}" in info.out && "\"firms\": 1" in info.out && "\"parties\": 2" in info.out, info.out)
        assertTrue(run("db", "info").out.contains("Firms"))
        assertEquals(4, run("--data-dir", File(root, "empty").path, "db", "info").code)

        val plain = File(root, "p.openbill.zip")
        assertEquals(0, run("backup", "create", "--out", plain.path).code)
        val inspected = run("--json", "backup", "inspect", plain.path)
        assertEquals(0, inspected.code, inspected.err)
        assertTrue("\"encrypted\": false" in inspected.out && "\"parties\": 2" in inspected.out && "\"attachments\": 0" in inspected.out, inspected.out)
        assertTrue("\"parties\": 2" in run("--json", "--backup", plain.path, "db", "info").out)

        password = "s3cret".toCharArray()
        val enc = File(root, "e.openbill.zip")
        assertEquals(0, run("backup", "create", "--out", enc.path).code)
        assertTrue("\"encrypted\": true" in run("--json", "backup", "inspect", enc.path).out)
        password = null
        assertEquals(8, run("backup", "inspect", enc.path).code) // encrypted, no password, no terminal
        password = "wrong".toCharArray()
        assertEquals(7, run("backup", "inspect", enc.path).code)
    }

    @Test
    fun failedRestoreLeavesDatabaseUntouched() {
        seed()
        val archive = File(root, "a.openbill.zip")
        assertEquals(0, run("backup", "create", "--out", archive.path).code)
        val bad = File(root, "bad.openbill.zip")
        ZipOutputStream(bad.outputStream()).use { zout ->
            ZipInputStream(archive.inputStream()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    val bytes = zin.readBytes()
                    zout.putNextEntry(ZipEntry(e.name))
                    zout.write(if (e.name == "database.sqlite") bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() } else bytes)
                    zout.closeEntry()
                }
            }
        }
        assertEquals(7, run("backup", "verify", bad.path).code)
        val db = File(dataDir, "openbill.db")
        val before = db.readBytes()
        assertEquals(7, run("--yes", "backup", "restore", bad.path).code)
        assertContentEquals(before, db.readBytes())
        assertTrue(dataDir.listFiles()!!.none { it.name.startsWith("restore-safety-") || it.name.startsWith(".restore-") })
    }

    @Test
    fun reportsGstr1AndTallyGoldens() {
        seed()
        val db = openBillDatabase(File(dataDir, "openbill.db").absolutePath)
        val at = Instant.fromEpochMilliseconds(1_780_000_000_000L)
        runBlocking {
            val r = db.roomRepositories()
            r.parties.saveParty(Party(id = "P3", firmId = "F1", name = "Kiran Traders", type = PartyType.CUSTOMER, gstin = "27AAAPL1234C1ZE", stateCode = "27", createdAt = at, updatedAt = at))
            r.parties.saveParty(Party(id = "P4", firmId = "F1", name = "Ravi Kumar", type = PartyType.CUSTOMER, stateCode = "29", createdAt = at, updatedAt = at))
        }
        db.close()
        fun ok(vararg a: String) = run(*a).also { assertEquals(0, it.code, it.err) }
        ok("invoice", "create", "--party", "Kiran Traders", "--date", "2026-09-05", "--line", "BOLT:10", "--post")
        ok("invoice", "create", "--party", "Ravi Kumar", "--date", "2026-09-06", "--place-of-supply", "29", "--line", "SVC:1@150000.00", "--post")
        ok("invoice", "create", "--party", "Acme Stores", "--date", "2026-09-07", "--line", "SVC:1@500.00", "--post")
        val sep = arrayOf("--from", "2026-09-01", "--to", "2026-09-30")
        assertGolden("gst-gstr1-json", run("gst", "gstr1", "--period", "2026-09"))
        assertGolden("report-sales-register", run("report", "sales-register", *sep))
        assertGolden("report-gstr3b-csv", run("report", "run", "gstr3b", "--format", "csv", *sep))
        assertGolden("report-profit-loss-json", run("--json", "report", "profit-loss", *sep))
        assertGolden("tally-export", run("export", "tally", *sep))
        assertEquals(run("export", "tally", *sep).out, run("tally", "export", *sep).out)
        assertEquals(2, run("report", "party-statement", *sep).code) // needs --party
        assertEquals(0, run("report", "party-statement", "--party", "Kiran Traders", *sep).code)
        assertTrue(run("audit", "list").out.contains("voucher.posted"))
        val out = File(root, "gstr1.json")
        assertEquals(0, run("gst", "gstr1", "--period", "2026-09", "--out", out.path).code)
        assertTrue(out.readText().startsWith("{"))
        // CA pack: its GSTR-3B CSV is the `report gstr3b` CSV.
        val zip = File(root, "ca.zip")
        ok("report", "ca-pack", *sep, "--out", zip.path)
        val entries = java.util.zip.ZipFile(zip).use { z -> z.entries().toList().associate { it.name to z.getInputStream(it).readBytes().decodeToString() } }
        // (the test harness normalises stdout line ends to LF)
        assertEquals(run("report", "run", "gstr3b", "--format", "csv", *sep).out, entries.getValue("gstr3b-2026-09-01-2026-09-30.csv").replace("\r\n", "\n"))
        assertTrue("Tally-2026-09-01-2026-09-30.xml" in entries && "README.txt" in entries)
        assertEquals(2, run("report", "ca-pack", *sep).code) // needs --out
    }

    @Test
    fun pdfPaymentStockAndExportCsv() {
        seed()
        fun ok(vararg a: String) = run(*a).also { assertEquals(0, it.code, it.err) }
        val number = ok("-q", "invoice", "create", "--party", "Acme Stores", "--date", "2026-10-01", "--line", "BOLT:10", "--post").out.trim()
        val pdf = File(root, "inv.pdf")
        ok("invoice", "pdf", number, "--template", "receipt-80", "--out", pdf.path)
        assertTrue(pdf.readBytes().copyOfRange(0, 5).decodeToString() == "%PDF-")
        assertEquals(2, run("invoice", "pdf", number, "--template", "nope").code)
        // 10 x 12.50 + 18% GST = 147.50: allocate 100, the rest of 120 stays on account.
        val pay = ok("--json", "payment", "in", "--party", "Acme Stores", "--amount", "120.00", "--date", "2026-10-02", "--allocate", "$number:100.00").out
        assertTrue(pay.contains("\"allocated\": \"100.00\"") && pay.contains("\"advance\": \"20.00\""), pay)
        assertEquals(3, run("payment", "in", "--party", "Acme Stores", "--amount", "100.00", "--date", "2026-10-02", "--allocate", "$number:60.00").code) // over-allocation
        ok("payment", "in", "--party", "Acme Stores", "--amount", "47.50", "--date", "2026-10-03", "--auto")
        assertEquals(2, ok("payment", "list").out.lines().count { it.contains("Acme Stores") })
        ok("stock", "adjust", "BOLT", "--qty", "+50", "--reason", "Opening", "--date", "2026-10-01", "--unit-cost", "5.00")
        assertEquals(2, run("stock", "adjust", "BOLT", "--qty", "-5").code) // needs --reason
        ok("stock", "adjust", "BOLT", "--qty", "-5", "--reason", "Damaged", "--date", "2026-10-04")
        assertTrue(ok("stock", "ledger", "BOLT").out.lines().last { it.isNotBlank() }.trim().endsWith("35"))
        assertTrue(ok("stock", "summary", "--format", "csv").out.contains("Steel Bolt"))
        assertEquals(ok("export", "parties", "--format", "csv").out, ok("export", "csv", "parties").out)
    }
}
