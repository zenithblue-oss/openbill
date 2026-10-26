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
            writeText("name,type,phone,gstin\r\n\"Gupta, Sons\",customer,99,\r\nBad Co,vendor,,\r\nNo Gst,both,,27XX\r\n")
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
