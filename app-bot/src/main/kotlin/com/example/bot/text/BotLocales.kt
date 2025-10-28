package com.example.bot.text

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

object BotLocales {
    // JDK 21: конструктор Locale(lang, country) deprecated — используем forLanguageTag
    val RU: Locale = Locale.forLanguageTag("ru-RU")
    val EN: Locale = Locale.ENGLISH

    fun resolve(lang: String?): Locale = if (lang?.startsWith("en", ignoreCase = true) == true) EN else RU

    fun dayNameShort(
        instant: Instant,
        zone: ZoneId,
        locale: Locale,
    ): String =
        instant.atZone(zone).dayOfWeek.getDisplayName(TextStyle.SHORT, locale).let { s ->
            if (s.isNotEmpty() && s[0].isLowerCase()) s.replaceFirstChar { it.titlecase(locale) } else s
        }

    fun dateDMmm(
        instant: Instant,
        zone: ZoneId,
        locale: Locale,
    ): String = instant.atZone(zone).format(DateTimeFormatter.ofPattern("d MMM", locale))

    fun timeHHmm(
        instant: Instant,
        zone: ZoneId,
        locale: Locale,
    ): String = instant.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm", locale))

    /** Простой формат суммы: без валютных символов, с локальными разделителями. */
    fun money(
        amountMinor: Long,
        locale: Locale,
    ): String {
        val nf = NumberFormat.getNumberInstance(locale)
        nf.maximumFractionDigits = 0
        nf.minimumFractionDigits = 0
        return nf.format(amountMinor / 100.0) // minor→major, округление отображением
    }
}
