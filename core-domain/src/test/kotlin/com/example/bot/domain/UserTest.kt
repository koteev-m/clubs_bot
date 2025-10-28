package com.example.bot.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec

class UserTest :
    StringSpec({
        "blank name fails" {
            shouldThrow<IllegalArgumentException> { User(1, "", "ADMIN") }
        }
    })
