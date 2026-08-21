package com.example.bot.data.security

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

internal object PermissionsTable : Table("permissions") {
    val code = text("code")

    override val primaryKey = PrimaryKey(code)
}

internal object UserRolePermissionsTable : Table("user_role_permissions") {
    val userRoleId =
        long("user_role_id").references(
            ref = UserRolesTable.id,
            onDelete = ReferenceOption.CASCADE,
        )
    val permissionCode = text("permission_code").references(PermissionsTable.code)

    override val primaryKey = PrimaryKey(userRoleId, permissionCode)
}
