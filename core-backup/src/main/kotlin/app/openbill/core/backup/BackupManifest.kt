package app.openbill.core.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

data class BackupFirm(val id: String, val name: String)

/** manifest.json; [files] maps each archive entry to its SHA-256 (hex). */
data class BackupManifest(
    val formatVersion: Int,
    val appVersion: String,
    val schemaVersion: Int,
    val createdAt: String,
    val firms: List<BackupFirm>,
    val recordCounts: Map<String, Long>,
    val files: Map<String, String>,
) {
    fun toJson(): String = pretty.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("formatVersion", formatVersion)
        put("appVersion", appVersion)
        put("schemaVersion", schemaVersion)
        put("createdAt", createdAt)
        put("firmIds", buildJsonArray { firms.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.id)) } })
        put("firms", buildJsonArray { firms.forEach { f -> add(buildJsonObject { put("id", f.id); put("name", f.name) }) } })
        put("recordCounts", buildJsonObject { recordCounts.forEach { (k, v) -> put(k, v) } })
        put("sha256", buildJsonObject { files.forEach { (k, v) -> put(k, v) } })
    })

    companion object {
        private val pretty = Json { prettyPrint = true }

        fun parse(text: String): BackupManifest {
            val o = Json.parseToJsonElement(text).jsonObject
            return BackupManifest(
                formatVersion = o.getValue("formatVersion").jsonPrimitive.int,
                appVersion = o["appVersion"]?.jsonPrimitive?.content ?: "",
                schemaVersion = o.getValue("schemaVersion").jsonPrimitive.int,
                createdAt = o.getValue("createdAt").jsonPrimitive.content,
                firms = o["firms"]?.jsonArray?.map {
                    val f = it.jsonObject
                    BackupFirm(f.getValue("id").jsonPrimitive.content, f.getValue("name").jsonPrimitive.content)
                } ?: emptyList(),
                recordCounts = o["recordCounts"]?.jsonObject?.mapValues { it.value.jsonPrimitive.long } ?: emptyMap(),
                files = o.getValue("sha256").jsonObject.mapValues { it.value.jsonPrimitive.content },
            )
        }
    }
}
