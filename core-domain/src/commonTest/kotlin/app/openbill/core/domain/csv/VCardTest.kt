package app.openbill.core.domain.csv

import kotlin.test.Test
import kotlin.test.assertEquals

class VCardTest {
    @Test
    fun readsNamesFirstPhoneAndEmailAcrossVersions() {
        val text = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Gupta\\, Sons\r\nTEL;TYPE=CELL:+91 98765 43210\r\nTEL:022-1234567\r\nEMAIL:a@b.in\r\nEND:VCARD\r\n" +
            "BEGIN:VCARD\nVERSION:2.1\nN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:Kum=C3=A1r;Ravi;;;\nitem1.TEL:9988\n 776655\nEND:VCARD\n" +
            "BEGIN:VCARD\nVERSION:4.0\nTEL:12345678\nEND:VCARD\n" // no name: skipped
        assertEquals(
            listOf(Contact("Gupta, Sons", "+919876543210", "a@b.in"), Contact("Ravi Kumár", "9988776655", null)),
            VCard.parse(text),
        )
    }

    @Test
    fun contactsBecomeCustomersAndDuplicatesAreSkipped() {
        val at = kotlin.time.Instant.fromEpochMilliseconds(0)
        var n = 0
        val existing = app.openbill.core.domain.model.Party(id = "P0", firmId = "F1", name = "Old", type = app.openbill.core.domain.model.PartyType.CUSTOMER, phone = "098765 43210", createdAt = at, updatedAt = at)
        val vcf = "BEGIN:VCARD\nFN:Same phone\nTEL:+91 9876543210\nEND:VCARD\nBEGIN:VCARD\nFN:New One\nTEL:91234 56789\nEND:VCARD\n" +
            "BEGIN:VCARD\nFN:new one\nTEL:9123456789\nEND:VCARD\nBEGIN:VCARD\nFN:old\nEND:VCARD\nBEGIN:VCARD\nFN:No Phone\nEND:VCARD\n"
        val r = MasterCsv.contacts(vcf, "F1", listOf(existing), { "N${n++}" }, at)
        assertEquals(listOf("New One", "No Phone"), r.valid.map { it.name })
        assertEquals(3, r.skipped)
        assertEquals(5, r.rows)
    }
}
