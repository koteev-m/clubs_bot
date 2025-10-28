package com.example.bot

import com.example.bot.telegram.Keyboards
import com.example.bot.telegram.tokens.ClubTokenCodec
import com.example.bot.text.BotTexts
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeLessThan

class CallbackDataBudgetTest :
    StringSpec({
        val texts = BotTexts()
        val kb = Keyboards(texts)

        "callback data under limit" {
            val clubs = (1..3).map { ClubTokenCodec.encode(it.toLong()) to "Club $it" }
            val clubKb = kb.clubsKeyboard(clubs)
            clubKb.inlineKeyboard().flatMap { it.toList() }.forEach { btn ->
                btn.callbackData?.length?.let { it shouldBeLessThan 64 }
            }

            val guests = kb.guestsKeyboard(6) { "tok$it" }
            guests.inlineKeyboard().flatMap { it.toList() }.forEach { btn ->
                btn.callbackData?.length?.let { it shouldBeLessThan 64 }
            }
        }
    })
