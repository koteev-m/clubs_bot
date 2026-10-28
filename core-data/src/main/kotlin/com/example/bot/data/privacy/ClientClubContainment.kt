package com.example.bot.data.privacy

import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.QueryBuilder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.selectAll

/** Durable onboarding ownership boundary; legacy roles never authorize these clubs. */
object ClientClubMappings : Table("client_clubs") {
    val clubId = long("club_id")
}

/** SQL predicate evaluated before a legacy query reads any club-owned payload. */
fun unmappedClub(clubId: Expression<*>): Op<Boolean> = UnmappedClubPredicate(clubId)

private class UnmappedClubPredicate(
    private val clubId: Expression<*>,
) : Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append(
            "NOT EXISTS (SELECT 1 FROM client_clubs private_client_club WHERE private_client_club.club_id = ",
        )
        queryBuilder.append(clubId)
        queryBuilder.append(")")
    }
}

/** Ownership-only check in the caller's existing Exposed transaction. No cache or active-state bypass. */
fun isMappedClub(clubId: Long): Boolean =
    ClientClubMappings
        .selectAll()
        .where { ClientClubMappings.clubId eq clubId }
        .limit(1)
        .any()

/** A descendant lookup resolves only ownership; its private payload remains outside the outer SELECT. */
fun unmappedResource(
    kind: LegacyResource,
    id: Expression<*>,
): Op<Boolean> = UnmappedResourcePredicate(kind, id)

private class UnmappedResourcePredicate(
    private val kind: LegacyResource,
    private val id: Expression<*>,
) : Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append(
            "NOT EXISTS (SELECT 1 FROM client_clubs private_descendant WHERE private_descendant.club_id IN (",
        )
        queryBuilder.append(kind.ownership.substringBefore('?'))
        queryBuilder.append("CAST(")
        queryBuilder.append(id)
        queryBuilder.append(" AS VARCHAR)" + kind.ownership.substringAfter('?') + "))")
    }
}
