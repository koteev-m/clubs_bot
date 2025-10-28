package com.example.bot.testing

import com.example.bot.domain.User
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class UserFactoryTest :
    StringSpec({
        "creates user" {
            val user = UserFactory.create()
            user shouldBe User(1, "test", "USER")
        }
    })
