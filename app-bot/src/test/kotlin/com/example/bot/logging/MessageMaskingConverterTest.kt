package com.example.bot.logging

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class MessageMaskingConverterTest : StringSpec({
    "maskPhone hides all but last digits" {
        val original = "+7 (999) 123-45-67"
        val masked = MessageMaskingConverter.maskPhone(original)

        masked.shouldContain("***")
        masked.shouldNotContain("999")
        masked.filter { it.isDigit() } shouldBe "567"
    }

    "maskPersonName replaces characters after first letter" {
        val original = "fullName=Иван Иванов"
        val masked = MessageMaskingConverter.maskPersonName(original)

        masked shouldBe "fullName=И*** И*****"
    }

    "maskTokens replaces telegram token with placeholder" {
        val original = "bot token 123456:ABCDefGhijklmnOPQRSTuvwxYZ0123456789"
        val masked = MessageMaskingConverter.maskTokens(original)

        masked shouldBe "bot token ***REDACTED***"
    }
})
