package com.example.bot.security.rbac

import com.example.bot.data.booking.core.AuditLogRepository
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.security.auth.TelegramPrincipal
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.application
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.auth.principal
import io.ktor.server.plugins.doublereceive.DoubleReceive
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.server.routing.application
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.slf4j.MDC

private val jsonParser = Json { ignoreUnknownKeys = true }

private val successRoles = setOf(Role.OWNER, Role.GLOBAL_ADMIN, Role.HEAD_MANAGER)

private val rbacStateKey = AttributeKey<RbacState>("rbac.state")
private val rbacResolutionKey = AttributeKey<RbacResolution>("rbac.resolution")
private val accessLogStateKey = AttributeKey<AccessLogState>("rbac.access.state")

/** Scope for club related routes. */
enum class ClubScope { Own, Any }

/** Configuration for [RbacPlugin]. */
class RbacConfig {
    lateinit var userRepository: UserRepository
    lateinit var userRoleRepository: UserRoleRepository
    lateinit var auditLogRepository: AuditLogRepository

    var clubScopeResolver: ClubScopeResolver = ClubScopeResolver()

    var principalExtractor: suspend (ApplicationCall) -> TelegramPrincipal? = { call -> call.principal() }

    internal fun buildState(): RbacState {
        check(::userRepository.isInitialized) { "userRepository must be provided" }
        check(::userRoleRepository.isInitialized) { "userRoleRepository must be provided" }
        check(::auditLogRepository.isInitialized) { "auditLogRepository must be provided" }
        return RbacState(
            userRepository = userRepository,
            userRoleRepository = userRoleRepository,
            auditLogRepository = auditLogRepository,
            clubScopeResolver = clubScopeResolver,
            principalExtractor = principalExtractor,
        )
    }
}

private val doubleReceiveLock = Any()

private class AuthorizeRouteConfig {
    var requiredRoles: Set<Role> = emptySet()
}

private val AuthorizeRoutePlugin =
    createRouteScopedPlugin(name = "AuthorizeRoutePlugin", createConfiguration = ::AuthorizeRouteConfig) {
        val state = application.attributes[rbacStateKey]
        onCall { call ->
            val required = pluginConfig.requiredRoles
            val access = call.attributes[accessLogStateKey]
            val resolution = call.attributes[rbacResolutionKey]
            when (resolution) {
                is RbacResolution.Failure -> {
                    state.handleFailure(call, access, resolution)
                    return@onCall
                }
                is RbacResolution.Success -> {
                    if (required.isNotEmpty() && resolution.roles.intersect(required).isEmpty()) {
                        state.handleForbidden(call, access, resolution, "missing_role", null)
                        return@onCall
                    } else {
                        access.roleCheckPassed = true
                    }
                }
            }
        }
    }

private class ClubScopeRouteConfig {
    var scope: ClubScope = ClubScope.Own
}

private val ClubScopeRoutePlugin =
    createRouteScopedPlugin(name = "ClubScopeRoutePlugin", createConfiguration = ::ClubScopeRouteConfig) {
        val state = application.attributes[rbacStateKey]
        onCall { call ->
            val access = call.attributes[accessLogStateKey]
            access.scopeRequired = true
            val resolution = call.attributes[rbacResolutionKey]
            val context =
                when (resolution) {
                    is RbacResolution.Failure -> {
                        state.handleFailure(call, access, resolution)
                        return@onCall
                    }
                    is RbacResolution.Success -> resolution
                }
            val clubId = state.clubScopeResolver.resolve(call)
            val hasGlobalRole = context.roles.any { it in successRoles }
            val allowed =
                when (pluginConfig.scope) {
                    ClubScope.Own ->
                        when {
                            clubId == null -> false
                            hasGlobalRole -> true
                            else -> clubId in context.clubIds
                        }
                    ClubScope.Any -> hasGlobalRole
                }
            if (!allowed) {
                val reason =
                    when (pluginConfig.scope) {
                        ClubScope.Own -> if (clubId == null) "club_missing" else "club_scope_violation"
                        ClubScope.Any -> "global_role_required"
                    }
                state.handleForbidden(call, access, context, reason, clubId)
                return@onCall
            } else {
                access.scopePassed = true
                if (clubId != null) {
                    access.clubId = clubId
                }
            }
        }
    }

/**
 * RBAC plugin responsible for injecting request context and audit logging.
 */
