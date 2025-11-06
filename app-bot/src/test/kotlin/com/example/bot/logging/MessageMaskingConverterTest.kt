package com.example.bot.logging

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class MessageMaskingConverterTest : StringSpec({

    "maskPhone hides all but last 3 digits" {
        val original = "+7 (999) 123-45-67"
        val masked = MessageMaskingConverter.maskPhone(original)

        // В сообщении есть признак маскирования
        masked shouldContain "***"
        // Код «999» не должен просвечивать
        masked shouldNotContain "999"

        // Сохраняются последние 3 цифры исходного номера
        val origDigits = original.filter(Char::isDigit)           // "79991234567"
        val maskedDigits = masked.filter(Char::isDigit)
        maskedDigits.endsWith(origDigits.takeLast(3)) shouldBe true

        // Исходная непрерывная цифровая строка не должна встречаться целиком
        masked shouldNotContain origDigits
    }

    "maskPersonName replaces characters after first letter (fullName and fio)" {
        val msg1 = "fullName=Иван Иванов"
        val masked1 = MessageMaskingConverter.maskPersonName(msg1)
        masked1 shouldNotContain "Иван Иванов"
        masked1 shouldContain "fullName="
        Regex("""fullName=И\*+\s+И\*+""").containsMatchIn(masked1) shouldBe true

        val msg2 = "fio=Иван Иванов"
        val masked2 = MessageMaskingConverter.maskPersonName(msg2)
        masked2 shouldNotContain "Иван Иванов"
        Regex("""fio=И\*+\s+И\*+""").containsMatchIn(masked2) shouldBe true
    }

    "maskTokens replaces bare telegram token with placeholder" {
        val t1 = "bot token 123456:ABCDefGhijklmnOPQRSTuvwxYZ0123456789"
        val t2 = "header=Bearer 1234567:AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_"

        val m1 = MessageMaskingConverter.maskTokens(t1)
        val m2 = MessageMaskingConverter.maskTokens(t2)

        m1 shouldContain "***REDACTED***"
        m2 shouldContain "***REDACTED***"

        val tokenRe = Regex("""\b\d{6,12}:[A-Za-z0-9_-]{30,}\b""")
        tokenRe.containsMatchIn(m1) shouldBe false
        tokenRe.containsMatchIn(m2) shouldBe false
    }
})
