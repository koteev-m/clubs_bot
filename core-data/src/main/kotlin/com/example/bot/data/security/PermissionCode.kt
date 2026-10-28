package com.example.bot.data.security

/** Stable identifier for an explicitly persisted permission. */
@JvmInline
value class PermissionCode(
    val value: String,
) {
    init {
        require(PERMISSION_CODE_PATTERN.matches(value)) { "Invalid permission code" }
    }

    private companion object {
        val PERMISSION_CODE_PATTERN = Regex("[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*)+")
    }
}

/** Canonical explicitly persisted permissions; client grants remain separate from legacy RBAC. */
object PermissionCodes {
    val CLIENT_CLUB_READ = PermissionCode("client.club.read")
    val SUPPORT_VIEW = PermissionCode("support.view")
    val SUPPORT_REPLY = PermissionCode("support.reply")
    val SUPPORT_STATUS_MANAGE = PermissionCode("support.status.manage")
}
