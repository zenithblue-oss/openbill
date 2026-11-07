package app.openbill.server

import androidx.sqlite.SQLiteException
import app.openbill.core.database.OPENBILL_DATABASE_VERSION
import app.openbill.core.sync.SyncRefusedException
import app.openbill.core.sync.SyncServer
import app.openbill.core.sync.SyncWire
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.contentLength
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory

/** Everything a request needs: settings, accounts, the sync core. */
class ServerApp(val config: ServerConfig, val instance: Instance, val sync: SyncServer, val version: String) {
    val limiter = LoginLimiter(config.loginAttemptsPerMinute)
}

/** An error with a stable code for clients (RFC 9457 problem body). */
class ApiError(val status: Int, val code: String, override val message: String, val retryAfter: Long? = null) : Exception(message)

private val log = LoggerFactory.getLogger("openbill.server")
private val PROBLEM = ContentType.parse("application/problem+json")

/**
 * The HTTP API (docs/10-server.md section 8). Deny by default: everything under `/api/v1` except
 * version, setup, login and token renewal needs a bearer access token; workspace routes also
 * need membership (an unknown and a forbidden workspace look the same: 404). No CORS.
 * Logs carry method, path, status and time only: never bodies, tokens or business data.
 */
fun Application.openBillApi(app: ServerApp) {
    val config = app.config
    intercept(ApplicationCallPipeline.Plugins) {
        val started = System.nanoTime()
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header(HttpHeaders.CacheControl, "no-store")
        try {
            proceed()
        } finally {
            if (call.request.path() !in setOf("/healthz", "/readyz")) {
                log.info("{} {} {} {}ms", call.request.httpMethod.value, call.request.path(), call.response.status()?.value ?: 0, (System.nanoTime() - started) / 1_000_000)
            }
        }
    }

    routing {
        get("/healthz") { call.respondText("ok") }
        get("/readyz") {
            val ready = runCatching { app.instance.ping() }.getOrDefault(false)
            call.respondText(if (ready) "ready" else "not ready", status = if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable)
        }
        get("/api/v1/version") {
            call.json(
                obj("name" to "openbill-server", "version" to app.version, "api" to 1, "schemaVersion" to OPENBILL_DATABASE_VERSION, "setupRequired" to !app.instance.hasUsers()),
            )
        }

        post("/api/v1/setup") {
            call.guard {
                val ip = call.address(config)
                throttle(app, "ip:$ip")
                val body = call.jsonBody(config)
                val password = body.str("password")
                Passwords.requireStrong(password)
                val hash = withContext(Dispatchers.Default) { Passwords.hash(password) }
                val user = app.instance.setup(body.str("setupToken"), body.str("username"), hash)
                if (user == null) {
                    app.limiter.failed("ip:$ip")
                    throw ApiError(403, "setup_refused", "Wrong setup token, or an admin already exists")
                }
                app.instance.audit(user.id, "admin.setup", user.username, ip)
                call.json(obj("id" to user.id, "username" to user.username, "admin" to true), HttpStatusCode.Created)
            }
        }

        post("/api/v1/auth/login") {
            call.guard {
                val ip = call.address(config)
                val body = call.jsonBody(config)
                val username = body.str("username")
                val keys = listOf("ip:$ip", "user:${username.lowercase()}")
                keys.forEach { throttle(app, it) }
                val found = app.instance.findUser(username)
                val ok = withContext(Dispatchers.Default) { Passwords.verify(body.str("password"), found?.second ?: Passwords.DUMMY) } && found != null
                if (!ok) {
                    keys.forEach(app.limiter::failed)
                    app.instance.audit(found?.first?.id, "login.failed", if (found != null) username else null, ip)
                    throw ApiError(401, "bad_credentials", "Wrong username or password")
                }
                val user = found!!.first
                app.limiter.succeeded("user:${username.lowercase()}")
                val (access, refresh) = app.instance.signIn(user, config.accessTokenMinutes, config.refreshTokenDays)
                app.instance.audit(user.id, "login", user.username, ip)
                call.json(obj("accessToken" to access, "refreshToken" to refresh, "tokenType" to "Bearer", "expiresIn" to config.accessTokenMinutes * 60))
            }
        }

        post("/api/v1/auth/token") {
            call.guard {
                val ip = call.address(config)
                throttle(app, "ip:$ip")
                val access = app.instance.renew(call.jsonBody(config).str("refreshToken"), config.accessTokenMinutes)
                if (access == null) {
                    app.limiter.failed("ip:$ip")
                    throw ApiError(401, "invalid_token", "Sign in again")
                }
                call.json(obj("accessToken" to access, "tokenType" to "Bearer", "expiresIn" to config.accessTokenMinutes * 60))
            }
        }

        post("/api/v1/auth/logout") {
            call.guard {
                val user = call.user(app)
                app.instance.signOut(call.bearer()!!)
                app.instance.audit(user.id, "logout", user.username, call.address(config))
                call.respondText("", status = HttpStatusCode.NoContent)
            }
        }

        get("/api/v1/me") {
            call.guard {
                val user = call.user(app)
                call.json(obj("id" to user.id, "username" to user.username, "admin" to user.admin))
            }
        }

        get("/api/v1/workspaces") {
            call.guard {
                val user = call.user(app)
                val list = app.instance.workspaces(user).map { obj("id" to it.id, "name" to it.name, "createdAt" to it.createdAt, "seeded" to app.sync.exists(it.id)) }
                call.json(JsonObject(mapOf("workspaces" to JsonArray(list))))
            }
        }

        post("/api/v1/workspaces") {
            call.guard {
                val user = call.user(app)
                if (!user.admin) throw ApiError(403, "forbidden", "Only an admin creates workspaces")
                val ws = app.instance.createWorkspace(call.jsonBody(config).str("name"), user)
                app.instance.audit(user.id, "workspace.create", ws.id, call.address(config))
                call.json(obj("id" to ws.id, "name" to ws.name, "createdAt" to ws.createdAt, "seeded" to false), HttpStatusCode.Created)
            }
        }

        post("/api/v1/sync/workspaces/{w}/seed") {
            call.guard {
                val user = call.user(app)
                val w = call.workspace(app, user)
                if (app.sync.exists(w)) throw ApiError(409, "already_seeded", "This workspace already has data; join it instead")
                val bytes = call.limitedBody(config.maxSeedBytes)
                try {
                    app.sync.createWorkspace(w, bytes)
                } catch (e: SQLiteException) {
                    throw ApiError(400, "bad_snapshot", "Not an OpenBill database")
                }
                val epoch = app.sync.pull(w, 0, 1).epoch
                app.instance.audit(user.id, "workspace.seed", w, call.address(config), "${bytes.size} bytes")
                call.json(obj("head" to 0, "epoch" to epoch), HttpStatusCode.Created)
            }
        }

        post("/api/v1/sync/workspaces/{w}/ops") {
            call.guard {
                val w = call.seededWorkspace(app, call.user(app))
                val ops = SyncWire.parsePushRequest(call.limitedBody(config.maxRequestBytes).decodeToString())
                if (ops.size > config.maxPushOps) throw ApiError(413, "too_many_ops", "At most ${config.maxPushOps} ops per push")
                call.respondText(SyncWire.pushResponse(app.sync.push(w, ops)), ContentType.Application.Json)
            }
        }

        get("/api/v1/sync/workspaces/{w}/ops") {
            call.guard {
                val w = call.seededWorkspace(app, call.user(app))
                val after = call.parameters["after"]?.toLongOrNull()?.takeIf { it >= 0 } ?: throw ApiError(400, "bad_request", "after must be a seq (0 or more)")
                val limit = (call.parameters["limit"]?.toIntOrNull() ?: PAGE).coerceIn(1, PAGE)
                call.respondText(SyncWire.pullResponse(app.sync.pull(w, after, limit)), ContentType.Application.Json)
            }
        }

        get("/api/v1/sync/workspaces/{w}/snapshot") {
            call.guard {
                call.seededWorkspace(app, call.user(app))
                throw ApiError(501, "not_implemented", "Snapshot download arrives in milestone S1c")
            }
        }
    }
}

