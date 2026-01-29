package com.example.bot.data.db

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

internal fun Instant.toOffsetDateTimeUtc(): OffsetDateTime =
    OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
