package app.openbill.core.sync

import app.openbill.core.database.SyncOutboxEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * One authored change, as it travels to the server (docs/10-server.md section 6.2). The device
 * orders its ops by [seq]; [hlc] (wall millis, never decreasing on this device) with [seq] and
 * [deviceId] as tie-breaks orders master edits across devices. The server's own `seq` is
 * assigned on acceptance and is not part of the authored op.
 */
data class SyncOperation(
    val seq: Long,
    val opId: String,
    val firmId: String?,
    val deviceId: String,
    val userId: String?,
    val hlc: Long,
    val groupId: String?,
    val entity: String,
    val entityId: String,
    val kind: Kind,
    val action: String?,
    val baseRevision: Long?,
    val payload: JsonObject,
    val schemaVersion: Int,
) {
    enum class Kind(val code: String) { UPSERT("upsert"), DELETE("delete") }

    /** Canonical JSON: fixed field order, payload keys sorted, no whitespace. Same op, same bytes. */
    fun toJson(): String = json.encodeToString(JsonObject.serializer(), toJsonObject())

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("opId", opId)
        put("seq", seq)
        put("firmId", firmId)
        put("deviceId", deviceId)
        put("userId", userId)
        put("hlc", hlc)
        put("groupId", groupId)
        put("entity", entity)
        put("entityId", entityId)
        put("type", kind.code)
        put("action", action)
        put("baseRevision", baseRevision)
        put("payload", sorted(payload))
        put("schemaVersion", schemaVersion)
    }

    companion object {
        private val json = Json

        fun fromJson(text: String): SyncOperation = fromJsonObject(json.parseToJsonElement(text).jsonObject)

        fun fromJsonObject(o: JsonObject): SyncOperation {
            fun text(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun need(key: String) = requireNotNull(text(key)) { "Sync op has no $key" }
            val type = need("type")
            return SyncOperation(
                seq = o.getValue("seq").jsonPrimitive.long,
                opId = need("opId"),
                firmId = text("firmId"),
                deviceId = need("deviceId"),
                userId = text("userId"),
                hlc = o.getValue("hlc").jsonPrimitive.long,
                groupId = text("groupId"),
                entity = need("entity"),
                entityId = need("entityId"),
                kind = Kind.entries.firstOrNull { it.code == type } ?: throw IllegalArgumentException("Unknown sync op type $type"),
                action = text("action"),
                baseRevision = o["baseRevision"]?.jsonPrimitive?.longOrNull,
                payload = sorted(o.getValue("payload").jsonObject),
                schemaVersion = o.getValue("schemaVersion").jsonPrimitive.long.toInt(),
            )
        }

        fun from(row: SyncOutboxEntity): SyncOperation = SyncOperation(
            seq = row.seq,
            opId = row.opId,
            firmId = row.firmId,
            deviceId = row.deviceId,
            userId = row.userId,
            hlc = row.hlc,
            groupId = row.groupId,
            entity = row.entity,
            entityId = row.entityId,
            kind = Kind.entries.first { it.code == row.kind },
            action = row.action,
            baseRevision = row.baseRevision,
            payload = sorted(json.parseToJsonElement(row.payload).jsonObject),
            schemaVersion = row.schemaVersion,
        )

        private fun sorted(o: JsonObject): JsonObject = JsonObject(o.entries.sortedBy { it.key }.associate { it.key to it.value })
    }
}
