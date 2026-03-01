package com.example.bot.data.repo

import com.example.bot.payments.PaymentsRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import org.jetbrains.exposed.sql.Database
import java.sql.SQLException

class PaymentsRepositoryImplTest {
    @Test
    fun `mapCaptureException rethrows cancellation exception`() {
        val repository = repo()
        val cancellation = CancellationException("cancelled")

        shouldThrow<CancellationException> {
            repository.mapCaptureException(cancellation)
        }
    }

    @Test
    fun `mapCaptureException maps unique violations and ignores others`() {
        val repository = repo()
        repository.mapCaptureException(SQLException("dup", "23505")) shouldBe PaymentsRepository.CaptureResult.CHARGE_CONFLICT
        repository.mapCaptureException(SQLException("other", "23503")).shouldBeNull()
    }

    private fun repo(): PaymentsRepositoryImpl = PaymentsRepositoryImpl(mockk<Database>(relaxed = true))
}
