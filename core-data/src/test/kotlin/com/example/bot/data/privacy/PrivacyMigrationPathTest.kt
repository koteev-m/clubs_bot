package com.example.bot.data.privacy

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrivacyMigrationPathTest {
    @Test
    fun `privacy migration uses next available version instead of conflicting V028`() {
        val h2Migration = resourceExists("db/migration/h2/V048__privacy_phone_governance.sql")
        val postgresMigration = resourceExists("db/migration/postgresql/V048__privacy_phone_governance.sql")

        assertTrue(h2Migration)
        assertTrue(postgresMigration)
        assertFalse(resourceExists("db/migration/h2/V028__privacy_phone_governance.sql"))
        assertFalse(resourceExists("db/migration/postgresql/V028__privacy_phone_governance.sql"))
    }

    private fun resourceExists(path: String): Boolean =
        javaClass.classLoader.getResource(path) != null
}
