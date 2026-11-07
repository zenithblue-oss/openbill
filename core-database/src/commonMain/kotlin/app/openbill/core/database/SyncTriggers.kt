package app.openbill.core.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Records every change to a synced table as an operation in `sync_outbox`, inside the same
 * transaction (docs/10-server.md section 6.2). One trigger per table and change, generated from
 * the live columns on every open, so no write path can skip the outbox and new columns are picked
 * up without code. Off unless `sync_state.enabled`.
 */
object SyncTriggers {
    private const val PREFIX = "sync_op_"

    /** Rows that describe this device, not the business: never synced. */
    val LOCAL_TABLES = setOf("sync_state", "sync_outbox", "room_master_table", "android_metadata")

    /**
     * Firm of a row ([r] is NEW or OLD) for tables without a `firmId` column. A child of a parent
     * that is already gone (an FK cascade) resolves to null and records nothing: the parent's
     * delete op cascades the same way on every replica.
     */
    private val firmOf: Map<String, (String) -> String> = mapOf(
        "firms" to { r -> "$r.id" },
        "users" to { _ -> "NULL" },
        "party_addresses" to { r -> "(SELECT firmId FROM parties WHERE id = $r.partyId)" },
        "batches" to { r -> "(SELECT firmId FROM items WHERE id = $r.itemId)" },
        "serial_numbers" to { r -> "(SELECT firmId FROM items WHERE id = $r.itemId)" },
        "voucher_lines" to { r -> "(SELECT firmId FROM vouchers WHERE id = $r.voucherId)" },
        "voucher_charges" to { r -> "(SELECT firmId FROM vouchers WHERE id = $r.voucherId)" },
        "voucher_charge_portions" to { r ->
            "(SELECT v.firmId FROM voucher_charges c JOIN vouchers v ON v.id = c.voucherId WHERE c.id = $r.chargeId)"
        },
    )

    /** Device secrets kept out of ops: a 4-digit PIN hash is brute-forced in seconds. */
    private val privateColumns = mapOf("users" to setOf("pinHash"))

    fun install(connection: SQLiteConnection) {
        drop(connection)
        tables(connection).forEach { table -> statements(table, columns(connection, table)).forEach(connection::execSQL) }
    }

    /** Run before every migration: a trigger naming a dropped or renamed column would fail the migration. */
    fun drop(connection: SQLiteConnection) {
        val names = connection.prepare("SELECT name FROM sqlite_master WHERE type = 'trigger' AND name LIKE '$PREFIX%'").use { s ->
            buildList { while (s.step()) add(s.getText(0)) }
        }
        names.forEach { connection.execSQL("DROP TRIGGER IF EXISTS `$it`") }
    }

    /** Every table in the file except [LOCAL_TABLES]; a new table syncs unless listed there. */
    fun tables(connection: SQLiteConnection): List<String> =
        connection.prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name").use { s ->
            buildList { while (s.step()) add(s.getText(0)) }
        }.filter { it !in LOCAL_TABLES }

    private class Column(val name: String, val pk: Int)

    private fun columns(connection: SQLiteConnection, table: String): List<Column> =
        connection.prepare("PRAGMA table_info(`$table`)").use { s ->
            buildList { while (s.step()) add(Column(s.getText(1), s.getLong(5).toInt())) }
        }

    private fun statements(table: String, columns: List<Column>): List<String> {
        val names = columns.map { it.name }
        val firm = firmOf[table] ?: if ("firmId" in names) { r -> "$r.firmId" } else error("Synced table $table has no firm: add it to SyncTriggers")
        val keys = columns.filter { it.pk > 0 }.sortedBy { it.pk }.map { it.name }.ifEmpty { error("Synced table $table has no primary key") }
        val shared = columns.filter { it.name !in privateColumns[table].orEmpty() }.map { it.name }.sorted()
        fun entityId(r: String) = keys.joinToString(" || '|' || ") { "$r.`$it`" }
        fun json(r: String, cols: List<String>) = cols.chunked(60).let { chunks ->
            chunks.drop(1).fold("json_object(${chunks[0].joinToString { "'$it', $r.`$it`" }})") { acc, more ->
                "json_insert($acc, ${more.joinToString { "'$.$it', $r.`$it`" }})"
            }
        }
        val revision = "revision" in names
        val guarded = table in firmOf && table != "firms" && table != "users"
        return listOf(
            Triple("INSERT", "NEW", "upsert"),
            Triple("UPDATE", "NEW", "upsert"),
            Triple("DELETE", "OLD", "delete"),
        ).map { (event, r, kind) ->
            val payload = if (kind == "upsert") json(r, shared) else json(r, keys)
            val base = if (revision && event != "INSERT") "OLD.revision" else "NULL"
            """
            CREATE TRIGGER `$PREFIX${table}_${event.lowercase()}` AFTER $event ON `$table`
            WHEN (SELECT enabled FROM sync_state WHERE id = 1) = 1${if (guarded) " AND ${firm(r)} IS NOT NULL" else ""}
            BEGIN
                INSERT INTO sync_outbox (opId, firmId, deviceId, userId, hlc, groupId, entity, entityId, kind, action, baseRevision, payload, schemaVersion)
                SELECT lower(hex(randomblob(16))), ${firm(r)}, deviceId, txUser, hlc, txId, '$table', ${entityId(r)}, '$kind', txAction, $base, $payload, $OPENBILL_DATABASE_VERSION
                FROM sync_state WHERE id = 1;
            END
            """.trimIndent()
        }
    }
}
