package app.openbill.core.domain.csv

import app.openbill.core.domain.model.VoucherType
import app.openbill.core.domain.report.ZipWriter
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AttachmentExportTest {
    private fun export(owners: List<AttachmentOwner>, files: Map<String, List<Pair<String, ByteArray>>>): List<Pair<String, ByteArray>> = runBlocking {
        val out = mutableListOf<Pair<String, ByteArray>>()
        attachmentExport(owners, files.mapValues { (_, l) -> l.map { it.first } }, { id, name -> files[id]?.firstOrNull { it.first == name }?.second }) { p, b -> out += p to b }
        out
    }

    @Test
    fun foldersAndIndex() {
        val owners = listOf(
            AttachmentOwner("v1", attachmentKind(VoucherType.SALE), "INV/24-25/1", "2026-04-01", "posted", "Lotus, Pune"),
            AttachmentOwner("v2", "Quotation", "draft", "2026-04-02", "draft", null),
            AttachmentOwner("p1", "Party", null, null, null, "Lotus, Pune"),
        )
        val out = export(owners, mapOf("v1" to listOf("bill.pdf" to byteArrayOf(1, 2)), "p1" to listOf("gst.png" to byteArrayOf(3))))
        // Same folder names from the app and the CLI; no character Windows rejects (\ / : * ? " < > |).
        assertEquals(listOf("Sales invoice_INV_24-25_1_v1/bill.pdf", "Party_p1/gst.png", "index.csv"), out.map { it.first })
        val index = out.last().second.decodeToString().trim().lines()
        assertEquals("file,record,number,date,status,party,bytes", index[0])
        assertEquals("Sales invoice_INV_24-25_1_v1/bill.pdf,Sales invoice,INV/24-25/1,2026-04-01,posted,\"Lotus, Pune\",2", index[1])
        assertEquals(3, index.size)
        val odd = AttachmentOwner("x", "Quotation", "Q:1*?.", null, null, null)
        assertEquals("Quotation_Q_1___x/a_b.pdf", export(listOf(odd), mapOf("x" to listOf("a<b.pdf" to byteArrayOf(1))))[0].first)
    }

    @Test
    fun streamedZipMatchesInMemoryAndRejectsDuplicates() {
        val files = listOf("a.txt" to "hello".encodeToByteArray(), "b/c.bin" to ByteArray(70_000) { it.toByte() })
        val streamed = mutableListOf<Byte>()
        ZipWriter.Stream { streamed += it.toList() }.apply { files.forEach { add(it.first, it.second) } }.finish()
        assertContentEquals(ZipWriter.write(files), streamed.toByteArray())
        assertFailsWith<IllegalArgumentException> { ZipWriter.Stream {}.apply { add("a", byteArrayOf()); add("a", byteArrayOf()) } }
    }
}
