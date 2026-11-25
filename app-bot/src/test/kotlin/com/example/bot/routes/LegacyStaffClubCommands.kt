package com.example.bot.routes

import io.ktor.http.HttpMethod
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** One valid HTTP command per write permission in the historical CLUB_ADMIN package. */
internal class LegacyStaffClubCommand(
    val path: String,
    val method: HttpMethod,
    json: String,
) {
    val body = Json.parseToJsonElement(json).jsonObject
    val key = "legacy-staff-" + path.replace('/', '-')
}

internal val legacyStaffClubCommands =
    listOf(
        LegacyStaffClubCommand(
            "profile",
            HttpMethod.Patch,
            """{"name":"Legacy staff update","description":"Saved","timezone":"Europe/Moscow","expectedVersion":"1"}""",
        ),
        LegacyStaffClubCommand("halls", HttpMethod.Post, """{"name":"Legacy hall"}"""),
        LegacyStaffClubCommand(
            "calendar",
            HttpMethod.Patch,
            """{"expectedVersion":"1","timezone":"Europe/Moscow","weekly":[{"day":1,"mode":"remove"}],
                "holidays":[],"exceptions":[]}""",
        ),
        LegacyStaffClubCommand(
            "booking-rules",
            HttpMethod.Patch,
            """{"expectedVersion":"1","cutoffMinutes":90,"holdMinutes":12,"retentionMinutes":45}""",
        ),
        LegacyStaffClubCommand(
            "finance/template",
            HttpMethod.Post,
            """{"expectedVersion":"1","entity":"bracelet","action":"create","name":"Legacy bracelet","orderIndex":0}""",
        ),
        LegacyStaffClubCommand(
            "modules-settings",
            HttpMethod.Patch,
            """{"expectedVersion":"1","earlyWindowMinutes":60}""",
        ),
    )
