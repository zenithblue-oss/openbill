package app.openbill.core.backup

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AttachmentStoreTest {
    @Test
    fun addListDeleteAndSanitizeNames() {
        val root = Files.createTempDirectory("att").toFile()
        val store = AttachmentStore(root)
        assertEquals("receipt.png", store.add("v1", "../../etc/receipt.png", "a".byteInputStream()))
        assertEquals("receipt-1.png", store.add("v1", "receipt.png", "b".byteInputStream()))
        assertEquals("file", AttachmentStore.safe(".."))
        assertEquals("a_b.pdf", AttachmentStore.safe("C:\\tmp\\a:b.pdf"))
        assertEquals(listOf("receipt-1.png", "receipt.png"), store.list("v1"))
        assertTrue(store.file("v1", "receipt.png").path.startsWith(root.path))
        store.delete("v1", "receipt.png")
        assertEquals(listOf("receipt-1.png"), store.list("v1"))
        root.deleteRecursively()
    }
}
