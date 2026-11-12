package com.example.bot.data.client.bindings

import com.example.bot.data.client.ClientAudience
import java.util.UUID

/** Construct only from trusted server intent/configuration. No production selector exists. */
data class ServerSelectedBindingReference(
    val rootId: UUID,
    val fence: BindingFence,
    val telegramBotId: Long,
    val audience: ClientAudience,
    val mappingEpoch: Long? = null,
    val credentialRevision: Long? = null,
)

/** Backend read-only projection, not runtime DI. Never an identity, audience proof or permission. */
fun interface DurableBindingSource {
    suspend fun read(reference: ServerSelectedBindingReference?): DurableBindingRead
}

sealed interface DurableBindingRead {
    data class Inactive(
        val snapshot: DurableBindingSnapshot,
    ) : DurableBindingRead

    data class Revoked(
        val snapshot: DurableBindingSnapshot,
    ) : DurableBindingRead

    data object MissingSelection : DurableBindingRead

    data object NotFound : DurableBindingRead

    data class Rejected(
        val reason: BindingReadRejection,
    ) : DurableBindingRead

    data object Inconsistent : DurableBindingRead

    data object ReadFailed : DurableBindingRead
}

enum class BindingReadRejection { IDENTITY, FENCE, SCOPE, CLIENT_STATE, MAPPING_EPOCH, CREDENTIAL_REVISION }

enum class CredentialMaterialState { AVAILABLE, RETIRED }

data class DurableCredentialMetadata(
    val referenceId: UUID,
    val revision: Long,
    val materialId: UUID,
    val state: CredentialMaterialState,
) {
    override fun toString(): String = "DurableCredentialMetadata(redacted)"
}

/** V078 has one aggregate fence, not independently writable incarnation counters. */
data class DurableBindingSnapshot(
    val rootId: UUID,
    val telegramBotId: Long,
    val kind: BindingKind,
    val fence: BindingFence,
    val status: BindingState,
    val audience: ClientAudience,
    val mappingEpoch: Long?,
    val clientLifecycleVersion: Long?,
    val credential: DurableCredentialMetadata?,
) {
    override fun toString(): String = "DurableBindingSnapshot(metadata-only; not-authorized)"
}

/** Exact deployment schema from server configuration; never JDBC/session search_path discovery. */
class BindingStorageSchema private constructor(
    val quoted: String,
) {
    companion object {
        fun fromServerConfiguration(name: String): BindingStorageSchema {
            require(Regex("[A-Za-z_][A-Za-z0-9_]{0,62}").matches(name)) { "Invalid binding storage schema" }
            require(!name.startsWith("pg_", ignoreCase = true)) { "Non-durable binding storage schema" }
            require(!name.equals("information_schema", ignoreCase = true)) { "System binding storage schema" }
            return BindingStorageSchema("\"$name\"")
        }
    }
}
