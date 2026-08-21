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

/** Canonical operational support permissions. */
object PermissionCodes {
    val SUPPORT_VIEW = PermissionCode("support.view")
    val SUPPORT_REPLY = PermissionCode("support.reply")
    val SUPPORT_STATUS_MANAGE = PermissionCode("support.status.manage")
}
