package com.example.bot.data.client.bindings

import java.util.UUID

enum class ControlPublicationStatus { DISABLED, ENABLED }

/** Exact server-provisioned scope, never an HTTP selector or a source of user authority. */
data class MaintenanceControlScope(
    val id: UUID,
    val rootId: UUID,
    val authorityConnectionId: UUID,
)

/** Metadata only; enabled means a bundle was published, never an ACTIVE bot or permission. */
data class MaintenanceControlHead(
    val scope: MaintenanceControlScope,
    val version: Long,
    val generation: Long,
    val status: ControlPublicationStatus,
    val manifestId: UUID?,
    val manifestDigest: String?,
) {
    init {
        require(version > 0 && generation == version)
        val complete = manifestId != null && manifestDigest?.matches(Regex("[0-9a-f]{64}")) == true
        require(
            if (status ==
                ControlPublicationStatus.ENABLED
            ) {
                complete
            } else {
                manifestId == null && manifestDigest == null
            },
        )
    }

    override fun toString(): String = "MaintenanceControlHead(metadata-only)"
}
