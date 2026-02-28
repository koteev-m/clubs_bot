package com.example.bot.data.repo

import com.example.bot.data.TestDatabase
import com.example.bot.payments.PaymentsRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.SQLException

class PaymentsRepositoryImplTest {
    private lateinit var testDatabase: TestDatabase
    private lateinit var repository: PaymentsRepositoryImpl

    @BeforeEach
    fun setUp() {
        testDatabase = TestDatabase()
        repository = PaymentsRepositoryImpl(testDatabase.database)
    }

    @AfterEach
    fun tearDown() {
        testDatabase.close()
    }

    @Test
    fun `mapCaptureException rethrows cancellation exception`() {
        val cancellation = CancellationException("cancelled")

        shouldThrow<CancellationException> {
            repository.mapCaptureException(cancellation)
        }
    }

    @Test
    fun `mapCaptureException maps unique violations and ignores others`() {
        repository.mapCaptureException(SQLException("dup", "23505")) shouldBe PaymentsRepository.CaptureResult.CHARGE_CONFLICT
        repository.mapCaptureException(SQLException("other", "23503")).shouldBeNull()
    }
}
