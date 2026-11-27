package com.example.bot.data.client.onboarding

import com.example.bot.data.client.assignments.managerRoleCodes
import com.example.bot.data.client.assignments.managerSourcePermissions
import com.example.bot.data.client.assignments.managerSources

internal const val FORM_OPERATING_NIGHT = "client.club.night.create"
internal const val READ_FLOOR_LAYOUT = "client.club.layout.read"
internal const val READ_FLOOR_CALENDAR = "client.club.calendar.read"
internal const val READ_FLOOR_BOOKING_RULES = "client.club.booking_rules.read"

internal fun OnboardingSession.floorOnly(
    clientId: Long,
    clubId: Long,
): Boolean {
    val roles = managerRoleCodes(clientId, clubId)
    return "FLOOR_MANAGER" in roles &&
        !hasIndependentCalendarWrite(clientId, clubId) &&
        businessOwnerRef(clientId, clubId) == null
}

internal fun OnboardingSession.requireFloorSection(
    clientId: Long,
    clubId: Long,
    permission: String,
) {
    val roles = managerRoleCodes(clientId, clubId)
    val restricted = roles.isNotEmpty() && "CLUB_MANAGER" !in roles && "NETWORK_MANAGER" !in roles
    if (restricted && !hasClubGrant(clientId, clubId, permission)) fail("not_found")
}

internal fun OnboardingSession.requireEventLinkAuthority(
    clientId: Long,
    clubId: Long,
) {
    val roles = managerRoleCodes(clientId, clubId)
    val restricted = roles.any { it !in setOf("NETWORK_MANAGER", "CLUB_MANAGER") }
    if (restricted && !hasIndependentCalendarWrite(clientId, clubId)) fail("not_found")
}

internal fun OnboardingSession.hasIndependentCalendarWrite(
    clientId: Long,
    clubId: Long,
): Boolean =
    managerSources(clientId, clubId).any { row ->
        row["role_code"] in setOf("NETWORK_MANAGER", "CLUB_MANAGER") && WRITE_CALENDAR in managerSourcePermissions(row)
    }
