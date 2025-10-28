package com.example.bot.data

import com.example.bot.data.repo.SegmentationRepository
import com.example.bot.notifications.SegmentId
import com.example.bot.notifications.SegmentNode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate

class SegmentationTest {
    private lateinit var db: Database
    private lateinit var repo: SegmentationRepository

    @BeforeEach
    fun setup() {
        db = Database.connect("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")
        repo = SegmentationRepository(db)
        transaction(db) {
            SchemaUtils.createMissingTablesAndColumns(
                SegmentationRepository.Users,
                SegmentationRepository.Bookings,
                SegmentationRepository.Segments,
            )

            // users
            SegmentationRepository.Users.insert {
                it[id] = 1
                it[clubId] = 1
                it[optIn] = true
                it[lang] = "ru"
                it[lastVisit] = LocalDate.now().minusDays(10)
                it[isPromoter] = true
                it[isVip] = false
                it[noShows] = 1
            }
            SegmentationRepository.Users.insert {
                it[id] = 2
                it[clubId] = 2
                it[optIn] = true
                it[lang] = "en"
                it[lastVisit] = LocalDate.now().minusDays(30)
                it[isPromoter] = false
                it[isVip] = true
                it[noShows] = 0
            }
            SegmentationRepository.Users.insert {
                it[id] = 3
                it[clubId] = 3
                it[optIn] = false
                it[lang] = "ru"
                it[lastVisit] = LocalDate.now().minusDays(90)
                it[isPromoter] = false
                it[isVip] = false
                it[noShows] = 5
            }

            // bookings
            SegmentationRepository.Bookings.insert {
                it[userId] = 1
                it[date] = LocalDate.now().minusDays(5)
            }
            SegmentationRepository.Bookings.insert {
                it[userId] = 2
                it[date] = LocalDate.now().minusDays(20)
            }
        }
    }

    @AfterEach
    fun tearDown() {
        transaction(db) {
            SchemaUtils.drop(
                SegmentationRepository.Segments,
                SegmentationRepository.Bookings,
                SegmentationRepository.Users,
            )
        }
    }

    @Test
    fun `resolve complex segment`() {
        val segment =
            SegmentNode(
                op = "AND",
                items =
                    listOf(
                        SegmentNode(
                            field = "club_id",
                            op = "IN",
                            args = listOf(Json.encodeToJsonElement(1), Json.encodeToJsonElement(2)),
                        ),
                        SegmentNode(field = "opt_in", op = "=", args = listOf(Json.encodeToJsonElement(true))),
                        SegmentNode(
                            field = "lang",
                            op = "IN",
                            args = listOf(Json.encodeToJsonElement("ru"), Json.encodeToJsonElement("en")),
                        ),
                        SegmentNode(field = "last_visit_days", op = "<=", args = listOf(Json.encodeToJsonElement(60))),
                        SegmentNode(field = "is_promoter", op = "=", args = listOf(Json.encodeToJsonElement(true))),
                        SegmentNode(field = "is_vip", op = "=", args = listOf(Json.encodeToJsonElement(false))),
                        SegmentNode(
                            field = "has_bookings_between",
                            op = "BETWEEN",
                            args =
                                listOf(
                                    Json.encodeToJsonElement(LocalDate.now().minusDays(30).toString()),
                                    Json.encodeToJsonElement(LocalDate.now().plusDays(1).toString()),
                                ),
                        ),
                        SegmentNode(field = "no_shows_ge", op = ">=", args = listOf(Json.encodeToJsonElement(1))),
                    ),
            )

        val id =
            transaction(db) {
                SegmentationRepository.Segments.insert { it[dsl] = Json.encodeToString(segment) } get
                    SegmentationRepository.Segments.id
            }

        val ids = repo.resolveSegment(SegmentId(id)).map { it.value }.toList()
        assertEquals(listOf(1L), ids)
    }

    @Test
    fun `resolve OR and NOT`() {
        val segment =
            SegmentNode(
                op = "AND",
                items =
                    listOf(
                        SegmentNode(
                            op = "OR",
                            items =
                                listOf(
                                    SegmentNode(
                                        field = "club_id",
                                        op = "=",
                                        args = listOf(Json.encodeToJsonElement(1)),
                                    ),
                                    SegmentNode(
                                        field = "club_id",
                                        op = "=",
                                        args = listOf(Json.encodeToJsonElement(2)),
                                    ),
                                ),
                        ),
                        SegmentNode(
                            op = "NOT",
                            items =
                                listOf(
                                    SegmentNode(
                                        field = "is_vip",
                                        op = "=",
                                        args = listOf(Json.encodeToJsonElement(true)),
                                    ),
                                ),
                        ),
                    ),
            )

        val id =
            transaction(db) {
                SegmentationRepository.Segments.insert { it[dsl] = Json.encodeToString(segment) } get
                    SegmentationRepository.Segments.id
            }

        val ids = repo.resolveSegment(SegmentId(id)).map { it.value }.toSet()
        assertEquals(setOf(1L), ids)
    }
}
