package app.openbill.core.sync

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * [SyncTransport] over the server's HTTP API (docs/10-server.md 6.3, 8). Signs in with a
 * long-lived [refreshToken] for short-lived access tokens, renewed on 401. Link failures and 5xx/429
 * are [SyncTransportException] (retried by [SyncClient]); a refusal (403/404/409, a revoked sign-in)
 * is [SyncRefusedException]. [client] carries TLS settings (certificate pinning comes with pairing).
 */
class HttpSyncTransport(
    baseUrl: String,
    private val refreshToken: String,
    private val client: HttpClient,
) : SyncTransport {
    private val base = baseUrl.trimEnd('/')
    private val lock = Mutex()
    private var access: String? = null

    override suspend fun push(workspaceId: String, ops: List<SyncOperation>): PushResponse =
        SyncWire.parsePushResponse(call(HttpMethod.Post, "/api/v1/sync/workspaces/$workspaceId/ops", SyncWire.pushRequest(ops)).bodyAsText())

    override suspend fun pull(workspaceId: String, after: Long, limit: Int): PullResponse =
        SyncWire.parsePullResponse(call(HttpMethod.Get, "/api/v1/sync/workspaces/$workspaceId/ops?after=$after&limit=$limit").bodyAsText())

    override suspend fun snapshot(workspaceId: String): Snapshot {
        val r = call(HttpMethod.Get, "/api/v1/sync/workspaces/$workspaceId/snapshot")
        return Snapshot(r.headers[SEQ_HEADER]!!.toLong(), r.headers[EPOCH_HEADER]!!, r.bodyAsBytes())
    }

    /** Seeds an empty [workspaceId] with this device's database file (section 6.6). */
    suspend fun seed(workspaceId: String, database: ByteArray) {
        call(HttpMethod.Post, "/api/v1/sync/workspaces/$workspaceId/seed", database, ContentType.Application.OctetStream)
    }

    private suspend fun call(method: HttpMethod, path: String, body: Any? = null, type: ContentType = ContentType.Application.Json): HttpResponse {
        for (attempt in 0..1) {
            val token = lock.withLock { access ?: renew().also { access = it } }
            val response = send(method, path, token, body, type)
            if (response.status.value == 401 && attempt == 0) {
                lock.withLock { if (access == token) access = null }
                continue
            }
            return response.checked()
        }
        error("unreachable")
    }

    private suspend fun renew(): String {
        val body = JsonObject(mapOf("refreshToken" to JsonPrimitive(refreshToken))).toString()
        val text = send(HttpMethod.Post, "/api/v1/auth/token", null, body, ContentType.Application.Json).checked().bodyAsText()
        return Json.parseToJsonElement(text).jsonObject.getValue("accessToken").jsonPrimitive.content
    }

    private suspend fun send(method: HttpMethod, path: String, token: String?, body: Any?, type: ContentType): HttpResponse = try {
        client.request(base + path) {
            this.method = method
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            if (body != null) {
                contentType(type)
                setBody(body)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw SyncTransportException("Server unreachable: ${e.message}", e)
    }

    private suspend fun HttpResponse.checked(): HttpResponse {
        val status = status.value
        if (status in 200..299) return this
        val detail = SyncWire.parseProblem(bodyAsText())?.second ?: "HTTP $status"
        throw when (status) {
            501 -> SyncRefusedException(detail)
            408, 429, in 500..599 -> SyncTransportException("Server busy or failing: $detail")
            401 -> SyncRefusedException("The server no longer accepts this sign-in: $detail")
            else -> SyncRefusedException(detail)
        }
    }

    companion object {
        const val SEQ_HEADER = "X-OpenBill-Seq"
        const val EPOCH_HEADER = "X-OpenBill-Epoch"

        /** Signs in with a password; returns the refresh token to keep (never the password). */
        suspend fun login(client: HttpClient, baseUrl: String, username: String, password: String): String {
            val body = JsonObject(mapOf("username" to JsonPrimitive(username), "password" to JsonPrimitive(password))).toString()
            val r = client.request(baseUrl.trimEnd('/') + "/api/v1/auth/login") {
                method = HttpMethod.Post
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            val text = r.bodyAsText()
            if (r.status.value != 200) throw SyncRefusedException(SyncWire.parseProblem(text)?.second ?: "Sign-in failed (HTTP ${r.status.value})")
            return Json.parseToJsonElement(text).jsonObject.getValue("refreshToken").jsonPrimitive.content
        }
    }
}