private const val PAGE = 500

private suspend fun ApplicationCall.guard(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: ApiError) {
        problem(e.status, e.code, e.message, e.retryAfter)
    } catch (e: SyncRefusedException) {
        problem(409, "sync_refused", e.message ?: "Refused")
    } catch (e: IllegalArgumentException) {
        problem(400, "bad_request", e.message ?: "Bad request")
    } catch (e: Exception) {
        log.error("{} {} failed: {}", request.httpMethod.value, request.path(), e.toString(), e)
        problem(500, "internal", "The server failed; try again")
    }
}

private suspend fun ApplicationCall.problem(status: Int, code: String, detail: String, retryAfter: Long? = null) {
    retryAfter?.let { response.header(HttpHeaders.RetryAfter, it.toString()) }
    if (status == 401) response.header(HttpHeaders.WWWAuthenticate, "Bearer")
    respondText(SyncWire.problem(status, code, detail), PROBLEM, HttpStatusCode.fromValue(status))
}

private fun throttle(app: ServerApp, key: String) {
    val wait = app.limiter.blockedFor(key)
    if (wait > 0) throw ApiError(429, "too_many_attempts", "Too many failed attempts; try again in $wait seconds", wait)
}

private fun ApplicationCall.bearer(): String? = request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()

private fun ApplicationCall.user(app: ServerApp): Instance.User =
    bearer()?.let(app.instance::userForAccess) ?: throw ApiError(401, "unauthorized", "Sign in first")

