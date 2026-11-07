package app.openbill.core.sync

import androidx.room.Transactor
import app.openbill.core.database.AuditTriggers
import app.openbill.core.database.OPENBILL_DATABASE_VERSION
import app.openbill.core.database.SyncTriggers
import app.openbill.core.model.Ulid
import kotlin.random.Random
import kotlin.time.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Writes and reads synced rows as JSON inside the caller's write transaction: the one apply path
 * of a pulling device and of the server, so both reach the same rows from the same ops. Table and
 * column names come from the live schema, never from the op, so an op cannot inject SQL.
 */
class SyncRows(private val c: Transactor) {
    private var synced: Set<String>? = null
    private val shapes = HashMap<String, SyncTriggers.Shape>()

    suspend fun shape(table: String): SyncTriggers.Shape {
        val tables = synced ?: c.usePrepared("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'") { s ->
            buildSet { while (s.step()) add(s.getText(0)) } - SyncTriggers.LOCAL_TABLES
        }.also { synced = it }
        require(table in tables) { "Not a synced table: $table" }
        return shapes.getOrPut(table) {
            val rows = c.usePrepared("PRAGMA table_info(`$table`)") { s -> buildList { while (s.step()) add(s.getText(1) to s.getLong(5).toInt()) } }
            SyncTriggers.Shape(rows.map { it.first }, rows.filter { it.second > 0 }.sortedBy { it.second }.map { it.first })
        }
    }

    suspend fun apply(op: SyncOperation) = when (op.kind) {
        SyncOperation.Kind.UPSERT -> upsert(op.entity, op.payload)
        SyncOperation.Kind.DELETE -> delete(op.entity, op.payload)
    }

    /** Insert or update by primary key; only the payload's columns are written (private ones stay local). */
    suspend fun upsert(table: String, payload: JsonObject) {
        val shape = shape(table)
        val cols = shape.shared(table).filter { it in payload }
        require(shape.keys.all { it in cols }) { "$table op has no primary key" }
        val update = cols.filter { it !in shape.keys }
        // Audit events are append-only: an existing one is never rewritten.
        val onConflict = if (table == "audit_events" || update.isEmpty()) "DO NOTHING" else "DO UPDATE SET " + update.joinToString { "`$it` = excluded.`$it`" }
        exec(
            "INSERT INTO `$table` (${cols.joinToString { "`$it`" }}) SELECT ${cols.joinToString { extract(it) }} WHERE true " +
                "ON CONFLICT (${shape.keys.joinToString { "`$it`" }}) $onConflict",
            payload,
        )
    }

    suspend fun delete(table: String, key: JsonObject) {
        val sql = "DELETE FROM `$table` WHERE ${where(table, key, "`$table`")}"
        if (table != "audit_events") return exec(sql, key)
        // Only a server correction deletes an audit event (one whose write was rejected); the guard is restored in the same transaction.
        exec("DROP TRIGGER IF EXISTS audit_events_no_delete")
        exec(sql, key)
        AuditTriggers.statements.forEach { exec(it) }
    }

    /** The row's synced columns, or null when it does not exist. */
    suspend fun read(table: String, key: JsonObject): JsonObject? {
        val shape = shape(table)
        return c.usePrepared("SELECT ${SyncTriggers.json("r", shape.shared(table))} FROM `$table` r WHERE ${where(table, key, "r")}") { s ->
            s.bindText(1, key.toString())
            if (s.step()) sortedObject(Json.parseToJsonElement(s.getText(0)).jsonObject) else null
        }
    }

    suspend fun key(table: String, payload: JsonObject): JsonObject = JsonObject(shape(table).keys.associateWith { payload.getValue(it) })

    /** The server's state of an entity as an op: an upsert of its row, or a delete when it is gone. */
    suspend fun correction(op: SyncOperation, groupId: String, seq: Long): SyncOperation {
        val key = key(op.entity, op.payload)
        val row = read(op.entity, key)
        return SyncOperation(
            seq = seq, opId = randomOpId(), firmId = op.firmId, deviceId = SERVER_DEVICE, userId = null,
            hlc = Clock.System.now().toEpochMilliseconds(), groupId = groupId, entity = op.entity, entityId = op.entityId,
            kind = if (row == null) SyncOperation.Kind.DELETE else SyncOperation.Kind.UPSERT, action = "sync.correction",
            baseRevision = null, payload = row ?: key, schemaVersion = OPENBILL_DATABASE_VERSION,
        )
    }

    suspend fun long(sql: String, arg: String): Long = c.usePrepared(sql) { s ->
        s.bindText(1, arg)
        if (s.step()) s.getLong(0) else 0L
    }

    suspend fun texts(sql: String, arg: String): List<String> = c.usePrepared(sql) { s ->
        s.bindText(1, arg)
        buildList { while (s.step()) add(s.getText(0)) }
    }

    private suspend fun where(table: String, key: JsonObject, alias: String): String {
        val keys = shape(table).keys
        require(keys.all { it in key }) { "$table op has no primary key" }
        return keys.joinToString(" AND ") { "$alias.`$it` = ${extract(it)}" }
    }

    private fun extract(column: String) = "json_extract(?1, '$.\"$column\"')"

    private suspend fun exec(sql: String, json: JsonObject? = null) {
        c.usePrepared(sql) { s ->
            if (json != null) s.bindText(1, json.toString())
            s.step()
        }
    }

    companion object {
        /** Author id of ops the server writes itself (corrections, review events). */
        const val SERVER_DEVICE = "server"

        fun randomOpId(): String = Random.nextBytes(16).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

        fun newGroupId(): String = Ulid.create(Clock.System.now().toEpochMilliseconds()).toString()

        fun entityId(keys: List<String>, payload: JsonObject): String = keys.joinToString("|") { payload.getValue(it).jsonPrimitive.content }

        internal fun sortedObject(o: JsonObject) = JsonObject(o.entries.sortedBy { it.key }.associate { it.key to it.value })

        internal fun text(o: JsonObject, key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
