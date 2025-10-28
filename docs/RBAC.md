# RBAC Plugin

The `RbacPlugin` from `core-security` provides HTTP-level role-based access control for the bot.
It resolves the current Telegram principal, loads roles and club scopes from the database, and
emits audit log records for every allow/deny decision.

## Installation

```kotlin
install(RbacPlugin) {
    userRepository = ExposedUserRepository(database)
    userRoleRepository = ExposedUserRoleRepository(database)
    auditLogRepository = AuditLogRepository(database)
    principalExtractor = { call ->
        call.request.header("X-Telegram-Id")?.toLongOrNull()?.let { id ->
            TelegramPrincipal(id, call.request.header("X-Telegram-Username"))
        }
    }
}
```

* `userRepository` resolves `users.telegram_user_id` → internal `users.id`.
* `userRoleRepository` loads user roles and club scope ids (`user_roles`).
* `auditLogRepository` writes to `audit_log` (`result = access_granted/access_denied`).
* `principalExtractor` can reuse any authentication mechanism (default is `call.principal()`).

`RbacPlugin` automatically enables `DoubleReceive` to safely inspect the request body when
extracting club identifiers.

## DSL

Two extension functions protect routes:

```kotlin
routing {
    authorize(Role.MANAGER, Role.CLUB_ADMIN) {
        clubScoped(ClubScope.Own) {
            get("/clubs/{clubId}/bookings") { ... }
        }
    }
    authorize(Role.OWNER, Role.GLOBAL_ADMIN) {
        get("/api/admin/overview") { ... }
    }
}
```

* `authorize(vararg roles)` ensures the caller has one of the specified `Role` values.
* `clubScoped(ClubScope)` enforces club restrictions:
  * `ClubScope.Own` – club id must belong to the user (or the user holds a global role such as
    `OWNER`, `GLOBAL_ADMIN`, `HEAD_MANAGER`).
  * `ClubScope.Any` – allows only global roles regardless of club id.

The plugin stores decision data in MDC and audit-log metadata; if an `Idempotency-Key` header or
`idempotency_key` query parameter is present, it is also placed into the MDC.

## Club Id Extraction

`ClubScopeResolver` tries to resolve the target club id from:

1. Path parameters (`{clubId}` or `{club_id}`).
2. Query parameters (`?clubId=...` or `?club_id=...`).
3. Headers (`X-Club-Id`).
4. JSON or form body (`clubId` / `club_id`), using double-read support.

The resolver caches the detected value per call to avoid extra parsing.

## Audit Logging

Every decision writes a record to `audit_log` with:

* `action = http_access`
* `resource = request.path`
* `result = access_granted | access_denied`
* `club_id`, `user_id`, `ip`, and `reason` (stored in `meta.reason`).

Denied requests log immediately; successful requests log after all checks pass.

## Testing

Unit tests (`core-security:RbacPluginTest`) cover 401/403/200 scenarios using the DSL and audit
logging hooks. Integration tests (`app-bot:RbacIntegrationTest`) run a real Ktor application with
Testcontainers + Flyway migrations and verify RBAC decisions alongside audit log persistence.
