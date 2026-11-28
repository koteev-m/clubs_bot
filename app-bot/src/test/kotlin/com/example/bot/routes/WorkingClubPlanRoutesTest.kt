package com.example.bot.routes

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.imageio.ImageIO
import javax.sql.DataSource
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

open class WorkingClubPlanRoutesTest : ClientManagerRoutesFixture() {
    @Test
    fun `working plan binds saved image to hall revision without granting layout rights`() =
        database { source ->
            withApp(source) {
                val (club, neighbor) = business(source)
                val base = "$C/clubs/$club"
                claim(invite("$base/managers", RECIPIENT, "CLUB_MANAGER"), SECOND_MANAGER)
                claim(invite("$base/managers", SECOND_MANAGER, "FLOOR_MANAGER"), THIRD_MANAGER)
                val hall =
                    ok("$base/halls", SECOND_MANAGER, obj("name" to "Main"))["halls"]!!
                        .jsonArray
                        .single()
                        .jsonObject
                        .string("id")
                val image = png(0x4d7d90)
                upload("$base/halls/$hall/plan", image, "1")
                val deskHall = ok("$base/operations", THIRD_MANAGER)["halls"]!!.jsonArray.single().jsonObject
                val reference = deskHall.getValue("plan").jsonObject
                val revision = reference.string("layoutRevision")
                val digest = reference.string("sha256")
                val path = "$base/operations/halls/$hall/plan/$revision/$digest"
                val plan = requestAs(path, THIRD_MANAGER)
                assertEquals(HttpStatusCode.OK, plan.status)
                assertEquals("no-store", plan.headers["Cache-Control"])
                assertContentEquals(image, plan.bodyAsBytes())
                assertEquals(HttpStatusCode.NotFound, requestAs("$base/halls/$hall/plan", THIRD_MANAGER).status)
                assertEquals(HttpStatusCode.NotFound, requestAs(path, FOREIGN).status)
                assertEquals(
                    HttpStatusCode.NotFound,
                    requestAs("$C/clubs/$neighbor/operations/halls/$hall/plan/$revision/$digest", THIRD_MANAGER).status,
                )
                upload("$base/halls/$hall/plan", png(0x5f8840), revision)
                assertEquals(HttpStatusCode.Conflict, requestAs(path, THIRD_MANAGER).status)
                val next =
                    ok("$base/operations", THIRD_MANAGER)["halls"]!!
                        .jsonArray
                        .single()
                        .jsonObject
                        .getValue("plan")
                        .jsonObject
                val nextRevision = next.string("layoutRevision")
                val nextDigest = next.string("sha256")
                val nextPath = "$base/operations/halls/$hall/plan/$nextRevision/$nextDigest"
                assertEquals(HttpStatusCode.OK, requestAs(nextPath, THIRD_MANAGER).status)
                val roster = ok("$base/managers", SECOND_MANAGER)
                val assignment = roster["assignments"]!!.jsonArray.single().jsonObject
                ok("$base/managers", SECOND_MANAGER, revokeSource(roster, assignment, "revoke_assignment"))
                assertEquals(HttpStatusCode.NotFound, requestAs(nextPath, THIRD_MANAGER).status)
            }
        }

    private suspend fun ApplicationTestBuilder.upload(
        path: String,
        image: ByteArray,
        revision: String,
    ) {
        val result =
            client.post(path) {
                header("Content-Type", "image/png")
                header("X-Layout-Version", revision)
                header("Idempotency-Key", UUID.randomUUID().toString())
                header("X-Telegram-Init-Data", signed(SECOND_MANAGER))
                setBody(image)
            }
        assertEquals(HttpStatusCode.OK, result.status)
    }

    private fun png(color: Int): ByteArray {
        val image = BufferedImage(24, 12, BufferedImage.TYPE_INT_RGB)
        image.setRGB(4, 4, color)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }
}

@Tag("it")
class WorkingClubPlanPostgresIT : WorkingClubPlanRoutesTest() {
    override fun databaseAt(
        target: String?,
        block: (DataSource) -> Unit,
    ) = withClientManagerPostgres(target, block)
}
