package app.openbill.ui

import app.openbill.core.domain.model.ExportType
import app.openbill.core.domain.model.VoucherType
import app.openbill.ui.invoice.DocFieldRules
import app.openbill.ui.invoice.DocFieldsForm
import app.openbill.ui.invoice.FilingPeriod
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentFieldsRulesTest {
    @Test
    fun validateFlagsMalformedFieldsAndRegistrationOnPostOnly() {
        val bad = DocFieldsForm(
            ecommerceGstin = "29AABCU9603R1ZM", transporterId = "ABC", vehicleNumber = "MH/12",
            distance = "10000", ewayBillNumber = "1234", exportType = ExportType.SEZ_UNDER_LUT, portCode = "INB",
            reverseCharge = true,
        )
        val draft = DocFieldRules.validate(bad, VoucherType.SALE, party = null, post = false)
        assertEquals(setOf("eco", "transporterId", "vehicle", "distance", "eway", "port"), draft.keys)
        val posted = DocFieldRules.validate(bad, VoucherType.SALE, party = null, post = true)
        assertTrue("reverseCharge" in posted && "export" in posted)

        val good = DocFieldsForm(
            ecommerceGstin = "29AABCU9603R1ZJ", transporterId = "27AAPFU0939F1ZV", vehicleNumber = "MH 12 AB 1234",
            distance = "850", ewayBillNumber = "1234 5678 9012", exportType = ExportType.EXPORT_UNDER_LUT, portCode = "INBOM1",
        )
        assertEquals(emptyMap(), DocFieldRules.validate(good, VoucherType.SALE, party = null, post = true))
    }

    @Test
    fun filingWarningOnlyAfterTheGstr1DueDateForOutwardDocuments() {
        val sep15 = LocalDate(2026, 9, 15)
        assertEquals(LocalDate(2026, 10, 11), FilingPeriod.gstr1DueDate(sep15))
        assertNull(FilingPeriod.warning(VoucherType.SALE, sep15, LocalDate(2026, 10, 11)))
        assertNotNull(FilingPeriod.warning(VoucherType.SALE, sep15, LocalDate(2026, 10, 12)))
        assertNotNull(FilingPeriod.warning(VoucherType.CREDIT_NOTE, sep15, LocalDate(2026, 11, 6)))
        assertNull(FilingPeriod.warning(VoucherType.PURCHASE, sep15, LocalDate(2026, 11, 6)))
        // December rolls into January of the next year.
        assertEquals(LocalDate(2027, 1, 11), FilingPeriod.gstr1DueDate(LocalDate(2026, 12, 31)))
    }
}
