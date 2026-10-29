package com.example.bot.routes

import com.example.bot.http.ErrorCodes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.MultiPartData
import io.ktor.http.content.PartData
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.requestsize.RequestTooLargeException
import io.ktor.server.request.receiveMultipart
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.discard
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

private const val MUSIC_UPLOAD_BUFFER_SIZE = 8_192

internal sealed class MusicUploadResult {
    data class Ok(
        val tempFile: Path,
        val contentType: ContentType,
        val sizeBytes: Long,
        val sha256: String,
    ) : MusicUploadResult()

    data class Error(
        val status: HttpStatusCode,
        val code: String,
        val details: Map<String, String>? = null,
    ) : MusicUploadResult()
}

internal suspend fun ApplicationCall.receiveUpload(
    maxBytes: Long,
    allowedContentTypes: Set<ContentType>,
): MusicUploadResult = receiveMusicUpload(receiveMultipart(), request.receiveChannel(), maxBytes, allowedContentTypes)

/** Owns each part and temp until the complete multipart body transfers the saved file to the caller. */
internal suspend fun receiveMusicUpload(
    multipart: MultiPartData,
    requestChannel: ByteReadChannel,
    maxBytes: Long,
    allowedContentTypes: Set<ContentType>,
): MusicUploadResult {
    val reader = MusicMultipartReader(multipart, requestChannel, maxBytes, allowedContentTypes)
    var failure: Throwable? = null
    val result =
        try {
            reader.read()
        } catch (cause: Throwable) {
            failure = cause
            null
        }
    failure = reader.cleanup(failure)
    failure?.let { throw it }
    return checkNotNull(result)
}

private class MusicMultipartReader(
    private val multipart: MultiPartData,
    private val requestChannel: ByteReadChannel,
    private val maxBytes: Long,
    private val allowedContentTypes: Set<ContentType>,
) {
    private var upload: MusicUploadResult.Ok? = null
    private var part: PartData? = null
    private var channel: ByteReadChannel? = null
    private var complete = false

    suspend fun read(): MusicUploadResult {
        while (true) {
            val next = multipart.readPart() ?: break
            part = next
            channel = (next as? PartData.FileItem)?.provider?.invoke()
            val rejected = readSelectedFile(next)
            if (rejected != null) return rejected
            // Drain ignored data cooperatively before Ktor's blocking part.dispose().
            channel?.discard()
            part = null
            next.dispose()
            channel = null
        }
        complete = true
        return upload ?: emptyMusicUpload("required")
    }

    private suspend fun readSelectedFile(next: PartData): MusicUploadResult.Error? {
        if (next !is PartData.FileItem || next.name != "file" || upload != null) return null
        val contentType = next.contentType
        val result =
            if (contentType == null || allowedContentTypes.none { contentType.match(it) }) {
                MusicUploadResult.Error(HttpStatusCode.UnsupportedMediaType, ErrorCodes.unsupported_media_type)
            } else {
                readMusicFile(checkNotNull(channel), maxBytes, contentType)
            }
        return when (result) {
            is MusicUploadResult.Error -> result
            is MusicUploadResult.Ok -> {
                upload = result
                null
            }
        }
    }

    fun cleanup(failure: Throwable?): Throwable? {
        if (complete) return failure
        val combined = abortMusicUpload(requestChannel, channel, part, failure)
        return upload?.let { musicTempCleanupFailure(it.tempFile, combined) } ?: combined
    }
}

private fun abortMusicUpload(
    requestChannel: ByteReadChannel,
    partChannel: ByteReadChannel?,
    part: PartData?,
    failure: Throwable?,
): Throwable? {
    // Ktor 3.3.1 FileItem.dispose runs a blocking discard. Stop both producer and
    // consumer first; never wait there for a producer that can still send bytes.
    val abort = CancellationException("Music upload stopped")
    var combined = failure

    fun cleanup(action: () -> Unit) {
        try {
            action()
        } catch (cancelled: CancellationException) {
            if (cancelled !== abort && cancelled.cause !== abort) {
                combined = combineMusicFailures(combined, cancelled)
            }
        } catch (cause: Throwable) {
            // A fatal error whose cause happens to be this abort is still fatal.
            combined = combineMusicFailures(combined, cause)
        }
    }
    cleanup { requestChannel.cancel(abort) }
    cleanup { partChannel?.cancel(abort) }
    cleanup { part?.dispose() }
    return combined
}

