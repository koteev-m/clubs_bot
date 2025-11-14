package com.example.bot.security

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val HEX_MASK = 0xff
private const val HEX_BASE = 0x100
private const val HEX_TRIM = 1
private const val HEX_RADIX = 16

enum class Role {
    USER,
    ADMIN,
}

class AccessController(
    private val permissions: Map<Role, Set<String>>,
) {
    fun isAllowed(
        role: Role,
        action: String,
    ): Boolean = permissions[role]?.contains(action) ?: false
}

object SignatureValidator {
    fun sign(
        secret: String,
        data: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(data.toByteArray()).joinToString("") { byte ->
            val hex = (byte.toInt() and HEX_MASK) + HEX_BASE
            hex.toString(HEX_RADIX).substring(HEX_TRIM)
        }
    }

    fun verify(
        secret: String,
        data: String,
        signature: String,
    ): Boolean = sign(secret, data) == signature
}
