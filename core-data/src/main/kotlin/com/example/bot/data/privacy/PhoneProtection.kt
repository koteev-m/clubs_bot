package com.example.bot.data.privacy

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val GCM_IV_LENGTH_BYTES = 12
private const val GCM_TAG_LENGTH_BITS = 128

data class ProtectedPhone(
    val encrypted: String,
    val hash: String,
)

data class PrivacyRetentionConfig(
    val guestListPhoneRetention: Duration,
)

data class PrivacyAdminActor(
    val userId: Long,
    val role: String,
)

data class PrivacyAnonymizeResult(
    val usersUpdated: Int,
    val bookingsUpdated: Int,
    val guestListEntriesUpdated: Int,
)

data class PrivacyRetentionResult(
    val guestListEntriesScrubbed: Int,
)

class PhoneCipher(secret: String) {
    private val keyBytes = sha256(secret.toByteArray(StandardCharsets.UTF_8))
    private val keySpec = SecretKeySpec(keyBytes, "AES")
    private val random = SecureRandom()

    fun protect(phoneE164: String): ProtectedPhone {
        val normalized = phoneE164.trim()
        require(normalized.isNotEmpty()) { "phone must not be blank" }
        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        random.nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val ciphertext = cipher.doFinal(normalized.toByteArray(StandardCharsets.UTF_8))
        val payload = ByteBuffer.allocate(iv.size + ciphertext.size).put(iv).put(ciphertext).array()
        return ProtectedPhone(
            encrypted = Base64.getEncoder().encodeToString(payload),
            hash = hash(normalized),
        )
    }

    fun decrypt(payload: String): String {
        val raw = Base64.getDecoder().decode(payload)
        require(raw.size > GCM_IV_LENGTH_BYTES) { "encrypted payload is malformed" }
        val iv = raw.copyOfRange(0, GCM_IV_LENGTH_BYTES)
        val ciphertext = raw.copyOfRange(GCM_IV_LENGTH_BYTES, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext).toString(StandardCharsets.UTF_8)
    }

    fun hash(phoneE164: String): String =
        sha256(("phone:" + phoneE164.trim()).toByteArray(StandardCharsets.UTF_8)).toHex()

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}

class PrivacyConfig private constructor(
    val phoneCipher: PhoneCipher,
    val retention: PrivacyRetentionConfig,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): PrivacyConfig {
            val profile = env["APP_PROFILE"]?.trim()?.lowercase().orEmpty()
            val encryptionKey = env["PHONE_ENCRYPTION_KEY"]?.trim().orEmpty().ifBlank {
                if (profile == "prod" || profile == "stage") {
                    error("PHONE_ENCRYPTION_KEY must be set and at least 32 characters long")
                }
                "dev-phone-encryption-key-32-bytes!!"
            }
            require(encryptionKey.length >= 32) { "PHONE_ENCRYPTION_KEY must be set and at least 32 characters long" }
            val retentionDays = env["RETENTION_GUEST_LIST_PHONE_DAYS"]?.toLongOrNull() ?: 30L
            require(retentionDays > 0) { "RETENTION_GUEST_LIST_PHONE_DAYS must be positive" }
            return PrivacyConfig(
                phoneCipher = PhoneCipher(encryptionKey),
                retention = PrivacyRetentionConfig(Duration.ofDays(retentionDays)),
            )
        }
    }
}

internal fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }
