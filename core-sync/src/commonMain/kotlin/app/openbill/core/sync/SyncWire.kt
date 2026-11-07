package app.openbill.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

/**
 * JSON bodies of the sync endpoints (docs/10-server.md 6.3), one codec for server and devices.
 * Ops use their canonical form ([SyncOperation.toJsonObject]).
 */
object SyncWire {
    fun pushRequest(ops: List<SyncOperation>): String = obj("ops" to JsonArray(ops.map { it.toJsonObject() })).toString()

    fun parsePushRequest(text: String): List<SyncOperation> = parse(text).getValue("ops").jsonArray.map { SyncOperation.fromJsonObject(it.jsonObject) }

    fun pushResponse(r: PushResponse): String = obj(
        "epoch" to JsonPrimitive(r.epoch),
        "results" to JsonArray(
            r.results.map {
                obj("opId" to JsonPrimitive(it.opId), "status" to JsonPrimitive(it.status.name.lowercase()), "seq" to JsonPrimitive(it.seq), "reason" to JsonPrimitive(it.reason))
            },
        ),
        "corrections" to JsonArray(r.corrections.map { it.toJsonObject() }),
    ).toString()

    fun parsePushResponse(text: String): PushResponse {
        val o = parse(text)
        return PushResponse(
            epoch = o.text("epoch")!!,
            results = o.getValue("results").jsonArray.map { e ->
                val r = e.jsonObject
                val status = r.text("status")!!
                OpResult(
                    opId = r.text("opId")!!,
                    status = OpResult.Status.entries.firstOrNull { it.name.lowercase() == status } ?: throw IllegalArgumentException("Unknown op status $status"),
                    seq = r["seq"]?.jsonPrimitive?.longOrNull,
                    reason = r.text("reason"),
                )
            },
            corrections = o.getValue("corrections").jsonArray.map { SyncOperation.fromJsonObject(it.jsonObject) },
        )
    }

    fun pullResponse(r: PullResponse): String = obj(
        "epoch" to JsonPrimitive(r.epoch),
        "epochStart" to JsonPrimitive(r.epochStart),
        "durable" to JsonPrimitive(r.durable),
        "head" to JsonPrimitive(r.head),
        "ops" to JsonArray(r.ops.map { obj("seq" to JsonPrimitive(it.seq), "op" to it.op.toJsonObject()) }),
    ).toString()

    fun parsePullResponse(text: String): PullResponse {
        val o = parse(text)
        return PullResponse(
            epoch = o.text("epoch")!!,
            epochStart = o.getValue("epochStart").jsonPrimitive.long,
            durable = o.getValue("durable").jsonPrimitive.long,
            head = o.getValue("head").jsonPrimitive.long,
            ops = o.getValue("ops").jsonArray.map { e -> Pulled(e.jsonObject.getValue("seq").jsonPrimitive.long, SyncOperation.fromJsonObject(e.jsonObject.getValue("op").jsonObject)) },
        )
    }

    /** RFC 9457 problem body: a stable [code] for programs, [detail] for people. */
    fun problem(status: Int, code: String, detail: String): String =
        obj("type" to JsonPrimitive("about:blank"), "status" to JsonPrimitive(status), "code" to JsonPrimitive(code), "detail" to JsonPrimitive(detail)).toString()

    /** The (code, detail) of a problem body; null when it is not one. */
    fun parseProblem(text: String): Pair<String, String>? = runCatching { parse(text).let { it.text("code")!! to (it.text("detail") ?: "") } }.getOrNull()

    private fun obj(vararg pairs: Pair<String, JsonElement?>) = JsonObject(pairs.associate { (k, v) -> k to (v ?: JsonNull) })

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
