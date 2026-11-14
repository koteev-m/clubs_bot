package com.example.bot.routes

import com.example.bot.http.ErrorCodes
import com.example.bot.http.MINI_APP_VARY_HEADER
import com.example.bot.http.NO_STORE_CACHE_CONTROL
import com.example.bot.http.respondError
import com.example.bot.plugins.installJsonErrorPages
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.MultiPartData
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.PartData
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.requestsize.RequestTooLargeException
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MusicUploadRequestLimitRaceTest {
    @get:Rule
    val deadline: Timeout = Timeout.seconds(20)

    @Test
    fun `failed part body is handled before late parser failure can truncate response`() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { parserDispatcher ->
            testApplication {
                val before = tempFiles()
                val bodyClosed = CompletableDeferred<Unit>()
                val releaseFailure = CompletableDeferred<Unit>()
                val producerStopped = CountDownLatch(1)
                val persisted = AtomicInteger()
                val failure = RequestTooLargeException(16)
                val body = ByteChannel()

                application {
                    install(ContentNegotiation) { json() }
                    installJsonErrorPages()
                    sendPipeline.intercept(ApplicationSendPipeline.After) {
                        val content = subject
                        if (call.response.status() == HttpStatusCode.BadRequest &&
                            content is OutgoingContent.ByteArrayContent
                        ) {
                            // Ktor's multipart producer publishes body.close(cause) before
                            // throwing into its parent scope. Force the late throw after
                            // an erroneous empty-file response reaches header commitment.
                            proceedWith(
                                object : OutgoingContent.ByteArrayContent() {
                                    override val contentType = content.contentType
                                    override val status = content.status
                                    override val headers = content.headers
                                    override val contentLength: Long?
                                        get() {
                                            releaseFailure.complete(Unit)
                                            check(producerStopped.await(2, TimeUnit.SECONDS))
                                            return content.contentLength
                                        }

                                    override fun bytes(): ByteArray = content.bytes()
                                },
                            )
                        }
                    }
                    routing {
                        put("/api/music-upload-limit-race") {
                            coroutineScope {
                                val producer =
                                    launch(parserDispatcher) {
                                        body.cancel(failure)
                                        bodyClosed.complete(Unit)
                                        releaseFailure.await()
                                        throw failure
                                    }
                                producer.invokeOnCompletion { producerStopped.countDown() }
                                bodyClosed.await()
                                val multipart = failedPart(body)
                                val result =
                                    receiveMusicUpload(multipart, ByteChannel(), 16, setOf(ContentType.Image.PNG))
                                when (result) {
                                    is MusicUploadResult.Error ->
                                        call.respondError(result.status, result.code, details = result.details)
                                    is MusicUploadResult.Ok -> {
                                        persisted.incrementAndGet()
                                        deleteMusicTempFile(result.tempFile)
                                        error("failed request must not persist an upload")
                                    }
                                }
                            }
                        }
                    }
                }

                val response = client.put("/api/music-upload-limit-race")
                val text = response.bodyAsText()
                assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
                val error = Json.parseToJsonElement(text).jsonObject
                assertEquals(ErrorCodes.payload_too_large, error["code"]?.jsonPrimitive?.content)
                assertEquals("413", error["status"]?.jsonPrimitive?.content)
                assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
                assertEquals(text.encodeToByteArray().size.toString(), response.headers[HttpHeaders.ContentLength])
                assertEquals(NO_STORE_CACHE_CONTROL, response.headers[HttpHeaders.CacheControl])
                assertEquals(MINI_APP_VARY_HEADER, response.headers[HttpHeaders.Vary])
                assertTrue(producerStopped.await(2, TimeUnit.SECONDS))
                assertEquals(0, persisted.get())
                assertEquals(before, tempFiles())
            }
        }
    }

    private fun failedPart(body: ByteChannel): MultiPartData =
        object : MultiPartData {
            private var delivered = false

            override suspend fun readPart(): PartData? {
                if (delivered) return null
                delivered = true
                return PartData.FileItem(
                    { body },
                    { check(body.isClosedForRead) },
                    Headers.build {
                        append(HttpHeaders.ContentType, ContentType.Image.PNG.toString())
                        append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"cover.png\"")
                    },
                )
            }
        }

    private fun tempFiles(): Set<String> =
        Files.newDirectoryStream(Path.of(System.getProperty("java.io.tmpdir")), "music-upload-*.bin").use { files ->
            files.map { it.fileName.toString() }.toSet()
        }
}
