package com.example.bot.routes

import com.example.bot.audit.AuditLogRepository
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.music.Like
import com.example.bot.music.MusicAsset
import com.example.bot.music.MusicAssetKind
import com.example.bot.music.MusicAssetMeta
import com.example.bot.music.MusicAssetRepository
import com.example.bot.music.MusicBattle
import com.example.bot.music.MusicBattleRepository
import com.example.bot.music.MusicBattleService
import com.example.bot.music.MusicBattleStatus
import com.example.bot.music.MusicBattleVote
import com.example.bot.music.MusicBattleVoteAggregate
import com.example.bot.music.MusicBattleVoteRepository
import com.example.bot.music.MusicItemCreate
import com.example.bot.music.MusicItemRepository
import com.example.bot.music.MusicItemType
import com.example.bot.music.MusicItemUpdate
import com.example.bot.music.MusicItemView
import com.example.bot.music.MusicLikesRepository
import com.example.bot.music.MusicSource
import com.example.bot.music.MusicStemsPackage
import com.example.bot.music.MusicStemsRepository
import com.example.bot.music.MusicVoteUpsertResult
import com.example.bot.music.UserId
import com.example.bot.plugins.TelegramMiniUser
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
import com.example.bot.security.auth.TelegramPrincipal
import com.example.bot.security.rbac.RbacPlugin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MusicBattleRoutesTest {
    private val now = Instant.parse("2024-07-01T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val telegramId = 100L
    private val json = Json

    @Before
    fun setup() {
        System.setProperty("TELEGRAM_BOT_TOKEN", "test")
        overrideMiniAppValidatorForTesting { _, _ -> TelegramMiniUser(id = telegramId) }
    }

    @After
    fun tearDown() {
        resetMiniAppValidator()
    }

    @Test
    fun `vote without auth returns unauthorized`() = withBattleApp { _, _, _ ->
        val response = client.post("/api/music/battles/1/vote") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("{" + "\"chosenItemId\":11}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `vote validates ids and payload`() = withBattleApp { _, _, _ ->
        val badBattle =
            client.post("/api/music/battles/not-a-number/vote") {
                header("X-Telegram-Init-Data", "init")
                header(HttpHeaders.ContentType, "application/json")
                setBody("{" + "\"chosenItemId\":11}")
            }
        assertEquals(HttpStatusCode.BadRequest, badBattle.status)

        val badChoice =
            client.post("/api/music/battles/1/vote") {
                header("X-Telegram-Init-Data", "init")
                header(HttpHeaders.ContentType, "application/json")
                setBody("{" + "\"chosenItemId\":999}")
            }
        assertEquals(HttpStatusCode.BadRequest, badChoice.status)
    }

    @Test
    fun `vote on closed battle returns invalid state`() = withBattleApp { _, _, _ ->
        val response =
            client.post("/api/music/battles/2/vote") {
                header("X-Telegram-Init-Data", "init")
                header(HttpHeaders.ContentType, "application/json")
                setBody("{" + "\"chosenItemId\":11}")
            }
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(response.bodyAsText().contains("invalid_state"))
    }

    @Test
    fun `vote idempotency keeps counts stable for same choice`() = withBattleApp { _, _, _ ->
        val first = vote(1, 11)
        val second = vote(1, 11)

        val firstVotes = json.parseToJsonElement(first.bodyAsText()).jsonObject["votes"]!!.jsonObject
        val secondVotes = json.parseToJsonElement(second.bodyAsText()).jsonObject["votes"]!!.jsonObject
        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals(1, firstVotes["countA"]!!.jsonPrimitive.int)
        assertEquals(1, secondVotes["countA"]!!.jsonPrimitive.int)
        assertEquals(11L, secondVotes["myVote"]!!.jsonPrimitive.long)
    }

    @Test
    fun `vote choice change updates aggregates`() = withBattleApp { _, _, _ ->
        vote(1, 11)
        val changed = vote(1, 12)

        val votes = json.parseToJsonElement(changed.bodyAsText()).jsonObject["votes"]!!.jsonObject
        assertEquals(0, votes["countA"]!!.jsonPrimitive.int)
        assertEquals(1, votes["countB"]!!.jsonPrimitive.int)
        assertEquals(12L, votes["myVote"]!!.jsonPrimitive.long)
        assertEquals(0, votes["percentA"]!!.jsonPrimitive.int)
        assertEquals(100, votes["percentB"]!!.jsonPrimitive.int)
    }

    @Test
    fun `fans ranking returns deterministic stats without pii`() = withBattleApp(
        likesAggregation = mapOf(100L to 5, 200L to 3),
        votesAggregation = mapOf(100L to 2, 200L to 4),
    ) { _, _, _ ->
        val response =
            client.get("/api/music/fans/ranking?clubId=10&windowDays=30") {
                header("X-Telegram-Init-Data", "init")
            }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(setOf("myStats", "distribution"), body.keys)
        assertFalse(body.containsKey("userIds"))

        val myStats = body["myStats"]!!.jsonObject
        assertEquals(2, myStats["votesCast"]!!.jsonPrimitive.int)
        assertEquals(5, myStats["likesGiven"]!!.jsonPrimitive.int)
        assertEquals(9, myStats["points"]!!.jsonPrimitive.int)
        assertEquals(2, myStats["rank"]!!.jsonPrimitive.int)

        val distribution = body["distribution"]!!.jsonObject
        val topPoints = distribution["topPoints"] as JsonArray
        assertEquals(listOf(11, 9), topPoints.map { it.jsonPrimitive.int })
    }

    @Test
    fun `vote same choice after closed is ok`() = withBattleApp { _, voteRepo, _ ->
        voteRepo as StubVoteRepository
        voteRepo.seedVote(
            battleId = 2,
            userId = telegramId,
            chosenItemId = 11,
            votedAt = now.minusSeconds(1800),
        )

        val response = vote(2, 11)

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `battle details with unpublished item returns not found`() = withBattleApp(
        itemsRepo = StubItemsRepository(unpublishedItemIds = setOf(11L)),
    ) { _, _, _ ->
        val response =
            client.get("/api/music/battles/1") {
                header("X-Telegram-Init-Data", "init")
            }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("not_found"))
    }

    @Test
    fun `stems endpoint allows owner and denies guest`() = withBattleApp(roles = setOf(Role.GUEST)) { _, _, _ ->
        val denied =
            client.get("/api/music/items/11/stems") {
                header("X-Telegram-Init-Data", "init")
            }
        assertEquals(HttpStatusCode.Forbidden, denied.status)
    }

    @Test
    fun `stems endpoint returns asset for owner`() = withBattleApp(roles = setOf(Role.OWNER)) { _, _, _ ->
        val response =
            client.get("/api/music/items/11/stems") {
                header("X-Telegram-Init-Data", "init")
            }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue((response.headers["Content-Type"] ?: "").startsWith("application/zip"))
    }

    private suspend fun ApplicationTestBuilder.vote(battleId: Long, chosenItemId: Long) =
        client.post("/api/music/battles/$battleId/vote") {
            header("X-Telegram-Init-Data", "init")
            header(HttpHeaders.ContentType, "application/json")
            setBody("{" + "\"chosenItemId\":$chosenItemId}")
        }

    private fun withBattleApp(
        roles: Set<Role> = setOf(Role.GUEST),
        itemsRepo: MusicItemRepository = StubItemsRepository(),
        likesAggregation: Map<Long, Int> = emptyMap(),
        votesAggregation: Map<Long, Int> = emptyMap(),
        block: suspend ApplicationTestBuilder.(MusicBattleRepository, MusicBattleVoteRepository, MusicStemsRepository) -> Unit,
    ) = testApplication {
        val battleRepo = StubBattleRepository(now)
        val voteRepo = StubVoteRepository(battleRepo, now, votesAggregation)
        val likesRepo = StubLikesRepository(likesAggregation)
        val stemsRepo = StubStemsRepository()
        val assetsRepo = StubAssetsRepository()
        val service = MusicBattleService(battleRepo, voteRepo, itemsRepo, likesRepo, clock)

        application {
            install(ContentNegotiation) { json() }
            install(RbacPlugin) {
                userRepository = StubUserRepository()
                userRoleRepository = StubUserRoleRepository(roles)
                auditLogRepository = relaxedAuditRepository()
                principalExtractor = { TelegramPrincipal(telegramId, "tester") }
            }
            musicBattleRoutes(
                battleService = service,
                itemsRepository = itemsRepo,
                stemsRepository = stemsRepo,
                assetsRepository = assetsRepo,
                botTokenProvider = { "test" },
            )
        }
        block(battleRepo, voteRepo, stemsRepo)
    }

    private fun relaxedAuditRepository(): AuditLogRepository = io.mockk.mockk(relaxed = true)

    private class StubUserRepository : UserRepository {
        override suspend fun getByTelegramId(id: Long): User? = User(id = id, telegramId = id, username = "tester")
        override suspend fun getById(id: Long): User? = User(id = id, telegramId = id, username = "tester")
    }

    private class StubUserRoleRepository(private val roles: Set<Role>) : UserRoleRepository {
        override suspend fun listRoles(userId: Long): Set<Role> = roles
        override suspend fun listClubIdsFor(userId: Long): Set<Long> = emptySet()
    }

    private class StubBattleRepository(now: Instant) : MusicBattleRepository {
        private val battles =
            mutableMapOf(
                1L to MusicBattle(1, 10, 11, 12, MusicBattleStatus.ACTIVE, now.minusSeconds(3600), now.plusSeconds(3600), now, now),
                2L to MusicBattle(2, 10, 11, 12, MusicBattleStatus.CLOSED, now.minusSeconds(7200), now.minusSeconds(300), now, now),
            )

        override suspend fun create(
            clubId: Long?,
            itemAId: Long,
            itemBId: Long,
            status: MusicBattleStatus,
            startsAt: Instant,
            endsAt: Instant,
        ): MusicBattle = throw UnsupportedOperationException()

        override suspend fun getById(id: Long): MusicBattle? = battles[id]

        override suspend fun findCurrentActive(clubId: Long?, now: Instant): MusicBattle? =
            battles.values.firstOrNull { it.clubId == clubId && it.status == MusicBattleStatus.ACTIVE && !now.isBefore(it.startsAt) && now.isBefore(it.endsAt) }

        override suspend fun listRecent(clubId: Long?, limit: Int, offset: Int): List<MusicBattle> =
            battles.values.filter { it.clubId == clubId }.sortedByDescending { it.startsAt }.drop(offset).take(limit)

        override suspend fun setStatus(id: Long, status: MusicBattleStatus, updatedAt: Instant): Boolean = false
    }

    private class StubVoteRepository(
        private val battles: MusicBattleRepository,
        private val now: Instant,
        private val aggregateVotesByUser: Map<Long, Int>,
    ) : MusicBattleVoteRepository {
        private val votes = mutableMapOf<Pair<Long, Long>, MusicBattleVote>()

        override suspend fun upsertVote(battleId: Long, userId: Long, chosenItemId: Long, now: Instant): MusicVoteUpsertResult {
            val battle = battles.getById(battleId) ?: throw IllegalArgumentException("Battle $battleId not found")
            val key = battleId to userId
            val existing = votes[key]
            if (existing?.chosenItemId == chosenItemId) return MusicVoteUpsertResult.UNCHANGED

            val isActive =
                battle.status == MusicBattleStatus.ACTIVE &&
                    !this.now.isBefore(battle.startsAt) &&
                    this.now.isBefore(battle.endsAt)
            if (!isActive) throw IllegalStateException("Battle $battleId is not open for vote changes")

            votes[key] = MusicBattleVote(battleId, userId, chosenItemId, now)
            return if (existing == null) MusicVoteUpsertResult.CREATED else MusicVoteUpsertResult.UPDATED
        }

        fun seedVote(battleId: Long, userId: Long, chosenItemId: Long, votedAt: Instant) {
            votes[battleId to userId] = MusicBattleVote(battleId, userId, chosenItemId, votedAt)
        }

        override suspend fun findUserVote(battleId: Long, userId: Long): MusicBattleVote? = votes[battleId to userId]

        override suspend fun aggregateVotes(battleId: Long): MusicBattleVoteAggregate? {
            val battle = battles.getById(battleId) ?: return null
            val battleVotes = votes.values.filter { it.battleId == battleId }
            return MusicBattleVoteAggregate(
                battleId = battleId,
                itemAId = battle.itemAId,
                itemBId = battle.itemBId,
                itemAVotes = battleVotes.count { it.chosenItemId == battle.itemAId },
                itemBVotes = battleVotes.count { it.chosenItemId == battle.itemBId },
            )
        }

        override suspend fun aggregateUserVotesSince(clubId: Long, since: Instant): Map<Long, Int> =
            if (aggregateVotesByUser.isNotEmpty()) aggregateVotesByUser else votes.values.filter { !it.votedAt.isBefore(since) }.groupingBy { it.userId }.eachCount()
    }

    private class StubItemsRepository(
        private val unpublishedItemIds: Set<Long> = emptySet(),
        private val nonSetItemIds: Set<Long> = emptySet(),
        private val clubOverrides: Map<Long, Long?> = emptyMap(),
    ) : MusicItemRepository {
        private val itemA =
            MusicItemView(
                id = 11,
                clubId = 10,
                title = "Set A",
                dj = "DJ A",
                description = null,
                itemType = MusicItemType.SET,
                source = MusicSource.FILE,
                sourceUrl = null,
                audioAssetId = null,
                telegramFileId = null,
                durationSec = 120,
                coverUrl = null,
                coverAssetId = null,
                tags = emptyList(),
                publishedAt = Instant.parse("2024-06-01T00:00:00Z"),
            )
        private val itemB = itemA.copy(id = 12, title = "Set B", dj = "DJ B")

        private fun adjusted(item: MusicItemView): MusicItemView {
            val publishedAt = if (item.id in unpublishedItemIds) null else item.publishedAt
            val itemType = if (item.id in nonSetItemIds) MusicItemType.TRACK else item.itemType
            val clubId = clubOverrides[item.id] ?: item.clubId
            return item.copy(publishedAt = publishedAt, itemType = itemType, clubId = clubId)
        }

        override suspend fun create(req: MusicItemCreate, actor: UserId): MusicItemView = throw UnsupportedOperationException()
        override suspend fun update(id: Long, req: MusicItemUpdate, actor: UserId): MusicItemView? = null
        override suspend fun setPublished(id: Long, publishedAt: Instant?, actor: UserId): MusicItemView? = null
        override suspend fun attachAudioAsset(id: Long, assetId: Long, actor: UserId): MusicItemView? = null
        override suspend fun attachCoverAsset(id: Long, assetId: Long, actor: UserId): MusicItemView? = null
        override suspend fun getById(id: Long): MusicItemView? =
            when (id) {
                11L -> adjusted(itemA)
                12L -> adjusted(itemB)
                else -> null
            }
        override suspend fun findByIds(ids: List<Long>): List<MusicItemView> = ids.mapNotNull { getById(it) }
        override suspend fun listActive(clubId: Long?, limit: Int, offset: Int, tag: String?, q: String?, type: MusicItemType?): List<MusicItemView> = emptyList()
        override suspend fun listAll(clubId: Long?, limit: Int, offset: Int, type: MusicItemType?): List<MusicItemView> = emptyList()
        override suspend fun lastUpdatedAt(): Instant? = null
    }

    private class StubLikesRepository(
        private val aggregateLikesByUser: Map<Long, Int> = emptyMap(),
    ) : MusicLikesRepository {
        override suspend fun like(userId: Long, itemId: Long, now: Instant): Boolean = false
        override suspend fun unlike(userId: Long, itemId: Long): Boolean = false
        override suspend fun findUserLikesSince(userId: Long, since: Instant): List<Like> = emptyList()
        override suspend fun findAllLikesSince(since: Instant): List<Like> = emptyList()
        override suspend fun aggregateUserLikesSince(clubId: Long, since: Instant): Map<Long, Int> = aggregateLikesByUser
        override suspend fun find(userId: Long, itemId: Long): Like? = null
        override suspend fun countsForItems(itemIds: Collection<Long>): Map<Long, Int> = emptyMap()
        override suspend fun likedItemsForUser(userId: Long, itemIds: Collection<Long>): Set<Long> = emptySet()
    }

    private class StubStemsRepository : MusicStemsRepository {
        override suspend fun linkStemAsset(itemId: Long, assetId: Long, now: Instant): MusicStemsPackage = throw UnsupportedOperationException()
        override suspend fun unlinkStemAsset(itemId: Long): Boolean = false
        override suspend fun getStemAsset(itemId: Long): MusicStemsPackage? = if (itemId == 11L) MusicStemsPackage(11, 700, Instant.EPOCH, Instant.EPOCH) else null
    }

    private class StubAssetsRepository : MusicAssetRepository {
        override suspend fun createAsset(kind: MusicAssetKind, bytes: ByteArray, contentType: String, sha256: String, sizeBytes: Long): MusicAsset = throw UnsupportedOperationException()

        override suspend fun getAsset(id: Long): MusicAsset? =
            if (id == 700L) {
                MusicAsset(700, MusicAssetKind.AUDIO, "zip".toByteArray(), "application/zip", "abc", 3, Instant.EPOCH, Instant.EPOCH)
            } else {
                null
            }

        override suspend fun getAssetMeta(id: Long): MusicAssetMeta? =
            if (id == 700L) {
                MusicAssetMeta(700, MusicAssetKind.AUDIO, "application/zip", "abc", 3, Instant.EPOCH)
            } else {
                null
            }
    }
}
