package app.openbill.core.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

object AuditTriggers {
    fun install(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS audit_events_no_update
            BEFORE UPDATE ON audit_events
            BEGIN
                SELECT RAISE(ABORT, 'audit_events is append-only');
            END;
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS audit_events_no_delete
            BEFORE DELETE ON audit_events
            BEGIN
                SELECT RAISE(ABORT, 'audit_events is append-only');
            END;
            """.trimIndent(),
        )
    }
}
