package com.example.bot.data.support

import com.example.bot.support.SupportServiceError
import com.example.bot.support.SupportServiceResult
import com.example.bot.support.TicketStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SupportGuestLifecycleServiceH2Test {
    private lateinit var fixture: SupportMutationH2Fixture

    @BeforeEach
    fun setUp() {
        fixture = SupportMutationH2Fixture()
    }

    @AfterEach
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `guest messages preserve NEW and IN PROGRESS statuses`() =
        runBlocking {
            val userId = fixture.insertUser("guest")
            val clubId = fixture.insertClub("Aurora")
            listOf(TicketStatus.NEW, TicketStatus.IN_PROGRESS).forEach { status ->
                val created = fixture.createTicket(userId = userId, clubId = clubId, text = "Need help $status")
                if (status != TicketStatus.NEW) {
                    fixture.seedStatus(created.ticket.id, status)
                }

                val message =
                    fixture.mutationService().addGuestMessage(
                        ticketId = created.ticket.id,
                        userId = userId,
                        text = "More context $status",
                        attachments = null,
                    )

                assertTrue(message is SupportServiceResult.Success)
                assertEquals(status, fixture.freshService().getTicket(created.ticket.id)?.status)
                assertEquals(2L, fixture.messageCount(created.ticket.id))
            }
        }

    @Test
    fun `guest message resumes RESOLVED but rejects readable legacy statuses without writes`() =
        runBlocking {
            val userId = fixture.insertUser("guest")
            val clubId = fixture.insertClub("Aurora")
            val resolved = fixture.createTicket(userId = userId, clubId = clubId, text = "Resolved issue")
            fixture.seedStatus(resolved.ticket.id, TicketStatus.RESOLVED)

            val resumed =
                fixture.mutationService().addGuestMessage(
                    ticketId = resolved.ticket.id,
                    userId = userId,
                    text = "It came back",
                    attachments = null,
                )

            assertTrue(resumed is SupportServiceResult.Success)
            assertEquals(TicketStatus.IN_PROGRESS, fixture.freshService().getTicket(resolved.ticket.id)?.status)
            assertEquals(2L, fixture.messageCount(resolved.ticket.id))

            listOf(TicketStatus.OPENED, TicketStatus.ANSWERED).forEach { legacyStatus ->
                val legacy = fixture.createTicket(userId = userId, clubId = clubId, text = "Legacy $legacyStatus")
                fixture.seedStatus(legacy.ticket.id, legacyStatus)
                val before = fixture.freshService().getTicket(legacy.ticket.id)
                val beforeMessages = fixture.messageCount(legacy.ticket.id)

                val result =
                    fixture.mutationService().addGuestMessage(
                        ticketId = legacy.ticket.id,
                        userId = userId,
                        text = "Must remain absent",
                        attachments = null,
                    )

                result.assertFailure(SupportServiceError.InvalidState)
                assertEquals(before, fixture.freshService().getTicket(legacy.ticket.id))
                assertEquals(beforeMessages, fixture.messageCount(legacy.ticket.id))
            }
        }

    @Test
    fun `closed ticket blocks guest message`() =
        runBlocking {
            val userId = fixture.insertUser("guest")
            val clubId = fixture.insertClub("Aurora")
            val created = fixture.createTicket(userId = userId, clubId = clubId, text = "Need help")
            fixture.seedStatus(created.ticket.id, TicketStatus.CLOSED)

            val result =
                fixture.mutationService().addGuestMessage(
                    ticketId = created.ticket.id,
                    userId = userId,
                    text = "Trying to reopen",
                    attachments = null,
                )

            assertTrue(result is SupportServiceResult.Failure)
            assertEquals(SupportServiceError.TicketClosed, (result as SupportServiceResult.Failure).error)
            assertEquals(1L, fixture.messageCount(created.ticket.id))
            assertEquals(TicketStatus.CLOSED, fixture.freshService().getTicket(created.ticket.id)?.status)
        }
}
