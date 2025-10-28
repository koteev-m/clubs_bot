package com.example.bot

import com.example.bot.availability.TableAvailabilityDto
import com.example.bot.availability.TableStatus
import com.example.bot.telegram.Keyboards
import com.example.bot.text.BotTexts
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize

class PaginationTest :
    StringSpec({
        val kb = Keyboards(BotTexts())
        val tables =
            (1..12).map {
                TableAvailabilityDto(it.toLong(), it.toString(), "A", 4, 100, TableStatus.FREE)
            }

        "creates navigation buttons" {
            val keyboard =
                kb.tablesKeyboard(tables, page = 1, pageSize = 5, lang = "ru") { it.tableId.toString() }
            val rows = keyboard.inlineKeyboard()
            rows.last().shouldHaveSize(2) // indicator and next on first page
        }
    })
