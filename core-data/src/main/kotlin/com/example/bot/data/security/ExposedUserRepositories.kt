package com.example.bot.data.security

import com.example.bot.data.db.withRetriedTx
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.sql.Connection

/**
 * Exposed implementation of [UserRepository].
 */
class ExposedUserRepository(
    private val db: Database,
) : UserRepository,
    UserIdentityProvisioner {
    override suspend fun getByTelegramId(id: Long): User? =
        newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            UsersTable
                .selectAll()
                .where { UsersTable.telegramUserId eq id }
                .limit(1)
                .firstOrNull()
                ?.toUser()
        }

    override suspend fun getById(id: Long): User? =
        newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            UsersTable
                .selectAll()
                .where { UsersTable.id eq id }
                .limit(1)
                .firstOrNull()
                ?.toUser()
        }

    override suspend fun ensureMinimalIdentity(telegramUserId: Long): User {
        require(telegramUserId > 0) { "telegramUserId must be positive" }
        return withRetriedTx(
            name = "user.identity.ensure",
            database = db,
            manageTransaction = false,
        ) {
            newSuspendedTransaction(
                context = Dispatchers.IO,
                db = db,
                transactionIsolation = Connection.TRANSACTION_READ_COMMITTED,
            ) {
                repetitionAttempts = 1
                UsersTable.insertIgnore {
                    it[UsersTable.telegramUserId] = telegramUserId
                }
                UsersTable
                    .selectAll()
                    .where { UsersTable.telegramUserId eq telegramUserId }
                    .limit(1)
                    .firstOrNull()
                    ?.toUser()
                    ?: error("Minimal user identity was not found after ensure")
            }
        }
    }
}

/**
 * Exposed implementation of [UserRoleRepository].
 */
class ExposedUserRoleRepository(
    private val db: Database,
) : UserRoleRepository {
    override suspend fun listRoles(userId: Long): Set<Role> =
        newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            UserRolesTable
                .selectAll()
                .where { UserRolesTable.userId eq userId }
                .mapTo(mutableSetOf()) { row ->
                    Role.valueOf(row[UserRolesTable.roleCode])
                }
        }

    override suspend fun listClubIdsFor(userId: Long): Set<Long> =
        newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            UserRolesTable
                .selectAll()
                .where {
                    (UserRolesTable.userId eq userId) and
                        (UserRolesTable.scopeType eq "CLUB") and
                        UserRolesTable.scopeClubId.isNotNull()
                }.mapNotNullTo(mutableSetOf()) { row ->
                    row[UserRolesTable.scopeClubId]
                }
        }
}

private fun ResultRow.toUser(): User =
    User(
        id = this[UsersTable.id],
        telegramId = this[UsersTable.telegramUserId],
        username = this[UsersTable.username],
    )
