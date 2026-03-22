package com.example.bot.data.privacy

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import javax.crypto.Cipher
import kotlinx.serialization.Serializable
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val GCM_IV_LENGTH_BYTES = 12
private const val GCM_TAG_LENGTH_BITS = 128
private const val MIN_PHONE_ENCRYPTION_KEY_LENGTH = 32
private const val DEV_PHONE_ENCRYPTION_KEY = "dev-phone-encryption-key-32-bytes!!"
private val PROD_LIKE_ENVIRONMENTS = setOf("prod", "production", "stage", "staging")

data class ProtectedPhone(
    val encrypted: String,
    val hash: String,
    val lastFour: String,
)

data class PrivacyRetentionConfig(
    val guestListPhoneRetention: Duration,
)

@Serializable
data class PrivacyAdminActor(
    val userId: Long,
    val role: String,
)

@Serializable
data class PrivacyAnonymizeResult(
    val usersUpdated: Int,
    val bookingsUpdated: Int,
    val guestListEntriesUpdated: Int,
)

@Serializable
data class PrivacyRetentionResult(
    val guestListEntriesScrubbed: Int,
)

class PhoneCipher(secret: String) {
    private val secretBytes = secret.toByteArray(StandardCharsets.UTF_8)
    private val keySpec = SecretKeySpec(sha256(secretBytes), "AES")
    private val hmacKeySpec = SecretKeySpec(secretBytes, "HmacSHA256")
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
            lastFour = lastFour(normalized),
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

    fun hash(phoneE164: String): String {
        val normalized = phoneE164.trim()
        require(normalized.isNotEmpty()) { "phone must not be blank" }
        return hmacSha256(("phone:" + normalized).toByteArray(StandardCharsets.UTF_8)).toHex()
    }

    fun lastFour(phoneE164: String): String {
        val digits = phoneE164.filter(Char::isDigit)
        require(digits.length >= 4) { "phone must contain at least 4 digits" }
        return digits.takeLast(4)
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun hmacSha256(bytes: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(hmacKeySpec)
            doFinal(bytes)
        }
}

class PrivacyConfig private constructor(
    val phoneCipher: PhoneCipher,
    val retention: PrivacyRetentionConfig,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): PrivacyConfig {
            val appMode = PrivacyAppMode.fromEnv(env)
            val encryptionKey = env["PHONE_ENCRYPTION_KEY"]?.trim().orEmpty()
            val resolvedEncryptionKey =
                when {
                    encryptionKey.isNotBlank() -> encryptionKey
                    appMode.isProdLike -> error("PHONE_ENCRYPTION_KEY must be set and at least 32 characters long")
                    else -> DEV_PHONE_ENCRYPTION_KEY
                }
            require(resolvedEncryptionKey.length >= MIN_PHONE_ENCRYPTION_KEY_LENGTH) {
                "PHONE_ENCRYPTION_KEY must be set and at least 32 characters long"
            }
            require(!(appMode.isProdLike && resolvedEncryptionKey == DEV_PHONE_ENCRYPTION_KEY)) {
                "PHONE_ENCRYPTION_KEY must not use the development default in ${appMode.value}"
            }
            val retentionDays = env["RETENTION_GUEST_LIST_PHONE_DAYS"]?.toLongOrNull() ?: 30L
            require(retentionDays > 0) { "RETENTION_GUEST_LIST_PHONE_DAYS must be positive" }
            return PrivacyConfig(
                phoneCipher = PhoneCipher(resolvedEncryptionKey),
                retention = PrivacyRetentionConfig(Duration.ofDays(retentionDays)),
            )
        }
    }
}

private data class PrivacyAppMode(
    val value: String,
    val isProdLike: Boolean,
) {
    companion object {
        fun fromEnv(env: Map<String, String>): PrivacyAppMode {
            val profile = envValue(env, "APP_PROFILE")
            val appEnv = envValue(env, "APP_ENV")
            val prodLike = listOf(profile, appEnv).any { it in PROD_LIKE_ENVIRONMENTS }
            val resolvedValue =
                when {
                    prodLike -> listOfNotNull(profile, appEnv).first { it in PROD_LIKE_ENVIRONMENTS }
                    profile != null -> profile
                    appEnv != null -> appEnv
                    else -> "dev"
                }
            return PrivacyAppMode(
                value = resolvedValue,
                isProdLike = prodLike,
            )
        }

        private fun envValue(env: Map<String, String>, key: String): String? =
            env[key]
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() }
    }
}

internal fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }
