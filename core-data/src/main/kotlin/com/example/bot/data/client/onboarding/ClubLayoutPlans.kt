package com.example.bot.data.client.onboarding

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import javax.imageio.ImageIO
import javax.imageio.stream.ImageInputStream
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream

/** Images are decoded and re-encoded before persistence; supplied type or magic alone is insufficient. */
internal class ClubLayoutPlans(
    private val session: OnboardingSession,
) {
    fun upload(
        hallId: Long,
        body: JsonObject,
    ) {
        body.only(listOf("hallId", "expectedVersion", "contentType", "bytesBase64"))
        val contentType = body.string("contentType")
        if (contentType !in setOf("image/png", "image/jpeg")) fail("unsupported_media_type")
        val encoded = body.string("bytesBase64") ?: fail("validation_error")
        if (encoded.length > MAX_ENCODED_SIZE) fail("payload_too_large")
        val source =
            try {
                Base64.getDecoder().decode(encoded)
            } catch (_: IllegalArgumentException) {
                fail("validation_error")
            }
        if (source.size > MAX_PLAN_BYTES) fail("payload_too_large")
        val bytes = sanitized(source, checkNotNull(contentType))
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val sql = session.sql
        val updated =
            sql.update(
                "UPDATE hall_plans SET bytes=?,content_type=?,sha256=?,size_bytes=?,updated_at=? WHERE hall_id=?",
                bytes,
                contentType,
                hash,
                bytes.size.toLong(),
                session.now,
                hallId,
            )
        if (updated == 0) {
            sql.update(
                "INSERT INTO hall_plans(hall_id,bytes,content_type,sha256,size_bytes,created_at,updated_at) " +
                    "VALUES (?,?,?,?,?,?,?)",
                hallId,
                bytes,
                contentType,
                hash,
                bytes.size.toLong(),
                session.now,
                session.now,
            )
        }
    }

    private fun sanitized(
        source: ByteArray,
        contentType: String,
    ): ByteArray {
        if (source.isEmpty()) fail("validation_error")
        try {
            return MemoryCacheImageInputStream(ByteArrayInputStream(source)).use { stream ->
                decode(stream, contentType)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: OnboardingFailure) {
            throw failure
        } catch (_: java.io.IOException) {
            fail("validation_error")
        } catch (_: IllegalArgumentException) {
            fail("validation_error")
        }
    }

    private fun decode(
        stream: ImageInputStream,
        contentType: String,
    ): ByteArray {
        val readers = ImageIO.getImageReaders(stream)
        if (!readers.hasNext()) fail("validation_error")
        val reader = readers.next()
        try {
            val expected = if (contentType == "image/png") "png" else "jpeg"
            if (!reader.formatName.equals(expected, ignoreCase = true)) fail("validation_error")
            reader.input = stream
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS) fail("validation_error")
            val image = reader.read(0) ?: fail("validation_error")
            return encode(image, expected)
        } finally {
            reader.dispose()
        }
    }

    private fun encode(
        image: BufferedImage,
        format: String,
    ): ByteArray =
        ByteArrayOutputStream().use { output ->
            MemoryCacheImageOutputStream(output).use { stream ->
                if (!ImageIO.write(image, format, stream)) fail("validation_error")
                stream.flush()
            }
            val bytes = output.toByteArray()
            if (bytes.size > MAX_PLAN_BYTES) fail("payload_too_large")
            bytes
        }

    private companion object {
        const val MAX_PLAN_BYTES = 5 * 1024 * 1024
        const val MAX_ENCODED_SIZE = ((MAX_PLAN_BYTES + 2) / 3) * 4
        const val MAX_PIXELS = 25_000_000L
    }
}
