-- Inactive TARGET metadata only. No bot/client/staff adoption, credentials or grants.
-- A security high-water mark, not a client catalogue. No FK to clients: deletion preserves it.
CREATE TABLE target_client_mapping_epochs (
 client_id BIGINT PRIMARY KEY CHECK (client_id > 0),
 epoch BIGINT NOT NULL CHECK (epoch > 0)
);
ALTER TABLE clients ADD COLUMN target_mapping_epoch BIGINT NOT NULL DEFAULT 1 CHECK (target_mapping_epoch > 0);

CREATE TABLE target_bot_connections (
 id UUID PRIMARY KEY,
 telegram_bot_id BIGINT NOT NULL UNIQUE CHECK (telegram_bot_id > 0),
 kind VARCHAR(8) NOT NULL CHECK (kind IN ('PLATFORM','BRANDED')),
 client_id BIGINT NULL,
 current_binding_id UUID NULL,
 version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
 generation BIGINT NOT NULL DEFAULT 1 CHECK (generation > 0),
 provenance_id UUID NOT NULL,
 created_by BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
 created_audit_id BIGINT NOT NULL REFERENCES audit_log(id) ON DELETE RESTRICT,
 last_audit_id BIGINT NOT NULL REFERENCES audit_log(id) ON DELETE RESTRICT,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(id,client_id), UNIQUE(id,telegram_bot_id),
 FOREIGN KEY(client_id) REFERENCES clients(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
 FOREIGN KEY(client_id) REFERENCES target_client_mapping_epochs(client_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
 CHECK ((kind='PLATFORM' AND client_id IS NULL) OR (kind='BRANDED' AND client_id IS NOT NULL))
);
CREATE TABLE target_credential_materials (
 id UUID PRIMARY KEY,
 root_id UUID NOT NULL REFERENCES target_bot_connections(id) ON DELETE RESTRICT,
 status VARCHAR(9) NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE','RETIRED')),
 created_audit_id BIGINT NOT NULL REFERENCES audit_log(id) ON DELETE RESTRICT,
 retired_audit_id BIGINT NULL REFERENCES audit_log(id) ON DELETE RESTRICT,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 retired_at TIMESTAMP WITH TIME ZONE NULL,
 UNIQUE(root_id,id),
 CHECK ((status='AVAILABLE' AND retired_at IS NULL AND retired_audit_id IS NULL) OR
        (status='RETIRED' AND retired_at IS NOT NULL AND retired_audit_id IS NOT NULL))
);
CREATE TABLE target_credential_revisions (
 id UUID PRIMARY KEY,
 root_id UUID NOT NULL,
 material_id UUID NOT NULL,
 revision BIGINT NOT NULL CHECK (revision > 0),
 verified_bot_id BIGINT NOT NULL CHECK (verified_bot_id > 0),
 attestation_id UUID NOT NULL,
 created_audit_id BIGINT NOT NULL REFERENCES audit_log(id) ON DELETE RESTRICT,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(root_id,id), UNIQUE(root_id,revision),
 FOREIGN KEY(root_id,verified_bot_id) REFERENCES target_bot_connections(id,telegram_bot_id) ON DELETE RESTRICT,
 FOREIGN KEY(root_id,material_id) REFERENCES target_credential_materials(root_id,id) ON DELETE RESTRICT
);
CREATE TABLE target_bot_bindings (
 id UUID PRIMARY KEY,
 root_id UUID NOT NULL REFERENCES target_bot_connections(id) ON DELETE RESTRICT,
 status VARCHAR(8) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ACTIVE','DISABLED','REVOKED')),
 credential_ref UUID NULL,
 provenance_id UUID NOT NULL,
 created_audit_id BIGINT NOT NULL REFERENCES audit_log(id) ON DELETE RESTRICT,
 revoked_audit_id BIGINT NULL REFERENCES audit_log(id) ON DELETE RESTRICT,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 revoked_at TIMESTAMP WITH TIME ZONE NULL,
 UNIQUE(root_id,id),
 FOREIGN KEY(root_id,credential_ref) REFERENCES target_credential_revisions(root_id,id) ON DELETE RESTRICT,
 CHECK ((status<>'REVOKED' AND revoked_at IS NULL AND revoked_audit_id IS NULL) OR
        (status='REVOKED' AND revoked_at IS NOT NULL AND revoked_audit_id IS NOT NULL))
);
ALTER TABLE target_bot_connections ADD CONSTRAINT target_root_current_binding_fk
 FOREIGN KEY(id,current_binding_id) REFERENCES target_bot_bindings(root_id,id) ON DELETE RESTRICT;
CREATE TABLE target_binding_clubs (
 root_id UUID NOT NULL,
 binding_id UUID NOT NULL,
 client_id BIGINT NOT NULL CHECK (client_id > 0),
 club_id BIGINT NOT NULL CHECK (club_id > 0),
 PRIMARY KEY(binding_id,club_id),
 FOREIGN KEY(root_id,client_id) REFERENCES target_bot_connections(id,client_id) ON DELETE RESTRICT,
 FOREIGN KEY(root_id,binding_id) REFERENCES target_bot_bindings(root_id,id) ON DELETE RESTRICT,
 FOREIGN KEY(client_id,club_id) REFERENCES client_clubs(client_id,club_id) ON UPDATE RESTRICT ON DELETE RESTRICT
);
CREATE TABLE target_binding_commands (
 actor_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
 authority_connection_id UUID NOT NULL,
 key_hash VARCHAR(64) NOT NULL CHECK (REGEXP_LIKE(key_hash,'^[0-9a-f]{64}$')),
 request_hash VARCHAR(64) NOT NULL CHECK (REGEXP_LIKE(request_hash,'^[0-9a-f]{64}$')),
 root_id UUID NOT NULL,
 binding_id UUID NOT NULL,
 result_version BIGINT NOT NULL CHECK (result_version > 0),
 result_generation BIGINT NOT NULL CHECK (result_generation > 0),
 audit_id BIGINT NOT NULL REFERENCES audit_log(id) ON DELETE RESTRICT,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(actor_user_id,authority_connection_id,key_hash),
 FOREIGN KEY(root_id,binding_id) REFERENCES target_bot_bindings(root_id,id) ON DELETE RESTRICT
);

CREATE TRIGGER target_client_epoch_guard BEFORE INSERT, UPDATE, DELETE ON clients
 FOR EACH ROW CALL "com.example.bot.data.client.bindings.ClientMappingEpochTrigger";
CREATE TRIGGER target_mapping_epoch_bump AFTER INSERT, UPDATE, DELETE ON client_clubs
 FOR EACH ROW CALL "com.example.bot.data.client.bindings.ClientMappingEpochTrigger";
CREATE TRIGGER target_bot_connections_guard BEFORE INSERT, UPDATE, DELETE ON target_bot_connections FOR EACH ROW CALL "com.example.bot.data.client.bindings.TargetMetadataGuardTrigger";
CREATE TRIGGER target_bot_bindings_guard BEFORE INSERT, UPDATE, DELETE ON target_bot_bindings FOR EACH ROW CALL "com.example.bot.data.client.bindings.TargetMetadataGuardTrigger";
CREATE TRIGGER target_credential_materials_guard BEFORE INSERT, UPDATE, DELETE ON target_credential_materials FOR EACH ROW CALL "com.example.bot.data.client.bindings.TargetMetadataGuardTrigger";
CREATE TRIGGER target_credential_revisions_guard BEFORE INSERT, UPDATE, DELETE ON target_credential_revisions FOR EACH ROW CALL "com.example.bot.data.client.bindings.TargetMetadataGuardTrigger";
CREATE TRIGGER target_binding_clubs_guard BEFORE INSERT, UPDATE, DELETE ON target_binding_clubs FOR EACH ROW CALL "com.example.bot.data.client.bindings.TargetMetadataGuardTrigger";
CREATE TRIGGER target_epoch_head_guard BEFORE INSERT, UPDATE, DELETE ON target_client_mapping_epochs FOR EACH ROW CALL "com.example.bot.data.client.bindings.TargetMetadataGuardTrigger";
CREATE TRIGGER target_binding_commands_guard BEFORE INSERT, UPDATE, DELETE ON target_binding_commands FOR EACH ROW CALL "com.example.bot.data.client.bindings.TargetMetadataGuardTrigger";
