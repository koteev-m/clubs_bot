package com.example.bot.telegram.tokens

object ClubTokenCodec {
    private const val RADIX = 36

    fun encode(clubId: Long): String = clubId.toString(RADIX)

    fun decode(token: String): Long? = token.toLongOrNull(RADIX)?.takeIf { it >= 0 }
}
