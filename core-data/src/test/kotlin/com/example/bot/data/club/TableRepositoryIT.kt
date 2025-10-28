package com.example.bot.data.club

import com.example.bot.club.Table
import com.example.bot.club.TableRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class TableRepositoryIT : PostgresClubIntegrationTest() {
    private lateinit var repository: TableRepository

    @BeforeEach
    fun initRepository() {
        repository = TableRepositoryImpl(database)
    }

    @Test
    fun `list tables by club`() =
        runBlocking {
            val clubId = insertClub(name = "Aurora")
            val otherClubId = insertClub(name = "Nebula")
            val firstTableId = insertTable(clubId, tableNumber = 1, capacity = 4, minDeposit = BigDecimal("100.00"))
            val secondTableId = insertTable(clubId, tableNumber = 2, capacity = 6, minDeposit = BigDecimal("150.00"))
            insertTable(otherClubId, tableNumber = 1, capacity = 2, minDeposit = BigDecimal("80.00"))

            val tables = repository.listByClub(clubId)

            val expected =
                listOf(
                    Table(
                        id = firstTableId,
                        clubId = clubId,
                        zoneId = null,
                        number = 1,
                        capacity = 4,
                        minDeposit = BigDecimal("100.00"),
                        active = true,
                    ),
                    Table(
                        id = secondTableId,
                        clubId = clubId,
                        zoneId = null,
                        number = 2,
                        capacity = 6,
                        minDeposit = BigDecimal("150.00"),
                        active = true,
                    ),
                )
            assertEquals(expected, tables)
        }

    @Test
    fun `get table by id`() =
        runBlocking {
            val clubId = insertClub(name = "Aurora")
            val tableId =
                insertTable(clubId, tableNumber = 5, capacity = 8, minDeposit = BigDecimal("250.00"), active = false)

            val table = repository.get(tableId)
            assertNotNull(table)
            assertEquals(tableId, table!!.id)
            assertEquals(false, table.active)

            val missing = repository.get(tableId + 42)
            assertNull(missing)
        }
}
