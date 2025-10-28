package com.example.bot.data.club

import com.example.bot.club.GuestListEntryStatus

internal const val MIN_GUESTS_PER_ENTRY: Int = 1
internal const val MAX_ADDITIONAL_GUESTS: Int = 10
internal const val MAX_GUESTS_PER_ENTRY: Int = MIN_GUESTS_PER_ENTRY + MAX_ADDITIONAL_GUESTS
internal const val DEFAULT_CATEGORY: String = "REGULAR"
internal const val DEFAULT_PLUS_ONES_USED: Int = 0
private const val PHONE_ERROR_MESSAGE: String = "Phone must contain digits and optional leading +"

private val SEPARATOR_CHARS = setOf(' ', '-', '(', ')', '.', '/', '\\')

internal sealed interface EntryValidationOutcome {
    data class Valid(
        val name: String,
        val phone: String?,
        val guestsCount: Int,
        val notes: String?,
        val status: GuestListEntryStatus,
    ) : EntryValidationOutcome

    data class Invalid(val reason: String) : EntryValidationOutcome
}

internal data class PhoneNormalization(val normalized: String?, val provided: Boolean, val valid: Boolean)

internal fun sanitizePhone(raw: String?): PhoneNormalization {
    val trimmed = raw?.trim()
    val provided = !trimmed.isNullOrEmpty()
    if (!provided) {
        return PhoneNormalization(null, provided = false, valid = true)
    }
    val builder = StringBuilder()
    var plusSeen = false
    var valid = true
    for (ch in trimmed!!) {
        if (!valid) break
        when {
            ch == '+' ->
                if (plusSeen || builder.isNotEmpty()) {
                    valid = false
                } else {
                    builder.append(ch)
                    plusSeen = true
                }
            ch.isDigit() -> builder.append(ch)
            ch in SEPARATOR_CHARS || ch.isWhitespace() -> Unit
            else -> valid = false
        }
    }
    val normalized = builder.toString()
    val sanitizedValid = valid && normalized.isNotEmpty() && normalized != "+"
    return if (sanitizedValid) {
        PhoneNormalization(normalized, provided = true, valid = true)
    } else {
        PhoneNormalization(null, provided = true, valid = false)
    }
}

internal fun validateEntryInput(
    name: String,
    phone: String?,
    guestsCount: Int,
    notes: String?,
    status: GuestListEntryStatus,
): EntryValidationOutcome {
    val trimmedName = name.trim()
    val phoneNormalization = sanitizePhone(phone)
    val error =
        when {
            trimmedName.isEmpty() -> "Name must not be blank"
            guestsCount < MIN_GUESTS_PER_ENTRY -> "guests_count must be >= $MIN_GUESTS_PER_ENTRY"
            guestsCount > MAX_GUESTS_PER_ENTRY -> "guests_count must be <= $MAX_GUESTS_PER_ENTRY"
            !phoneNormalization.valid && phoneNormalization.provided -> PHONE_ERROR_MESSAGE
            else -> null
        }
    return if (error != null) {
        EntryValidationOutcome.Invalid(error)
    } else {
        val normalizedNotes = notes?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedPhone =
            if (phoneNormalization.provided && phoneNormalization.valid) {
                phoneNormalization.normalized
            } else {
                null
            }
        EntryValidationOutcome.Valid(
            name = trimmedName,
            phone = normalizedPhone,
            guestsCount = guestsCount,
            notes = normalizedNotes,
            status = status,
        )
    }
}
