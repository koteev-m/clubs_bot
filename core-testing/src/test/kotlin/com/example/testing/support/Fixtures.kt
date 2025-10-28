package com.example.testing.support

import com.example.bot.availability.Table
import com.example.bot.domain.User
import com.example.bot.notifications.NotifyMessage
import com.example.bot.time.Club
import com.example.bot.time.Event
import java.time.Instant

/** Factory helpers for test entities. */
object Fixtures {
    fun club(
        id: Long = 1,
        timezone: String = "UTC",
    ) = Club(id, timezone)

    fun event(
        id: Long = 1,
        clubId: Long = 1,
        start: Instant = Instant.now(),
        end: Instant = start.plusSeconds(3600),
        special: Boolean = true,
    ) = Event(id, clubId, start, end, special)

    fun table(
        id: Long = 1,
        number: String = "T1",
        zone: String = "A",
        capacity: Int = 4,
        minDeposit: Int = 100,
        active: Boolean = true,
    ) = Table(id, number, zone, capacity, minDeposit, active)

    fun user(
        id: Long = 1,
        name: String = "User",
        role: String = "guest",
    ) = User(id, name, role)

    data class Promoter(val id: Long = 1, val name: String = "Promoter", val alias: String = "prom")

    fun promoter(
        id: Long = 1,
        name: String = "Promoter",
        alias: String = "prom",
    ) = Promoter(id, name, alias)

    fun notifyMessage(
        chatId: Long = 1,
        text: String = "hi",
    ) = NotifyMessage(chatId, null, com.example.bot.notifications.NotifyMethod.TEXT, text, null, null, null, null, null)
}
