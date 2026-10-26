package app.openbill.core.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

object AuditTriggers {
    /** Also re-run by firm erasure, which drops the delete trigger inside its transaction. */
    val statements = listOf(
        """
        CREATE TRIGGER IF NOT EXISTS audit_events_no_update
        BEFORE UPDATE ON audit_events
        BEGIN
            SELECT RAISE(ABORT, 'audit_events is append-only');
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS audit_events_no_delete
        BEFORE DELETE ON audit_events
        BEGIN
            SELECT RAISE(ABORT, 'audit_events is append-only');
        END;
        """.trimIndent(),
    )

    fun install(connection: SQLiteConnection) = statements.forEach(connection::execSQL)
}