val RbacPlugin =
    createApplicationPlugin(name = "RbacPlugin", createConfiguration = ::RbacConfig) {
        val state = pluginConfig.buildState()
        installDoubleReceiveIfNeeded(application)
        application.attributes.put(rbacStateKey, state)

        onCall { call ->
            val accessState = AccessLogState()
            call.attributes.put(accessLogStateKey, accessState)
            val resolution = state.resolve(call)
            call.attributes.put(rbacResolutionKey, resolution)
        }

        application.intercept(ApplicationCallPipeline.Call) {
            proceed()
            if (!call.attributes.contains(accessLogStateKey)) return@intercept
            if (!call.attributes.contains(rbacResolutionKey)) return@intercept
            val access = call.attributes[accessLogStateKey]
            val resolution = call.attributes[rbacResolutionKey]
            state.logFinalDecision(call, access, resolution)
        }
    }

private fun installDoubleReceiveIfNeeded(application: Application) {
    if (application.pluginOrNull(DoubleReceive) != null) return
    synchronized(doubleReceiveLock) {
        if (application.pluginOrNull(DoubleReceive) == null) {
            application.install(DoubleReceive)
        }
    }
}

/** DSL entry point for enforcing role based access. */
fun Route.authorize(
    vararg roles: Role,
    block: Route.() -> Unit,
) {
    val authorizedRoute = createChild(PluginRouteSelector("authorize"))
    authorizedRoute.install(AuthorizeRoutePlugin) { requiredRoles = roles.toSet() }
    authorizedRoute.block()
}

/** Applies club scope rules to nested routes. */
fun Route.clubScoped(
    scope: ClubScope,
    block: Route.() -> Unit,
) {
    val scopedRoute = createChild(PluginRouteSelector("clubScoped"))
    scopedRoute.install(ClubScopeRoutePlugin) { this.scope = scope }
    scopedRoute.block()
}

private class PluginRouteSelector(private val label: String) : RouteSelector() {
    override suspend fun evaluate(
        context: RoutingResolveContext,
        segmentIndex: Int,
    ): RouteSelectorEvaluation = RouteSelectorEvaluation.Constant

    override fun toString(): String = label
}

internal sealed interface RbacResolution {
    data class Success(
        val principal: TelegramPrincipal,
        val user: User,
        val roles: Set<Role>,
        val clubIds: Set<Long>,
    ) : RbacResolution

    data class Failure(val reason: FailureReason) : RbacResolution
}

data class RbacContext(
    val principal: TelegramPrincipal,
    val user: User,
    val roles: Set<Role>,
    val clubIds: Set<Long>,
)

enum class FailureReason { MissingPrincipal, UserNotFound }

internal data class AccessLogState(
    var roleCheckPassed: Boolean = false,
    var scopeRequired: Boolean = false,
    var scopePassed: Boolean = false,
    var clubId: Long? = null,
    var logged: Boolean = false,
)

internal data class RbacState(
    val userRepository: UserRepository,
    val userRoleRepository: UserRoleRepository,
    val auditLogRepository: AuditLogRepository,
    val clubScopeResolver: ClubScopeResolver,
    val principalExtractor: suspend (ApplicationCall) -> TelegramPrincipal?,
) {
    suspend fun resolve(call: ApplicationCall): RbacResolution {
        val principal = principalExtractor(call)
        return if (principal == null) {
            RbacResolution.Failure(FailureReason.MissingPrincipal)
        } else {
            val user = userRepository.getByTelegramId(principal.userId)
            if (user == null) {
                RbacResolution.Failure(FailureReason.UserNotFound)
            } else {
                val roles = userRoleRepository.listRoles(user.id)
                val clubIds = userRoleRepository.listClubIdsFor(user.id)
                RbacResolution.Success(principal, user, roles, clubIds)
            }
        }
    }

    suspend fun handleFailure(
        call: ApplicationCall,
        access: AccessLogState,
        failure: RbacResolution.Failure,
    ) {
        val reason =
            when (failure.reason) {
                FailureReason.MissingPrincipal -> "missing_principal"
                FailureReason.UserNotFound -> "user_not_found"
            }
        if (!access.logged) {
            logDecision(call, null, "access_denied", reason, null)
            access.logged = true
        }
        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
    }

    suspend fun handleForbidden(
        call: ApplicationCall,
        access: AccessLogState,
        context: RbacResolution.Success,
        reason: String,
        clubId: Long?,
    ) {
        if (!access.logged) {
            logDecision(call, context, "access_denied", reason, clubId)
            access.logged = true
        }
        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
    }

    suspend fun logFinalDecision(
        call: ApplicationCall,
        access: AccessLogState,
        resolution: RbacResolution,
    ) {
        val success = resolution as? RbacResolution.Success
        val shouldLog =
            !access.logged &&
                success != null &&
                access.roleCheckPassed &&
                (!access.scopeRequired || access.scopePassed)
        if (!shouldLog) return
        logDecision(call, success, "access_granted", "authorized", access.clubId)
        access.logged = true
    }

    private suspend fun logDecision(
        call: ApplicationCall,
        success: RbacResolution.Success?,
        result: String,
        reason: String,
        clubId: Long?,
    ) {
        val userId = success?.user?.id
        val ip = call.request.local.remoteAddress
        val meta =
            buildJsonObject {
                put("reason", reason)
                put("method", call.request.httpMethod.value)
            }
        withIdempotencyMdc(call) {
            auditLogRepository.log(
                userId = userId,
                action = "http_access",
                resource = call.request.path(),
                clubId = clubId,
                result = result,
                ip = ip,
                meta = meta,
            )
        }
    }
}

