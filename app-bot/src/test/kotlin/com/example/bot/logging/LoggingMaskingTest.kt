package com.example.bot.logging

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class LoggingMaskingTest :
    StringSpec({
        "maskQrToken masks GL tokens" {
            val original = "GL:42:1001:1732390400:deadbeefcafebabe"

            val masked = maskQrToken(original)

            masked shouldBe "***"
            masked shouldNotContain "42"
            masked shouldNotContain "1001"
            masked shouldNotContain "deadbeef"
            masked shouldNotContain("GL:")
        }

        "maskQrToken masks INV tokens" {
            val original = "INV:12345:abcdef"

            val masked = maskQrToken(original)

            masked shouldBe "***"
            masked shouldNotContain "12345"
            masked shouldNotContain("INV:")
        }

        "maskQrToken masks arbitrary tokens" {
            val original = "SOME-VERY-LONG-TOKEN-1234567890"

            val masked = maskQrToken(original)

            masked shouldNotContain original
            masked shouldContain "...[masked]"
            masked.startsWith(original.take(6)) shouldBe true
        }
    })