private fun combineMusicFailures(
    primary: Throwable?,
    cleanup: Throwable,
): Throwable {
    if (primary == null || primary === cleanup) return cleanup
    val fatalCleanup = cleanup is Error && primary !is Error
    val cancelledCleanup = cleanup is CancellationException && primary !is Error && primary !is CancellationException
    return if (fatalCleanup || cancelledCleanup) {
        cleanup.apply { addSuppressed(primary) }
    } else {
        primary.apply { addSuppressed(cleanup) }
    }
}

internal fun deleteMusicTempFile(
    tempFile: Path,
    failure: Throwable? = null,
) {
    val combined = musicTempCleanupFailure(tempFile, failure)
    if (combined !== failure) throw checkNotNull(combined)
}

private fun musicTempCleanupFailure(
    tempFile: Path,
    failure: Throwable?,
): Throwable? =
    try {
        Files.deleteIfExists(tempFile)
        failure
    } catch (cleanup: Throwable) {
        combineMusicFailures(failure, cleanup)
    }

private suspend fun readMusicFile(
    channel: ByteReadChannel,
    maxBytes: Long,
    contentType: ContentType,
): MusicUploadResult =
    try {
        val result = readLimitedWithShaToTempFile({ channel }, maxBytes)
        if (result.sizeBytes == 0L) {
            deleteMusicTempFile(result.tempFile)
            emptyMusicUpload()
        } else {
            MusicUploadResult.Ok(result.tempFile, contentType, result.sizeBytes, result.sha256)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (tooLarge: RequestTooLargeException) {
        throw tooLarge
    } catch (_: MusicPayloadTooLargeException) {
        MusicUploadResult.Error(HttpStatusCode.PayloadTooLarge, ErrorCodes.payload_too_large)
    } catch (_: Exception) {
        MusicUploadResult.Error(HttpStatusCode.InternalServerError, ErrorCodes.internal_error)
    }

private fun emptyMusicUpload(reason: String = "must_be_non_empty"): MusicUploadResult.Error =
    MusicUploadResult.Error(
        HttpStatusCode.BadRequest,
        ErrorCodes.validation_error,
        details = mapOf("file" to reason),
    )

internal data class ReadWithShaResult(
    val tempFile: Path,
    val sizeBytes: Long,
    val sha256: String,
)

internal suspend fun readLimitedWithShaToTempFile(
    channelProvider: () -> ByteReadChannel,
    maxBytes: Long,
): ReadWithShaResult {
    val tempFile = Files.createTempFile("music-upload-", ".bin")
    return try {
        val channel = channelProvider()
        val digest = MessageDigest.getInstance("SHA-256")
        val total =
            Files.newOutputStream(tempFile).use { output ->
                channel.copyMusicUpload(output, digest, maxBytes)
            }
        ReadWithShaResult(tempFile, total, digest.digest().joinToString("") { "%02x".format(it) })
    } catch (cause: Throwable) {
        deleteMusicTempFile(tempFile, cause)
        throw cause
    }
}

private suspend fun ByteReadChannel.copyMusicUpload(
    output: OutputStream,
    digest: MessageDigest,
    maxBytes: Long,
): Long {
    val buffer = ByteArray(MUSIC_UPLOAD_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = readAvailable(buffer)
        if (read <= 0) break
        total += read
        if (total > maxBytes) throw MusicPayloadTooLargeException()
        digest.update(buffer, 0, read)
        output.write(buffer, 0, read)
    }
    return total
}

private class MusicPayloadTooLargeException : IOException()
