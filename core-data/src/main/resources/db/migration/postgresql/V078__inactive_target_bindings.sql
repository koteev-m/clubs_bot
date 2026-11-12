-- Migration schema is server-selected by Flyway; never derived from caller search_path.
SET LOCAL search_path = pg_catalog, "${flyway:defaultSchema}", pg_temp;
-- Inactive TARGET metadata only. No bot/client/staff adoption, credentials or grants.
-- A security high-water mark, not a client catalogue. No FK to clients: deletion preserves it.
CREATE TABLE "${flyway:defaultSchema}".target_client_mapping_epochs (
 client_id BIGINT PRIMARY KEY CHECK (client_id > 0),
 epoch BIGINT NOT NULL CHECK (epoch > 0)
);
ALTER TABLE "${flyway:defaultSchema}".clients ADD COLUMN target_mapping_epoch BIGINT NOT NULL DEFAULT 1 CHECK (target_mapping_epoch > 0);

CREATE TABLE "${flyway:defaultSchema}".target_bot_connections (
 id UUID PRIMARY KEY,
 telegram_bot_id BIGINT NOT NULL UNIQUE CHECK (telegram_bot_id > 0),
 kind VARCHAR(8) NOT NULL CHECK (kind IN ('PLATFORM','BRANDED')),
 client_id BIGINT NULL,
 current_binding_id UUID NULL,
 version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
 generation BIGINT NOT NULL DEFAULT 1 CHECK (generation > 0),
 provenance_id UUID NOT NULL,
 created_by BIGINT NOT NULL REFERENCES "${flyway:defaultSchema}".users(id) ON DELETE RESTRICT,
 created_audit_id BIGINT NOT NULL REFERENCES "${flyway:defaultSchema}".audit_log(id) ON DELETE RESTRICT,
 last_audit_id BIGINT NOT NULL REFERENCES "${flyway:defaultSchema}".audit_log(id) ON DELETE RESTRICT,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(id,client_id), UNIQUE(id,telegram_bot_id),
 FOREIGN KEY(client_id) REFERENCES "${flyway:defaultSchema}".clients(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
 FOREIGN KEY(client_id) REFERENCES "${flyway:defaultSchema}".target_client_mapping_epochs(client_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
 CHECK ((kind='PLATFORM' AND client_id IS NULL) OR (kind='BRANDED' AND client_id IS NOT NULL))
);
CREATE TABLE "${flyway:defaultSchema}".target_credential_materials (
 id UUID PRIMARY KEY,
 root_id UUID NOT NULL REFERENCES "${flyway:defaultSchema}".target_bot_connections(id) ON DELETE RESTRICT,
 status VARCHAR(9) NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE','RETIRED')),
 created_audit_id BIGINT NOT NULL REFERENCES "${flyway:defaultSchema}".audit_log(id) ON DELETE RESTRICT,
 retired_audit_id BIGINT NULL REFERENCES "${flyway:defaultSchema}".audit_log(id) ON DELETE RESTRICT,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 retired_at TIMESTAMP WITH TIME ZONE NULL,
 UNIQUE(root_id,id),
 CHECK ((status='AVAILABLE' AND retired_at IS NULL AND retired_audit_id IS NULL) OR
        (status='RETIRED' AND retired_at IS NOT NULL AND retired_audit_id IS NOT NULL))
);
CREATE TABLE "${flyway:defaultSchema}".target_credential_revisions (
 id UUID PRIMARY KEY,
 root_id UUID NOT NULL,
 material_id UUID NOT NULL,
 revision BIGINT NOT NULL CHECK (revision > 0),
 verified_bot_id BIGINT NOT NULL CHECK (verified_bot_id > 0),
 attestation_id UUID NOT NULL,
 created_audit_id BIGINT NOT NULL REFERENCES "${flyway:defaultSchema}".audit_log(id) ON DELETE RESTRICT,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(root_id,id), UNIQUE(root_id,revision),
 FOREIGN KEY(root_id,verified_bot_id) REFERENCES "${flyway:defaultSchema}".target_bot_connections(id,telegram_bot_id) ON DELETE RESTRICT,
 FOREIGN KEY(root_id,material_id) REFERENCES "${flyway:defaultSchema}".target_credential_materials(root_id,id) ON DELETE RESTRICT
);
CREATE TABLE "${flyway:defaultSchema}".target_bot_bindings (
 id UUID PRIMARY KEY,
 root_id UUID NOT NULL REFERENCES "${flyway:defaultSchema}".target_bot_connections(id) ON DELETE RESTRICT,
 status VARCHAR(8) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ACTIVE','DISABLED','REVOKED')),
 credential_ref UUID NULL,
 provenance_id UUID NOT NULL,
 created_audit_id BIGINT NOT NULL REFERENCES "${flyway:defaultSchema}".audit_log(id) ON DELETE RESTRICT,
 revoked_audit_id BIGINT NULL REFERENCES "${flyway:defaultSchema}".audit_log(id) ON DELETE RESTRICT,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 revoked_at TIMESTAMP WITH TIME ZONE NULL,
 UNIQUE(root_id,id),
 FOREIGN KEY(root_id,credential_ref) REFERENCES "${flyway:defaultSchema}".target_credential_revisions(root_id,id) ON DELETE RESTRICT,
 CHECK ((status<>'REVOKED' AND revoked_at IS NULL AND revoked_audit_id IS NULL) OR
        (status='REVOKED' AND revoked_at IS NOT NULL AND revoked_audit_id IS NOT NULL))
);
ALTER TABLE "${flyway:defaultSchema}".target_bot_connections ADD CONSTRAINT target_root_current_binding_fk
 FOREIGN KEY(id,current_binding_id) REFERENCES "${flyway:defaultSchema}".target_bot_bindings(root_id,id) ON DELETE RESTRICT;
CREATE TABLE "${flyway:defaultSchema}".target_binding_clubs (
 root_id UUID NOT NULL,
 binding_id UUID NOT NULL,
 client_id BIGINT NOT NULL CHECK (client_id > 0),
 club_id BIGINT NOT NULL CHECK (club_id > 0),
 PRIMARY KEY(binding_id,club_id),
 FOREIGN KEY(root_id,client_id) REFERENCES "${flyway:defaultSchema}".target_bot_connections(id,client_id) ON DELETE RESTRICT,
 FOREIGN KEY(root_id,binding_id) REFERENCES "${flyway:defaultSchema}".target_bot_bindings(root_id,id) ON DELETE RESTRICT,
 FOREIGN KEY(client_id,club_id) REFERENCES "${flyway:defaultSchema}".client_clubs(client_id,club_id) ON UPDATE RESTRICT ON DELETE RESTRICT
);
CREATE TABLE "${flyway:defaultSchema}".target_binding_commands (
 actor_user_id BIGINT NOT NULL REFERENCES "${flyway:defaultSchema}".users(id) ON DELETE RESTRICT,
 authority_connection_id UUID NOT NULL,
 key_hash VARCHAR(64) NOT NULL CHECK (pg_catalog.regexp_like(key_hash,'^[0-9a-f]{64}$')),
 request_hash VARCHAR(64) NOT NULL CHECK (pg_catalog.regexp_like(request_hash,'^[0-9a-f]{64}$')),
 root_id UUID NOT NULL,
 binding_id UUID NOT NULL,
 result_version BIGINT NOT NULL CHECK (result_version > 0),
 result_generation BIGINT NOT NULL CHECK (result_generation > 0),
 audit_id BIGINT NOT NULL REFERENCES "${flyway:defaultSchema}".audit_log(id) ON DELETE RESTRICT,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(actor_user_id,authority_connection_id,key_hash),
 FOREIGN KEY(root_id,binding_id) REFERENCES "${flyway:defaultSchema}".target_bot_bindings(root_id,id) ON DELETE RESTRICT
);

CREATE FUNCTION "${flyway:defaultSchema}".target_epoch_next(client_key BIGINT, minimum_epoch BIGINT) RETURNS BIGINT LANGUAGE plpgsql
SET search_path = pg_catalog, "${flyway:defaultSchema}", pg_temp AS $$
DECLARE result BIGINT;
BEGIN
 INSERT INTO "${flyway:defaultSchema}".target_client_mapping_epochs(client_id,epoch) VALUES (client_key,minimum_epoch)
 ON CONFLICT(client_id) DO UPDATE SET epoch="${flyway:defaultSchema}".target_client_mapping_epochs.epoch+1 RETURNING epoch INTO result;
 RETURN result;
END $$;
CREATE FUNCTION "${flyway:defaultSchema}".target_client_epoch_guard() RETURNS TRIGGER LANGUAGE plpgsql
SET search_path = pg_catalog, "${flyway:defaultSchema}", pg_temp AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.target_mapping_epoch<>1 THEN RAISE EXCEPTION 'target_epoch_initial' USING ERRCODE='23514'; END IF;
  NEW.target_mapping_epoch:="${flyway:defaultSchema}".target_epoch_next(NEW.id,1);
 ELSIF TG_OP='DELETE' THEN
  PERFORM "${flyway:defaultSchema}".target_epoch_next(OLD.id,OLD.target_mapping_epoch+1);
  RETURN OLD;
 ELSE
  IF NEW.id<>OLD.id THEN
   PERFORM "${flyway:defaultSchema}".target_epoch_next(OLD.id,OLD.target_mapping_epoch+1);
   NEW.target_mapping_epoch:="${flyway:defaultSchema}".target_epoch_next(NEW.id,1);
   RETURN NEW;
  END IF;
  IF NEW.target_mapping_epoch<>OLD.target_mapping_epoch AND
     (OLD.target_mapping_epoch=9223372036854775807 OR NEW.target_mapping_epoch<>OLD.target_mapping_epoch+1) THEN
   RAISE EXCEPTION 'target_epoch_monotonic' USING ERRCODE='23514';
  END IF;
  IF NEW.target_mapping_epoch<>OLD.target_mapping_epoch OR NEW.is_active IS DISTINCT FROM OLD.is_active OR
     NEW.archived_at IS DISTINCT FROM OLD.archived_at OR NEW.lifecycle_version IS DISTINCT FROM OLD.lifecycle_version THEN
   NEW.target_mapping_epoch:="${flyway:defaultSchema}".target_epoch_next(OLD.id,OLD.target_mapping_epoch+1);
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER target_client_epoch_guard BEFORE INSERT OR UPDATE OR DELETE ON "${flyway:defaultSchema}".clients FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".target_client_epoch_guard();
CREATE FUNCTION "${flyway:defaultSchema}".target_mapping_epoch_bump() RETURNS TRIGGER LANGUAGE plpgsql
SET search_path = pg_catalog, "${flyway:defaultSchema}", pg_temp AS $$
DECLARE old_client BIGINT; new_client BIGINT; affected BIGINT;
BEGIN
 IF TG_OP='TRUNCATE' THEN RAISE EXCEPTION 'target_mapping_truncate_blocked' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' AND NEW.client_id=OLD.client_id AND NEW.club_id=OLD.club_id THEN RETURN NEW; END IF;
 IF TG_OP<>'INSERT' THEN old_client:=OLD.client_id; END IF;
 IF TG_OP<>'DELETE' THEN new_client:=NEW.client_id; END IF;
 FOR affected IN SELECT DISTINCT x FROM pg_catalog.unnest(ARRAY[old_client,new_client]) x WHERE x IS NOT NULL ORDER BY x LOOP
  UPDATE "${flyway:defaultSchema}".clients SET target_mapping_epoch=target_mapping_epoch+1 WHERE id=affected;
 END LOOP;
 IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;
CREATE TRIGGER target_mapping_epoch_bump AFTER INSERT OR UPDATE OR DELETE ON "${flyway:defaultSchema}".client_clubs
 FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".target_mapping_epoch_bump();
CREATE TRIGGER target_mapping_truncate_guard BEFORE TRUNCATE ON "${flyway:defaultSchema}".client_clubs
 FOR EACH STATEMENT EXECUTE FUNCTION "${flyway:defaultSchema}".target_mapping_epoch_bump();
-- Child writes cannot wait for a parent held by a root-first service transaction.
-- They fail closed on contention, then fence each successful security-relevant row.
CREATE FUNCTION "${flyway:defaultSchema}".target_child_root_lock(root_key UUID) RETURNS UUID LANGUAGE plpgsql
SET search_path = pg_catalog, "${flyway:defaultSchema}", pg_temp AS $$
DECLARE binding_key UUID;
BEGIN
 SELECT current_binding_id INTO binding_key FROM "${flyway:defaultSchema}".target_bot_connections WHERE id=root_key FOR UPDATE NOWAIT;
 IF NOT FOUND THEN RAISE EXCEPTION 'target_child_root_unknown' USING ERRCODE='23514'; END IF;
 RETURN binding_key;
END $$;
CREATE FUNCTION "${flyway:defaultSchema}".target_child_root_bump(root_key UUID) RETURNS VOID LANGUAGE plpgsql
SET search_path = pg_catalog, "${flyway:defaultSchema}", pg_temp AS $$
BEGIN
 UPDATE "${flyway:defaultSchema}".target_bot_connections SET version=version+1,generation=generation+1,updated_at=CURRENT_TIMESTAMP WHERE id=root_key;
 IF NOT FOUND THEN RAISE EXCEPTION 'target_child_root_unknown' USING ERRCODE='23514'; END IF;
END $$;
CREATE FUNCTION "${flyway:defaultSchema}".target_metadata_guard() RETURNS TRIGGER LANGUAGE plpgsql
SET search_path = pg_catalog, "${flyway:defaultSchema}", pg_temp AS $$
DECLARE terminal BOOLEAN; child_root UUID; selected_binding UUID;
BEGIN
 IF TG_OP<>'TRUNCATE' AND TG_TABLE_NAME IN
  ('target_bot_bindings','target_credential_materials','target_credential_revisions','target_binding_clubs') THEN
  IF TG_OP='INSERT' THEN child_root:=NEW.root_id; ELSE child_root:=OLD.root_id; END IF;
  selected_binding:="${flyway:defaultSchema}".target_child_root_lock(child_root);
  IF selected_binding IS NULL AND NOT (TG_OP='INSERT' AND TG_TABLE_NAME='target_bot_bindings') THEN
   RAISE EXCEPTION 'target_child_root_uninitialized' USING ERRCODE='23514'; END IF;
 END IF;
 IF TG_OP='TRUNCATE' OR TG_OP='DELETE' THEN
  IF TG_TABLE_NAME='target_binding_clubs' AND TG_OP='DELETE' THEN
   SELECT status='REVOKED' INTO terminal FROM "${flyway:defaultSchema}".target_bot_bindings WHERE id=OLD.binding_id;
   IF terminal IS DISTINCT FROM FALSE THEN RAISE EXCEPTION 'target_scope_terminal' USING ERRCODE='23514'; END IF;
   PERFORM "${flyway:defaultSchema}".target_child_root_bump(child_root);
   RETURN OLD;
  END IF;
  RAISE EXCEPTION 'target_history_immutable' USING ERRCODE='23514';
 END IF;
 IF TG_TABLE_NAME='target_binding_commands' THEN
  IF TG_OP='UPDATE' THEN RAISE EXCEPTION 'target_receipt_immutable' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='target_client_mapping_epochs' THEN
  IF TG_OP='UPDATE' AND (NEW.client_id<>OLD.client_id OR NEW.epoch<>OLD.epoch+1) THEN
   RAISE EXCEPTION 'target_epoch_head_monotonic' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='target_bot_connections' THEN
  IF TG_OP='INSERT' THEN
   IF NEW.version<>1 OR NEW.generation<>1 OR NEW.current_binding_id IS NOT NULL THEN
    RAISE EXCEPTION 'target_root_initial' USING ERRCODE='23514'; END IF;
  ELSE
   IF (NEW.id,NEW.telegram_bot_id,NEW.kind,NEW.client_id,NEW.provenance_id,NEW.created_by,NEW.created_audit_id,NEW.created_at)
      IS DISTINCT FROM (OLD.id,OLD.telegram_bot_id,OLD.kind,OLD.client_id,OLD.provenance_id,OLD.created_by,OLD.created_audit_id,OLD.created_at) THEN
    RAISE EXCEPTION 'target_root_identity_immutable' USING ERRCODE='23514'; END IF;
   IF NEW.current_binding_id IS DISTINCT FROM OLD.current_binding_id THEN
    SELECT status='PENDING' INTO terminal FROM "${flyway:defaultSchema}".target_bot_bindings WHERE id=NEW.current_binding_id;
    IF terminal IS DISTINCT FROM TRUE THEN RAISE EXCEPTION 'target_current_requires_new_pending' USING ERRCODE='23514'; END IF;
   END IF;
   IF NOT (OLD.current_binding_id IS NULL AND NEW.current_binding_id IS NOT NULL AND OLD.version=1 AND OLD.generation=1 AND NEW.version=1 AND NEW.generation=1) THEN
    IF NEW.version<>OLD.version+1 OR NEW.generation<>OLD.generation+1 OR NEW.current_binding_id IS NULL THEN
     RAISE EXCEPTION 'target_root_monotonic' USING ERRCODE='23514'; END IF;
    IF NEW.current_binding_id IS DISTINCT FROM OLD.current_binding_id THEN
     SELECT status='REVOKED' INTO terminal FROM "${flyway:defaultSchema}".target_bot_bindings WHERE id=OLD.current_binding_id;
     IF NOT terminal THEN RAISE EXCEPTION 'target_incarnation_terminal_required' USING ERRCODE='23514'; END IF;
    END IF;
   END IF;
  END IF;
 ELSIF TG_TABLE_NAME='target_bot_bindings' THEN
  IF NEW.status='ACTIVE' THEN RAISE EXCEPTION 'target_activation_not_enabled' USING ERRCODE='23514'; END IF;
  IF TG_OP='UPDATE' AND (OLD.status='REVOKED' OR
    (NEW.id,NEW.root_id,NEW.provenance_id,NEW.created_audit_id,NEW.created_at) IS DISTINCT FROM
    (OLD.id,OLD.root_id,OLD.provenance_id,OLD.created_audit_id,OLD.created_at)) THEN
   RAISE EXCEPTION 'target_binding_terminal_or_immutable' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='target_credential_revisions' THEN
  IF TG_OP='UPDATE' THEN RAISE EXCEPTION 'target_reference_immutable' USING ERRCODE='23514'; END IF;
  SELECT status='RETIRED' INTO terminal FROM "${flyway:defaultSchema}".target_credential_materials WHERE id=NEW.material_id;
  IF terminal IS DISTINCT FROM FALSE THEN RAISE EXCEPTION 'target_material_retired_or_unknown' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='target_credential_materials' THEN
  IF TG_OP='INSERT' AND NEW.status<>'AVAILABLE' THEN RAISE EXCEPTION 'target_material_initial' USING ERRCODE='23514'; END IF;
  IF TG_OP='UPDATE' AND (OLD.status='RETIRED' OR NEW.status<>'RETIRED' OR
    (NEW.id,NEW.root_id,NEW.created_audit_id,NEW.created_at) IS DISTINCT FROM (OLD.id,OLD.root_id,OLD.created_audit_id,OLD.created_at)) THEN
   RAISE EXCEPTION 'target_material_terminal' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='target_binding_clubs' THEN
  IF TG_OP='UPDATE' THEN RAISE EXCEPTION 'target_scope_replace_required' USING ERRCODE='23514'; END IF;
  SELECT status='REVOKED' INTO terminal FROM "${flyway:defaultSchema}".target_bot_bindings WHERE id=NEW.binding_id;
  IF terminal THEN RAISE EXCEPTION 'target_scope_terminal' USING ERRCODE='23514'; END IF;
 END IF;
 -- A fresh, unselected bootstrap binding has no readable/current receipt yet.
 IF child_root IS NOT NULL AND selected_binding IS NOT NULL THEN
  PERFORM "${flyway:defaultSchema}".target_child_root_bump(child_root);
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER target_bot_connections_guard BEFORE INSERT OR UPDATE OR DELETE ON "${flyway:defaultSchema}".target_bot_connections FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".target_metadata_guard();
CREATE TRIGGER target_bot_connections_truncate_guard BEFORE TRUNCATE ON "${flyway:defaultSchema}".target_bot_connections FOR EACH STATEMENT EXECUTE FUNCTION "${flyway:defaultSchema}".target_metadata_guard();
CREATE TRIGGER target_bot_bindings_guard BEFORE INSERT OR UPDATE OR DELETE ON "${flyway:defaultSchema}".target_bot_bindings FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".target_metadata_guard();
CREATE TRIGGER target_bot_bindings_truncate_guard BEFORE TRUNCATE ON "${flyway:defaultSchema}".target_bot_bindings FOR EACH STATEMENT EXECUTE FUNCTION "${flyway:defaultSchema}".target_metadata_guard();
CREATE TRIGGER target_credential_materials_guard BEFORE INSERT OR UPDATE OR DELETE ON "${flyway:defaultSchema}".target_credential_materials FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".target_metadata_guard();
CREATE TRIGGER target_credential_materials_truncate_guard BEFORE TRUNCATE ON "${flyway:defaultSchema}".target_credential_materials FOR EACH STATEMENT EXECUTE FUNCTION "${flyway:defaultSchema}".target_metadata_guard();
CREATE TRIGGER target_credential_revisions_guard BEFORE INSERT OR UPDATE OR DELETE ON "${flyway:defaultSchema}".target_credential_revisions FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".target_metadata_guard();
CREATE TRIGGER target_credential_revisions_truncate_guard BEFORE TRUNCATE ON "${flyway:defaultSchema}".target_credential_revisions FOR EACH STATEMENT EXECUTE FUNCTION "${flyway:defaultSchema}".target_metadata_guard();
CREATE TRIGGER target_binding_clubs_guard BEFORE INSERT OR UPDATE OR DELETE ON "${flyway:defaultSchema}".target_binding_clubs FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".target_metadata_guard();
CREATE TRIGGER target_binding_clubs_truncate_guard BEFORE TRUNCATE ON "${flyway:defaultSchema}".target_binding_clubs FOR EACH STATEMENT EXECUTE FUNCTION "${flyway:defaultSchema}".target_metadata_guard();
CREATE TRIGGER target_epoch_head_guard BEFORE INSERT OR UPDATE OR DELETE ON "${flyway:defaultSchema}".target_client_mapping_epochs FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".target_metadata_guard();
CREATE TRIGGER target_epoch_head_truncate_guard BEFORE TRUNCATE ON "${flyway:defaultSchema}".target_client_mapping_epochs FOR EACH STATEMENT EXECUTE FUNCTION "${flyway:defaultSchema}".target_metadata_guard();
CREATE TRIGGER target_binding_commands_guard BEFORE INSERT OR UPDATE OR DELETE ON "${flyway:defaultSchema}".target_binding_commands FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".target_metadata_guard();
CREATE TRIGGER target_binding_commands_truncate_guard BEFORE TRUNCATE ON "${flyway:defaultSchema}".target_binding_commands FOR EACH STATEMENT EXECUTE FUNCTION "${flyway:defaultSchema}".target_metadata_guard();
