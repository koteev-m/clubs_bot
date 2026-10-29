package com.example.bot.routes

import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.MultiPartData
import io.ktor.http.content.PartData
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.IOException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MusicUploadLifecycleTest {
    @get:Rule
    val deadline: Timeout = Timeout.seconds(20)

    @Test
    fun `late multipart failure removes already saved file`() =
        runBlocking {
            val before = tempFiles()
            val failure = IOException("synthetic later part failure")
            val parts = Parts(file(ByteReadChannel(byteArrayOf(1))), after = { throw failure })
            val thrown = assertFailsWith<IOException> { receive(parts) }
            assertSame(failure, thrown)
            assertEquals(1, parts.disposed)
            assertEquals(before, tempFiles())
        }

    @Test
    fun `early overflow cancels live producer before part disposal`() =
        runBlocking {
            withTimeout(5_000) {
                val before = tempFiles()
                val body = ByteChannel(autoFlush = true)
                val stopped = CompletableDeferred<Unit>()
                val producer =
                    launch {
                        try {
                            while (true) body.writeFully(ByteArray(32))
                        } finally {
                            stopped.complete(Unit)
                        }
                    }
                val parts = Parts(file(body))
                val result = receive(parts, limit = 16)
                assertEquals(HttpStatusCode.PayloadTooLarge, (result as MusicUploadResult.Error).status)
                stopped.await()
                producer.join()
                assertEquals(1, parts.disposed)
                assertEquals(before, tempFiles())
            }
        }

    @Test
    fun `cancellation while streaming stops producer and deletes temp`() =
        runBlocking {
            withTimeout(5_000) {
                val before = tempFiles()
                val body = ByteChannel(autoFlush = true)
                val received = CompletableDeferred<Unit>()
                val parts = Parts(file(body, opened = { received.complete(Unit) }))
                val request = async { receive(parts) }
                received.await()
                body.writeFully(byteArrayOf(1))
                request.cancelAndJoin()
                assertTrue(request.isCancelled)
                assertTrue(body.isClosedForRead)
                assertEquals(1, parts.disposed)
                assertEquals(before, tempFiles())
            }
        }

    @Test
    fun `fatal provider error is rethrown and part released`() =
        runBlocking {
            val before = tempFiles()
            val fatal = OutOfMemoryError("synthetic fatal error without allocating memory")
            val parts = Parts(file(ByteReadChannel.Empty, opened = { throw fatal }))
            assertSame(fatal, assertFailsWith<OutOfMemoryError> { receive(parts) })
            assertEquals(1, parts.disposed)
            assertEquals(before, tempFiles())
        }

    @Test
    fun `read helper preserves cancellation and fatal failure and cleans temp`() =
        runBlocking {
            val before = tempFiles()
            val cancelled = CancellationException("synthetic cancellation")
            assertSame(
                cancelled,
                assertFailsWith<CancellationException> {
                    readLimitedWithShaToTempFile({ throw cancelled }, 16)
                },
            )
            val fatal = OutOfMemoryError("synthetic fatal error without allocating memory")
            assertSame(
                fatal,
                assertFailsWith<OutOfMemoryError> {
                    readLimitedWithShaToTempFile({ throw fatal }, 16)
                },
            )
            assertEquals(before, tempFiles())
        }

    @Test
    fun `fatal disposal caused by explicit abort is not swallowed`() =
        runBlocking {
            val before = tempFiles()
            val body = ByteChannel()
            var fatal: Error? = null
            val parts =
                Parts(
                    file(body, type = ContentType.Text.Plain, disposal = {
                        throw Error("synthetic fatal disposal", body.closedCause).also { fatal = it }
                    }),
                )
            val thrown = assertFailsWith<Error> { receive(parts) }
            assertSame(fatal, thrown)
            assertEquals(1, parts.disposed)
            assertEquals(before, tempFiles())
        }

    @Test
    fun `failed disposal is not repeated or self suppressed and pending temp is removed`() =
        runBlocking {
            val before = tempFiles()
            val failure = IOException("synthetic disposal failure")
            val parts = Parts(file(ByteReadChannel(byteArrayOf(1)), disposal = { throw failure }))
            assertSame(failure, assertFailsWith<IOException> { receive(parts) })
            assertEquals(1, parts.disposed)
            assertEquals(0, failure.suppressed.size)
            assertEquals(before, tempFiles())
        }

    @Test
    fun `cleanup rethrowing original cancellation never suppresses itself`() =
        runBlocking {
            val before = tempFiles()
            val cancelled = CancellationException("synthetic cancellation")
            val parts = Parts(file(ByteReadChannel.Empty, opened = { throw cancelled }, disposal = { throw cancelled }))
            assertSame(cancelled, assertFailsWith<CancellationException> { receive(parts) })
            assertEquals(1, parts.disposed)
            assertEquals(0, cancelled.suppressed.size)
            assertEquals(before, tempFiles())
        }

    @Test
    fun `temp deletion failure does not replace original cancellation or fatal error`() =
        runBlocking {
            for (failure in listOf(
                CancellationException("synthetic cancellation"),
                OutOfMemoryError("synthetic fatal"),
            )) {
                val before = tempFiles()
                var obstructed: Path? = null
                try {
                    val thrown =
                        assertFailsWith<Throwable> {
                            readLimitedWithShaToTempFile({
                                val filename = (tempFiles() - before).single()
                                val temp = Path.of(System.getProperty("java.io.tmpdir")).resolve(filename)
                                obstructed = temp
                                Files.delete(temp)
                                Files.createDirectory(temp)
                                Files.createFile(temp.resolve("nonempty"))
                                throw failure
                            }, 16)
                        }
                    assertSame(failure, thrown)
                    assertTrue(thrown.suppressed.single() is DirectoryNotEmptyException)
                } finally {
                    obstructed?.let {
                        Files.deleteIfExists(it.resolve("nonempty"))
                        Files.deleteIfExists(it)
                    }
                }
                assertEquals(before, tempFiles())
            }
        }

    private suspend fun receive(
        parts: Parts,
        limit: Long = 1024,
    ): MusicUploadResult = receiveMusicUpload(parts, ByteChannel(), limit, setOf(ContentType.Image.PNG))

    private fun file(
        body: ByteReadChannel,
        opened: () -> Unit = {},
        type: ContentType = ContentType.Image.PNG,
        disposal: () -> Unit = {},
    ): () -> PartData.FileItem =
        {
            PartData.FileItem(
                {
                    opened()
                    body
                },
                {
                    assertTrue(body.isClosedForRead, "dispose must never await a live producer")
                    disposal()
                },
                Headers.build {
                    append(HttpHeaders.ContentType, type.toString())
                    append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"cover.png\"")
                },
            )
        }

    private class Parts(
        private val create: () -> PartData.FileItem,
        private val after: () -> PartData? = { null },
    ) : MultiPartData {
        var disposed = 0
        private var read = false

        override suspend fun readPart(): PartData? {
            if (read) return after()
            read = true
            val original = create()
            return PartData.FileItem(original.provider, {
                disposed++
                original.dispose()
            }, original.headers)
        }
    }

    private fun tempFiles(): Set<String> =
        Files.newDirectoryStream(Path.of(System.getProperty("java.io.tmpdir")), "music-upload-*.bin").use { stream ->
            stream.map { it.fileName.toString() }.toSet()
        }
}
