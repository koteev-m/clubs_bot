package com.example.bot.data.client.bindings

import com.example.bot.data.client.ClientAudience
import java.sql.ResultSet
import java.util.UUID

internal class InvalidBindingMetadata : RuntimeException()

/** Validates canonical shape separately from the trusted caller's exact expected pins. */
internal class DurableBindingDecoder(
    private val selected: ServerSelectedBindingReference,
) {
    fun decode(rows: ResultSet): DurableBindingRead {
        if (!rows.next() || rows.getObject("binding_id") == null) return DurableBindingRead.NotFound
        val snapshot = header(rows)
        val clubs = clubs(rows, snapshot)
        val audience =
            when (snapshot.kind) {
                BindingKind.PLATFORM -> {
                    valid(snapshot.clientId == null && clubs.isEmpty())
                    ClientAudience.Platform
                }
                BindingKind.BRANDED -> ClientAudience.Client(requireValue(snapshot.clientId), clubs)
            }
        val result =
            DurableBindingSnapshot(
                snapshot.rootId,
                snapshot.botId,
                snapshot.kind,
                snapshot.fence,
                snapshot.status,
                audience,
                snapshot.epoch,
                snapshot.lifecycle,
                snapshot.credential,
            )
        val rejection = rejection(result, snapshot)
        return when {
            rejection != null -> DurableBindingRead.Rejected(rejection)
            result.status == BindingState.REVOKED -> DurableBindingRead.Revoked(result)
            else -> DurableBindingRead.Inactive(result)
        }
    }

    private fun header(r: ResultSet): Header {
        val root = requireValue(r.uuid("root_id"))
        val binding = requireValue(r.uuid("binding_id"))
        val version = requireValue(r.number("version"))
        val generation = requireValue(r.number("generation"))
        val bot = requireValue(r.number("telegram_bot_id"))
        valid(version > 0 && generation > 0 && bot > 0)
        valid(r.uuid("binding_root") == root)
        val kind = BindingKind.entries.singleOrNull { it.name == r.getString("kind") }
        val status = BindingState.entries.singleOrNull { it.name == r.getString("binding_status") }
        valid(status != null && status != BindingState.ACTIVE)
        val revoked = r.getObject("revoked_at") != null && r.number("revoked_audit_id") != null
        valid(
            if (status ==
                BindingState.REVOKED
            ) {
                revoked
            } else {
                r.getObject("revoked_at") == null && r.number("revoked_audit_id") == null
            },
        )
        return Header(
            root,
            bot,
            requireValue(kind),
            BindingFence(binding, version, generation),
            requireValue(status),
            r.uuid("current_binding_id"),
            r.number("client_id"),
            r.number("target_mapping_epoch"),
            r.number("epoch_head"),
            r.number("lifecycle_version"),
            r.number("canonical_client"),
            r.getBoolean("client_active") && r.getObject("archived_at") == null,
            credential(r, root, bot),
        )
    }

    private fun credential(
        r: ResultSet,
        root: UUID,
        bot: Long,
    ): DurableCredentialMetadata? {
        val ref = r.uuid("credential_ref") ?: return null
        valid(r.uuid("reference_id") == ref && r.uuid("reference_root") == root)
        valid(r.uuid("material_root") == root && r.number("verified_bot_id") == bot)
        val revision = requireValue(r.number("revision"))
        valid(revision > 0)
        val state = CredentialMaterialState.entries.singleOrNull { it.name == r.getString("material_status") }
        valid(state != null)
        val retired = r.getObject("retired_at") != null && r.number("retired_audit_id") != null
        valid(
            if (state ==
                CredentialMaterialState.RETIRED
            ) {
                retired
            } else {
                r.getObject("retired_at") == null &&
                    r.number("retired_audit_id") == null
            },
        )
        return DurableCredentialMetadata(ref, revision, requireValue(r.uuid("material_id")), requireValue(state))
    }

    private fun clubs(
        rows: ResultSet,
        header: Header,
    ): Set<Long> {
        val clubs = mutableSetOf<Long>()
        do {
            val club = rows.number("club_id")
            if (club != null) {
                valid(club > 0 && clubs.add(club))
                valid(rows.uuid("scope_root") == header.rootId && rows.number("scope_client") == header.clientId)
                valid(rows.number("mapped_club") == club && rows.number("canonical_club") == club)
            }
        } while (rows.next())
        return clubs
    }

    private fun rejection(
        s: DurableBindingSnapshot,
        h: Header,
    ): BindingReadRejection? =
        when {
            s.rootId != selected.rootId || s.telegramBotId != selected.telegramBotId -> BindingReadRejection.IDENTITY
            h.current != selected.fence.bindingId || s.fence != selected.fence -> BindingReadRejection.FENCE
            !sameScope(s.audience, selected.audience) -> BindingReadRejection.SCOPE
            s.kind == BindingKind.BRANDED && !clientValid(h) -> BindingReadRejection.CLIENT_STATE
            !epochValid(h) -> BindingReadRejection.MAPPING_EPOCH
            s.credential?.revision != selected.credentialRevision -> BindingReadRejection.CREDENTIAL_REVISION
            else -> null
        }

    private fun clientValid(h: Header): Boolean =
        h.clientId != null && h.canonicalClient == h.clientId && h.clientActive && (h.lifecycle ?: 0) > 0

    private fun epochValid(h: Header): Boolean =
        when (h.kind) {
            BindingKind.PLATFORM -> selected.mappingEpoch == null && h.epoch == null && h.head == null
            BindingKind.BRANDED -> (h.epoch ?: 0) > 0 && h.epoch == h.head && h.epoch == selected.mappingEpoch
        }

    private fun sameScope(
        actual: ClientAudience,
        expected: ClientAudience,
    ): Boolean =
        when {
            actual is ClientAudience.Platform -> expected is ClientAudience.Platform
            actual is ClientAudience.Client && expected is ClientAudience.Client ->
                actual.allowedClubIds.isNotEmpty() &&
                    actual.clientId == expected.clientId &&
                    actual.allowedClubIds == expected.allowedClubIds
            else -> false
        }

    private data class Header(
        val rootId: UUID,
        val botId: Long,
        val kind: BindingKind,
        val fence: BindingFence,
        val status: BindingState,
        val current: UUID?,
        val clientId: Long?,
        val epoch: Long?,
        val head: Long?,
        val lifecycle: Long?,
        val canonicalClient: Long?,
        val clientActive: Boolean,
        val credential: DurableCredentialMetadata?,
    )
}

private fun ResultSet.uuid(column: String): UUID? = getObject(column, UUID::class.java)

private fun ResultSet.number(column: String): Long? = getObject(column)?.let { (it as Number).toLong() }

private fun valid(condition: Boolean) {
    if (!condition) throw InvalidBindingMetadata()
}

private fun <T : Any> requireValue(value: T?): T = value ?: throw InvalidBindingMetadata()
