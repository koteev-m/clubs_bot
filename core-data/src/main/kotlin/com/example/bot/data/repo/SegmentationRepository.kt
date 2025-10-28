package com.example.bot.data.repo

import com.example.bot.notifications.SegmentId
import com.example.bot.notifications.SegmentNode
import com.example.bot.notifications.UserId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder.between
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.exists
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.not
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate

/**
 * Repository resolving audience segments into user id sequences.
 */
class SegmentationRepository(private val db: Database) {
    object Users : Table("users") {
        val id = long("id").autoIncrement()
        val clubId = integer("club_id")
        val optIn = bool("opt_in")
        val lang = varchar("lang", 10)
        val lastVisit = date("last_visit")
        val isPromoter = bool("is_promoter")
        val isVip = bool("is_vip")
        val noShows = integer("no_shows")
        override val primaryKey = PrimaryKey(id)
    }

    object Bookings : Table("bookings") {
        val id = long("id").autoIncrement()
        val userId = long("user_id") references Users.id
        val date = date("booking_date")
        override val primaryKey = PrimaryKey(id)
    }

    object Segments : Table("segments") {
        val id = long("id").autoIncrement()
        val dsl = text("dsl")
        override val primaryKey = PrimaryKey(id)
    }

    private val json = Json

    fun resolveSegment(
        segmentId: SegmentId,
        batchSize: Int = 500,
    ): Sequence<UserId> {
        return sequence {
            val node =
                transaction(db) {
                    Segments
                        .selectAll()
                        .where { Segments.id eq segmentId.value }
                        .single()[Segments.dsl]
                        .let { json.decodeFromString(SegmentNode.serializer(), it) }
                }
            var offset = 0L
            while (true) {
                val batch =
                    transaction(db) {
                        Users
                            .selectAll()
                            .where { buildCondition(node) }
                            .limit(batchSize, offset)
                            .map { it[Users.id] }
                    }
                if (batch.isEmpty()) break
                batch.forEach { yield(UserId(it)) }
                offset += batch.size
            }
        }
    }

    private fun buildCondition(node: SegmentNode): Op<Boolean> {
        return when (node.op.uppercase()) {
            "AND" -> node.items.map { buildCondition(it) }.reduce { acc, op -> acc and op }
            "OR" -> node.items.map { buildCondition(it) }.reduce { acc, op -> acc or op }
            "NOT" -> not(buildCondition(node.items.single()))
            else -> fieldCondition(node)
        }
    }

    private fun fieldCondition(node: SegmentNode): Op<Boolean> {
        val field = node.field ?: error("field required for leaf node")
        return when (field) {
            "club_id" ->
                when (node.op) {
                    "IN" -> Users.clubId.inList(node.args.map { it.jsonPrimitive.int })
                    "=" ->
                        Users.clubId eq
                            node.args
                                .first()
                                .jsonPrimitive.int
                    else -> error("unsupported op ${node.op}")
                }
            "opt_in" ->
                Users.optIn eq
                    node.args
                        .first()
                        .jsonPrimitive.boolean
            "lang" ->
                when (node.op) {
                    "IN" -> Users.lang.inList(node.args.map { it.jsonPrimitive.content })
                    "=" ->
                        Users.lang eq
                            node.args
                                .first()
                                .jsonPrimitive.content
                    else -> error("unsupported op ${node.op}")
                }
            "last_visit_days" -> {
                val days =
                    node.args
                        .first()
                        .jsonPrimitive.int
                val date = LocalDate.now().minusDays(days.toLong())
                when (node.op) {
                    "<=" -> Users.lastVisit greaterEq date
                    ">=" -> Users.lastVisit lessEq date
                    else -> error("unsupported op ${node.op}")
                }
            }
            "is_promoter" ->
                Users.isPromoter eq
                    node.args
                        .first()
                        .jsonPrimitive.boolean
            "is_vip" ->
                Users.isVip eq
                    node.args
                        .first()
                        .jsonPrimitive.boolean
            "has_bookings_between" -> {
                val start = LocalDate.parse(node.args[0].jsonPrimitive.content)
                val end = LocalDate.parse(node.args[1].jsonPrimitive.content)
                exists(
                    Bookings
                        .selectAll()
                        .where {
                            (Bookings.userId eq Users.id) and Bookings.date.between(start, end)
                        },
                )
            }
            "no_shows_ge" ->
                Users.noShows.greaterEq(
                    node.args
                        .first()
                        .jsonPrimitive.int,
                )
            else -> error("unknown field $field")
        }
    }
}
