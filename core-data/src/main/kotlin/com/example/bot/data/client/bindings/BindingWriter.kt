package com.example.bot.data.client.bindings

import com.example.bot.data.client.onboarding.OnboardingSql
import com.example.bot.data.client.onboarding.fail
import java.time.Instant
import java.util.UUID

internal class BindingWriter(
    private val sql: OnboardingSql,
    private val audit: Long,
    private val now: Instant,
) {
    fun create(
        command: BindingCommand.Create,
        actor: Long,
    ) {
        sql.update(
            "INSERT INTO target_bot_connections(id,telegram_bot_id,kind,provenance_id,created_by," +
                "created_audit_id,last_audit_id,created_at,updated_at) " +
                "VALUES (?,?,'PLATFORM',?,?,?,?,?,?)",
            command.rootId,
            command.botId,
            command.provenanceId,
            actor,
            audit,
            audit,
            now,
            now,
        )
        incarnation(command.rootId, command.bindingId, command.provenanceId)
        sql.update(
            "UPDATE target_bot_connections SET current_binding_id=? WHERE id=?",
            command.bindingId,
            command.rootId,
        )
    }

    fun change(
        command: BindingCommand,
        before: BindingView,
    ) {
        when (command) {
            is BindingCommand.State -> state(before, command.state)
            is BindingCommand.Replace -> {
                incarnation(command.rootId, command.bindingId, command.provenanceId)
            }
            is BindingCommand.AttachReference -> attach(command, before)
            is BindingCommand.RetireMaterial -> retireSelected(command, before)
            is BindingCommand.Create -> fail("invalid_transition")
        }
        // Child guards advanced the same locked aggregate. Preserve the input CAS
        // validated under that lock, then CAS the exact post-child fence for receipt/audit.
        val fenced = BindingRows(sql).current(command.rootId, exclusive = true) ?: fail("invalid_transition")
        if (fenced.fence.bindingId != before.fence.bindingId ||
            fenced.fence.version <= before.fence.version ||
            fenced.fence.generation <= before.fence.generation
        ) {
            fail("version_conflict")
        }
        val nextBinding = (command as? BindingCommand.Replace)?.bindingId ?: before.fence.bindingId
        val count =
            sql.update(
                "UPDATE target_bot_connections SET current_binding_id=?,version=version+1," +
                    "generation=generation+1,last_audit_id=?,updated_at=? " +
                    "WHERE id=? AND current_binding_id=? AND version=? AND generation=?",
                nextBinding,
                audit,
                now,
                command.rootId,
                before.fence.bindingId,
                fenced.fence.version,
                fenced.fence.generation,
            )
        if (count != 1) fail("version_conflict")
    }

    private fun incarnation(
        root: UUID,
        binding: UUID,
        provenance: UUID,
    ) {
        sql.update(
            "INSERT INTO target_bot_bindings(id,root_id,provenance_id,created_audit_id,created_at) VALUES (?,?,?,?,?)",
            binding,
            root,
            provenance,
            audit,
            now,
        )
    }

    private fun state(
        before: BindingView,
        desired: BindingState,
    ) {
        if (desired == BindingState.REVOKED) {
            sql.update(
                "UPDATE target_credential_materials SET status='RETIRED',retired_at=?," +
                    "retired_audit_id=? WHERE root_id=? AND status='AVAILABLE'",
                now,
                audit,
                before.rootId,
            )
            sql.update(
                "UPDATE target_bot_bindings SET status='REVOKED',revoked_at=?,revoked_audit_id=? WHERE id=?",
                now,
                audit,
                before.fence.bindingId,
            )
        } else {
            sql.update("UPDATE target_bot_bindings SET status='DISABLED' WHERE id=?", before.fence.bindingId)
        }
    }

    private fun attach(
        command: BindingCommand.AttachReference,
        before: BindingView,
    ) {
        val material =
            sql
                .row(
                    "SELECT status,root_id FROM target_credential_materials WHERE id=?",
                    command.materialId,
                )?.bindingColumns()
        if (material == null) {
            sql.update(
                "INSERT INTO target_credential_materials(id,root_id,created_audit_id,created_at) VALUES (?,?,?,?)",
                command.materialId,
                command.rootId,
                audit,
                now,
            )
        } else if (material["root_id"] != command.rootId.toString() ||
            material["status"] != "AVAILABLE"
        ) {
            fail("invalid_transition")
        }
        sql.update(
            "INSERT INTO target_credential_revisions(id,root_id,material_id,revision," +
                "verified_bot_id,attestation_id,created_audit_id,created_at) VALUES (?,?,?,?,?,?,?,?)",
            command.referenceId,
            command.rootId,
            command.materialId,
            command.revision,
            before.botId,
            command.attestationId,
            audit,
            now,
        )
        sql.update(
            "UPDATE target_bot_bindings SET credential_ref=? WHERE id=?",
            command.referenceId,
            before.fence.bindingId,
        )
    }

    private fun retireSelected(
        command: BindingCommand.RetireMaterial,
        before: BindingView,
    ) {
        val rows = BindingRows(sql)
        if (!rows.materialAvailable(command.rootId, command.materialId)) fail("invalid_transition")
        sql.update(
            "UPDATE target_credential_materials SET status='RETIRED',retired_at=?," +
                "retired_audit_id=? WHERE root_id=? AND id=? AND status='AVAILABLE'",
            now,
            audit,
            command.rootId,
            command.materialId,
        )
        val selected =
            before.credentialReference?.let {
                sql
                    .row(
                        "SELECT material_id FROM target_credential_revisions WHERE id=?",
                        it,
                    )?.bindingColumns()
                    ?.get("material_id")
            }
        if (selected == command.materialId.toString()) {
            sql.update(
                "UPDATE target_bot_bindings SET credential_ref=NULL,status='DISABLED' WHERE id=?",
                before.fence.bindingId,
            )
        }
    }
}
