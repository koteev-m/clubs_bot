-- Inactive maintenance metadata only. No connection adoption, credential or authority issuance.
CREATE TABLE target_maintenance_control_heads (
 id UUID PRIMARY KEY,
 root_id UUID NOT NULL UNIQUE REFERENCES target_bot_connections(id) ON DELETE RESTRICT,
 authority_connection_id UUID NOT NULL UNIQUE,
 provenance_id UUID NOT NULL,
 version BIGINT NOT NULL CHECK (version > 0),
 generation BIGINT NOT NULL CHECK (generation = version),
 status VARCHAR(8) NOT NULL CHECK (status IN ('DISABLED','ENABLED')),
 manifest_id UUID NULL,
 manifest_digest VARCHAR(64) NULL,
 last_audit_id BIGINT NOT NULL REFERENCES audit_log(id) ON DELETE RESTRICT,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CHECK ((status='DISABLED' AND manifest_id IS NULL AND manifest_digest IS NULL) OR
        (status='ENABLED' AND manifest_id IS NOT NULL AND manifest_digest IS NOT NULL AND REGEXP_LIKE(manifest_digest,'^[0-9a-f]{64}$')))
);
CREATE TABLE target_maintenance_publications (
 control_id UUID NOT NULL REFERENCES target_maintenance_control_heads(id) ON DELETE RESTRICT,
 version BIGINT NOT NULL CHECK (version > 0),
 generation BIGINT NOT NULL CHECK (generation = version),
 status VARCHAR(8) NOT NULL CHECK (status IN ('DISABLED','ENABLED')),
 manifest_id UUID NULL UNIQUE,
 manifest_digest VARCHAR(64) NULL,
 audit_id BIGINT NOT NULL REFERENCES audit_log(id) ON DELETE RESTRICT,
 PRIMARY KEY(control_id,generation),
 CHECK ((status='DISABLED' AND manifest_id IS NULL AND manifest_digest IS NULL) OR
        (status='ENABLED' AND manifest_id IS NOT NULL AND manifest_digest IS NOT NULL AND REGEXP_LIKE(manifest_digest,'^[0-9a-f]{64}$')))
);
CREATE TRIGGER target_control_guard BEFORE INSERT, UPDATE, DELETE ON target_maintenance_control_heads
 FOR EACH ROW CALL "com.example.bot.data.client.bindings.ControlHeadGuardTrigger";
CREATE TRIGGER target_control_record AFTER INSERT, UPDATE ON target_maintenance_control_heads
 FOR EACH ROW CALL "com.example.bot.data.client.bindings.ControlHeadHistoryTrigger";
CREATE TRIGGER target_publication_guard BEFORE INSERT, UPDATE, DELETE ON target_maintenance_publications
 FOR EACH ROW CALL "com.example.bot.data.client.bindings.ControlHeadGuardTrigger";