private suspend fun withIdempotencyMdc(
    call: ApplicationCall,
    block: suspend () -> Unit,
) {
    val key = call.request.header("Idempotency-Key") ?: call.request.queryParameters["idempotency_key"]
    if (key == null) {
        block()
    } else {
        MDC.put("idempotency_key", key)
        try {
            block()
        } finally {
            MDC.remove("idempotency_key")
        }
    }
}

private val clubResolutionKey = AttributeKey<ClubResolution>("rbac.club.resolution")

private data class ClubResolution(val clubId: Long?)

/** Resolves club identifier from request parameters. */
class ClubScopeResolver(private val bodyKeys: Set<String> = setOf("clubId", "club_id")) {
    suspend fun resolve(call: ApplicationCall): Long? {
        val explicit = if (call.attributes.contains(CLUB_ID_ATTRIBUTE)) call.attributes[CLUB_ID_ATTRIBUTE] else null
        val cachedHolder = if (call.attributes.contains(clubResolutionKey)) call.attributes[clubResolutionKey] else null
        val cached = cachedHolder?.clubId
        val resolved =
            explicit
                ?: cached
                ?: findInParameters(call)
                ?: findInQuery(call)
                ?: findInHeaders(call)
                ?: findInBody(call)
        if (cachedHolder == null) {
            call.attributes.put(clubResolutionKey, ClubResolution(resolved))
        }
        return resolved
    }

    private fun findInParameters(call: ApplicationCall): Long? =
        parse(call.parameters["clubId"]) ?: parse(call.parameters["club_id"])

    private fun findInQuery(call: ApplicationCall): Long? =
        parse(call.request.queryParameters["clubId"]) ?: parse(call.request.queryParameters["club_id"])

    private fun findInHeaders(call: ApplicationCall): Long? =
        parse(call.request.header("X-Club-Id")) ?: parse(call.request.header("club_id"))

    private suspend fun findInBody(call: ApplicationCall): Long? {
        if (call.request.httpMethod !in bodyMethods) return null
        val contentType = call.request.contentType()
        return when {
            contentType.match(ContentType.Application.Json) -> parseJsonBody(call)
            contentType.match(ContentType.Application.FormUrlEncoded) -> {
                val parameters = call.receiveParameters()
                parse(parameters["clubId"]) ?: parse(parameters["club_id"])
            }
            else -> null
        }
    }

    private suspend fun parseJsonBody(call: ApplicationCall): Long? {
        val text = runCatching { call.receiveText() }.getOrNull()
        val root = if (text.isNullOrBlank()) null else runCatching { jsonParser.parseToJsonElement(text) }.getOrNull()
        return root?.let { findInJson(it) }
    }

    private fun findInJson(element: JsonElement): Long? {
        return when (element) {
            is JsonObject -> {
                bodyKeys.firstNotNullOfOrNull { key -> element[key]?.let { toLong(it) } }
                    ?: element.values.firstNotNullOfOrNull { findInJson(it) }
            }
            is JsonArray -> element.firstNotNullOfOrNull { findInJson(it) }
            else -> null
        }
    }

    private fun toLong(element: JsonElement): Long? {
        if (element is JsonNull) return null
        val primitive = element.jsonPrimitive
        return if (primitive.isString) parse(primitive.content) else primitive.safeLongOrNull()
    }

    private fun parse(value: String?): Long? = value?.toLongOrNull()

    companion object {
        val CLUB_ID_ATTRIBUTE: AttributeKey<Long> = AttributeKey("rbac.club.id")
        private val bodyMethods = setOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch, HttpMethod.Delete)
    }
}

private fun JsonPrimitive.safeLongOrNull(): Long? =
    try {
        this.long
    } catch (_: Throwable) {
        this.content.toLongOrNull()
    }

fun ApplicationCall.rbacContext(): RbacContext {
    val resolution = attributes[rbacResolutionKey]
    return when (resolution) {
        is RbacResolution.Success ->
            RbacContext(
                principal = resolution.principal,
                user = resolution.user,
                roles = resolution.roles,
                clubIds = resolution.clubIds,
            )
        is RbacResolution.Failure -> error("RBAC resolution failure: ${resolution.reason}")
    }
}
