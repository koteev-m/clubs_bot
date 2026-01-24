package com.example.bot.opschat

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import java.time.Instant

class OpsChatModelsTest :
    StringSpec({
        "clubId must be positive" {
            shouldThrow<IllegalArgumentException> {
                ClubOpsChatConfigUpsert(
                    clubId = 0,
                    chatId = -100,
                    bookingsThreadId = null,
                    checkinThreadId = null,
                    supportThreadId = null,
                )
            }
        }

        "chatId must not be zero" {
            shouldThrow<IllegalArgumentException> {
                ClubOpsChatConfigUpsert(
                    clubId = 1,
                    chatId = 0,
                    bookingsThreadId = null,
                    checkinThreadId = null,
                    supportThreadId = null,
                )
            }
        }

        "thread ids must be positive when provided" {
            shouldThrow<IllegalArgumentException> {
                ClubOpsChatConfig(
                    clubId = 1,
                    chatId = -100,
                    bookingsThreadId = -1,
                    checkinThreadId = null,
                    supportThreadId = null,
                    updatedAt = Instant.EPOCH,
                )
            }
        }

        "negative chatId is allowed for supergroups" {
            ClubOpsChatConfigUpsert(
                clubId = 42,
                chatId = -1001234567890,
                bookingsThreadId = 10,
                checkinThreadId = 20,
                supportThreadId = 30,
            )
        }
    })
