package com.example.bot.data.security

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

internal object UsersTable : Table("users") {
    val id = long("id").autoIncrement()
    val telegramUserId = long("telegram_user_id")
    val username = text("username").nullable()
    val displayName = text("display_name").nullable()
    val phoneE164 = text("phone_e164").nullable()
    val encryptedPhone = text("encrypted_phone").nullable()
    val phoneHash = varchar("phone_hash", 64).nullable()
    val anonymizedAt = timestampWithTimeZone("anonymized_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

internal object UserRolesTable : Table("user_roles") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val roleCode = text("role_code")
    val scopeType = text("scope_type")
    val scopeClubId = long("scope_club_id").nullable()

    override val primaryKey = PrimaryKey(id)
}