private fun ApplicationCall.workspace(app: ServerApp, user: Instance.User): String {
    val w = parameters["w"].orEmpty()
    if (!SyncServer.ID.matches(w) || !app.instance.isMember(w, user)) throw ApiError(404, "no_workspace", "No such workspace")
    return w
}

private fun ApplicationCall.seededWorkspace(app: ServerApp, user: Instance.User): String =
    workspace(app, user).also { if (!app.sync.exists(it)) throw ApiError(409, "not_seeded", "The workspace has no data yet; seed it from the first device") }

private fun ApplicationCall.address(config: ServerConfig): String =
    (if (config.trustForwardedFor) request.headers["X-Forwarded-For"]?.split(',')?.lastOrNull()?.trim()?.takeIf { it.isNotEmpty() } else null)
        ?: request.local.remoteAddress

/** The body, refused past [max] bytes (413) without reading more than that. */
private suspend fun ApplicationCall.limitedBody(max: Long): ByteArray {
    request.contentLength()?.let { if (it > max) throw ApiError(413, "too_large", "The request is over ${max shr 20} MB") }
    val bytes = receiveChannel().readRemaining(max + 1).readByteArray()
    if (bytes.size > max) throw ApiError(413, "too_large", "The request is over ${max shr 20} MB")
    return bytes
}

private suspend fun ApplicationCall.jsonBody(config: ServerConfig): JsonObject =
    runCatching { Json.parseToJsonElement(limitedBody(minOf(config.maxRequestBytes, 64L shl 10)).decodeToString()).jsonObject }.getOrElse {
        if (it is ApiError) throw it
        throw ApiError(400, "bad_request", "Expected a JSON object")
    }

private fun JsonObject.str(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ApiError(400, "bad_request", "Missing $key")

private fun obj(vararg pairs: Pair<String, Any>) = JsonObject(
    pairs.associate { (k, v) ->
        k to when (v) {
            is String -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            else -> error("Unsupported JSON value")
        }
    },
)

private suspend fun ApplicationCall.json(body: JsonObject, status: HttpStatusCode = HttpStatusCode.OK) =
    respondText(body.toString(), ContentType.Application.Json, status)
