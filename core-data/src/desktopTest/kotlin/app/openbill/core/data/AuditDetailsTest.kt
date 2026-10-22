package app.openbill.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuditDetailsTest {
    @Test
    fun escapesStringsKeysAndRendersScalars(): kotlin.Unit {
        val json = AuditDetails.json(
            "quote\"key" to "line1\nline2\t\"quoted\" \\ slash\u0001",
            "count" to 42L,
            "flag" to true,
            "missing" to null,
        )

        assertEquals(
            "{\"quote\\\"key\":\"line1\\nline2\\t\\\"quoted\\\" \\\\ slash\\u0001\"," +
                "\"count\":42,\"flag\":true,\"missing\":null}",
            json,
        )
    }

    @Test
    fun emptyObjectIsValidJsonObject(): kotlin.Unit {
        assertEquals("{}", AuditDetails.json())
    }

    @Test
    fun restoreAuditDetailsContainBothReasons(): kotlin.Unit {
        val json = AuditDetails.json(
            "cancellationReason" to "Customer request",
            "restoreReason" to "Approved",
            "restoredAt" to 1_700_000_500_000L,
        )

        assertTrue(json.startsWith("{"))
        assertTrue(json.endsWith("}"))
        assertTrue(json.contains("\"cancellationReason\":\"Customer request\""))
        assertTrue(json.contains("\"restoreReason\":\"Approved\""))
        assertTrue(json.contains("\"restoredAt\":1700000500000"))
    }
}
