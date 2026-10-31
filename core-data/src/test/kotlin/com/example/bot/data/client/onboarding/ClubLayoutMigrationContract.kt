package com.example.bot.data.client.onboarding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Migration assertions run unchanged through the same H2 and PostgreSQL contract inheritance. */
internal abstract class ClubLayoutMigrationContract : SimplifiedOnboardingContract() {
    @Test
    fun `V065 bootstrap uses active unique confirmed ownership preserves legacy and never revives tombstones`() {
        newFixture("64").use { previous ->
            val ownerRows =
                listOf(
                    migrationOwner(previous, 65001),
                    migrationOwner(previous, 65002, historical = true),
                    migrationOwner(previous, 65003, memberActive = false),
                    migrationOwner(previous, 65004, readActive = false),
                    migrationOwner(previous, 65005, profileActive = false),
                    migrationOwner(previous, 65006, clientActive = false),
                    migrationOwner(previous, 65007, published = true),
                    migrationOwner(previous, 65008),
                )
            // The old schema can represent conflicting immutable owner sources; ambiguity must fail closed.
            previous.execute("INSERT INTO users(id,telegram_user_id) VALUES (65009,880065009)")
            previous.execute("INSERT INTO client_memberships(client_id,user_id,is_active) VALUES (65008,65009,TRUE)")
            historicalOwner(previous, 65009, 65008, 65008)
            previous.execute("INSERT INTO users(id,telegram_user_id) VALUES (65010,880065010)")
            previous.execute("INSERT INTO client_memberships(client_id,user_id,is_active) VALUES (65001,65010,TRUE)")
            previous.execute(
                "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) VALUES " +
                    "(65001,65010,65001,'$READ_CLUB',TRUE),(65001,65010,65001,'$WRITE_PROFILE',TRUE)",
            )
            val legacy = previous.rows("SELECT * FROM user_roles ORDER BY id")
            val provenance = previous.rows("SELECT * FROM client_direct_club_profiles ORDER BY club_id")
            previous.migrate()
            val grants =
                previous.rows(
                    "SELECT club_id,user_id,is_active FROM client_club_grants " +
                        "WHERE permission_code='$WRITE_LAYOUT' ORDER BY club_id",
                )
            assertEquals(listOf(65001L, 65002L), grants.map { it.long("club_id") })
            assertEquals(listOf(65001L, 65002L), grants.map { it.long("user_id") })
            assertTrue(grants.all { it.active() })
            val audit =
                previous.rows(
                    "SELECT actor_user_id,subject_user_id,club_id FROM audit_log " +
                        "WHERE action='client.club.layout.bootstrap' ORDER BY club_id",
                )
            assertEquals(listOf(65001L, 65002L), audit.map { it.long("subject_user_id") })
            assertEquals(listOf(65001L, 65002L), audit.map { it.long("club_id") })
            assertTrue(audit.all { it["actor_user_id"] == null })
            assertEquals(legacy, previous.rows("SELECT * FROM user_roles ORDER BY id"))
            assertEquals(provenance, previous.rows("SELECT * FROM client_direct_club_profiles ORDER BY club_id"))
            assertEquals(
                ownerRows.size,
                previous.rows("SELECT club_id FROM client_clubs WHERE club_id BETWEEN 65001 AND 65008").size,
            )
            previous.execute(
                "UPDATE client_club_grants SET is_active=FALSE WHERE club_id=65001 AND permission_code='$WRITE_LAYOUT'",
            )
            val before =
                previous.rows(
                    "SELECT * FROM client_club_grants ORDER BY client_id,user_id,club_id,permission_code",
                )
            // Replay only the guarded backfill DML, in addition to Flyway's normal second migrate no-op.
            val migration =
                checkNotNull(
                    javaClass.classLoader.getResource("db/migration/${previous.vendor}/V065__client_club_layout.sql"),
                ).readText()
            migration
                .substring(migration.indexOf("INSERT INTO audit_log"))
                .split(';')
                .filter {
                    it.isNotBlank()
                }.forEach(previous::execute)
            previous.migrate()
            assertEquals(
                before,
                previous.rows("SELECT * FROM client_club_grants ORDER BY client_id,user_id,club_id,permission_code"),
            )
            assertEquals(2, previous.rows("SELECT id FROM audit_log WHERE action='client.club.layout.bootstrap'").size)
        }
    }

    private fun migrationOwner(
        previous: OnboardingFixture,
        id: Long,
        historical: Boolean = false,
        memberActive: Boolean = true,
        readActive: Boolean = true,
        profileActive: Boolean = true,
        clientActive: Boolean = true,
        published: Boolean = false,
    ): Long {
        previous.execute("INSERT INTO users(id,telegram_user_id) VALUES ($id,${880000000L + id})")
        previous.execute("INSERT INTO clients(id,display_name,is_active) VALUES ($id,'Old client $id',$clientActive)")
        previous.execute("INSERT INTO client_memberships(client_id,user_id,is_active) VALUES ($id,$id,$memberActive)")
        previous.execute(
            "INSERT INTO clubs(id,name,timezone,is_active) VALUES ($id,'Old club $id','Europe/Moscow',$published)",
        )
        previous.execute("INSERT INTO client_clubs(client_id,club_id) VALUES ($id,$id)")
        previous.execute(
            "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) VALUES " +
                "($id,$id,$id,'$READ_CLUB',$readActive),($id,$id,$id,'$WRITE_PROFILE',$profileActive)",
        )
        if (historical) {
            historicalOwner(previous, id, id, id)
        } else {
            previous.execute(
                "INSERT INTO client_direct_club_profiles(club_id,client_id,owner_user_id,venue_fingerprint," +
                    "country_code,city,address,legacy_fingerprint) VALUES ($id,$id,$id,'${digest("old-$id")}'," +
                    "'RU','Old city','Old address','${digest("legacy-$id")}')",
            )
        }
        return id
    }

    private fun historicalOwner(
        previous: OnboardingFixture,
        ownerId: Long,
        clientId: Long,
        clubId: Long,
    ) {
        val hash = digest("approved-$ownerId")
        previous.execute(
            "INSERT INTO client_club_applications(id,client_id,author_user_id,status,submitted_at," +
                "submitted_snapshot,submitted_hash,submitted_version," +
                "approved_snapshot_hash,approved_snapshot_version," +
                "venue_fingerprint,reserved_fingerprint,commercial_reference,commercial_agreed_at,platform_confirmed," +
                "decision_actor_user_id,decided_at) VALUES ($ownerId,$clientId,$ownerId,'approved',CURRENT_TIMESTAMP," +
                "'{}','$hash',1,'$hash',1,'$hash','$hash','Synthetic',CURRENT_DATE,TRUE,$ownerId,CURRENT_TIMESTAMP)",
        )
        previous.execute(
            "INSERT INTO client_club_onboarding(application_id,client_id,owner_user_id,club_id," +
                "approved_snapshot_hash,approved_snapshot_version,create_application_version) " +
                "VALUES ($ownerId,$clientId,$ownerId,$clubId,'$hash',1,1)",
        )
    }
}
