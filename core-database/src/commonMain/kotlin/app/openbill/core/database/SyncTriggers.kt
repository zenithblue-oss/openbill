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

    /**
     * Never synced: rows that describe this device, not the business. Number sequences are
     * per device (section 6.5): each device numbers in its own series, so a counter is never
     * shared and two offline devices cannot issue the same number.
     */
    val LOCAL_TABLES = setOf(
        "sync_state", "sync_outbox", "sync_inbox", "room_master_table", "android_metadata", "voucher_number_sequences",
    )

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

    /**
     * Columns kept out of ops: device secrets (a 4-digit PIN hash is brute-forced in seconds) and
     * derived caches each replica recomputes after applying ops (an order's fulfilment).
     */
    val privateColumns = mapOf("users" to setOf("pinHash"), "vouchers" to setOf("fulfilment"))

    /**
     * Rows that stay on the device (section 18): drafts, held bills and their lines, charges and
     * payment details, and audit events about them. Posting touches the children
     * (`VoucherDao.touchPosted`), so the post op carries the whole document.
     */
    private val draft = { v: String -> "EXISTS (SELECT 1 FROM vouchers d WHERE d.id = $v AND d.status = 'draft')" }
    private val localRow: Map<String, (String) -> String> = mapOf(
        "vouchers" to { r -> "$r.status = 'draft'" },
        "voucher_lines" to { r -> draft("$r.voucherId") },
        "voucher_charges" to { r -> draft("$r.voucherId") },
        "voucher_charge_portions" to { r -> draft("$r.voucherId") },
        "payment_details" to { r -> draft("$r.voucherId") },
        // A draft's own history stays with it, even after it is posted; other events about a draft too.
        "audit_events" to { r ->
            "$r.action IN ('voucher.draft_created', 'voucher.draft_deleted', 'voucher.draft_restored', 'voucher.purged') OR " +
                "($r.voucherId IS NOT NULL AND ${draft("$r.voucherId")})"
        },
    )

    /** SQL condition, true for a device-local row of [table] ([r] names the row). */
    fun localCondition(table: String, r: String): String? = localRow[table]?.invoke(r)

    /** Updates that change no synced column (an order's fulfilment cache) record nothing. */
    private val skipUnchanged = setOf("vouchers")

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

    /** Columns of [table]: names in table order, and the primary key in key order. */
    class Shape(val columns: List<String>, val keys: List<String>) {
        /** Columns that travel in ops, in name order. */
        fun shared(table: String): List<String> = columns.filter { it !in privateColumns[table].orEmpty() }.sorted()
    }

    fun shape(connection: SQLiteConnection, table: String): Shape {
        val rows = connection.prepare("PRAGMA table_info(`$table`)").use { s ->
            buildList { while (s.step()) add(s.getText(1) to s.getLong(5).toInt()) }
        }
        return Shape(rows.map { it.first }, rows.filter { it.second > 0 }.sortedBy { it.second }.map { it.first })
    }

    private class Column(val name: String, val pk: Int)

    private fun columns(connection: SQLiteConnection, table: String): List<Column> =
        shape(connection, table).let { s -> s.columns.map { c -> Column(c, s.keys.indexOf(c) + 1) } }

    /** SQL building a JSON object of [cols] of row [r] (json_object takes at most ~60 pairs per call). */
    fun json(r: String, cols: List<String>): String = cols.chunked(60).let { chunks ->
        chunks.drop(1).fold("json_object(${chunks[0].joinToString { "'$it', $r.`$it`" }})") { acc, more ->
            "json_insert($acc, ${more.joinToString { "'$.$it', $r.`$it`" }})"
        }
    }

    private fun statements(table: String, columns: List<Column>): List<String> {
        val names = columns.map { it.name }
        val firm = firmOf[table] ?: if ("firmId" in names) { r -> "$r.firmId" } else error("Synced table $table has no firm: add it to SyncTriggers")
        val keys = columns.filter { it.pk > 0 }.sortedBy { it.pk }.map { it.name }.ifEmpty { error("Synced table $table has no primary key") }
        val shared = columns.filter { it.name !in privateColumns[table].orEmpty() }.map { it.name }.sorted()
        fun entityId(r: String) = keys.joinToString(" || '|' || ") { "$r.`$it`" }
        val revision = "revision" in names
        val guarded = table in firmOf && table != "firms" && table != "users"
        return listOf(
            Triple("INSERT", "NEW", "upsert"),
            Triple("UPDATE", "NEW", "upsert"),
            Triple("DELETE", "OLD", "delete"),
        ).map { (event, r, kind) ->
            val payload = if (kind == "upsert") json(r, shared) else json(r, keys)
            val base = if (revision && event != "INSERT") "OLD.revision" else "NULL"
            val conditions = buildList {
                add("(SELECT enabled FROM sync_state WHERE id = 1) = 1")
                if (guarded) add("${firm(r)} IS NOT NULL")
                localRow[table]?.let { add("NOT (${it(r)})") }
                if (event == "UPDATE" && table in skipUnchanged) add("NOT (${shared.joinToString(" AND ") { "OLD.`$it` IS NEW.`$it`" }})")
            }
            """
            CREATE TRIGGER `$PREFIX${table}_${event.lowercase()}` AFTER $event ON `$table`
            WHEN ${conditions.joinToString(" AND ")}
            BEGIN
                INSERT INTO sync_outbox (opId, firmId, deviceId, userId, hlc, groupId, entity, entityId, kind, action, baseRevision, payload, schemaVersion)
                SELECT lower(hex(randomblob(16))), ${firm(r)}, deviceId, txUser, hlc, txId, '$table', ${entityId(r)}, '$kind', COALESCE(txAction, 'unattributed'), $base, $payload, $OPENBILL_DATABASE_VERSION
                FROM sync_state WHERE id = 1;
            END
            """.trimIndent()
        }
    }
}
