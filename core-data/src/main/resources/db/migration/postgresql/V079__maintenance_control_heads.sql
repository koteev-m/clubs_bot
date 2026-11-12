SET LOCAL search_path = pg_catalog, "${flyway:defaultSchema}", pg_temp;
-- Inactive maintenance metadata only. No connection adoption, credential or authority issuance.
CREATE TABLE "${flyway:defaultSchema}".target_maintenance_control_heads (
 id UUID PRIMARY KEY,
 root_id UUID NOT NULL UNIQUE REFERENCES "${flyway:defaultSchema}".target_bot_connections(id) ON DELETE RESTRICT,
 authority_connection_id UUID NOT NULL UNIQUE,
 provenance_id UUID NOT NULL,
 version BIGINT NOT NULL CHECK (version > 0),
 generation BIGINT NOT NULL CHECK (generation = version),
 status VARCHAR(8) NOT NULL CHECK (status IN ('DISABLED','ENABLED')),
 manifest_id UUID NULL,
 manifest_digest VARCHAR(64) NULL,
 last_audit_id BIGINT NOT NULL REFERENCES "${flyway:defaultSchema}".audit_log(id) ON DELETE RESTRICT,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CHECK ((status='DISABLED' AND manifest_id IS NULL AND manifest_digest IS NULL) OR
        (status='ENABLED' AND manifest_id IS NOT NULL AND manifest_digest IS NOT NULL AND pg_catalog.regexp_like(manifest_digest,'^[0-9a-f]{64}$')))
);
CREATE TABLE "${flyway:defaultSchema}".target_maintenance_publications (
 control_id UUID NOT NULL REFERENCES "${flyway:defaultSchema}".target_maintenance_control_heads(id) ON DELETE RESTRICT,
 version BIGINT NOT NULL CHECK (version > 0),
 generation BIGINT NOT NULL CHECK (generation = version),
 status VARCHAR(8) NOT NULL CHECK (status IN ('DISABLED','ENABLED')),
 manifest_id UUID NULL UNIQUE,
 manifest_digest VARCHAR(64) NULL,
 audit_id BIGINT NOT NULL REFERENCES "${flyway:defaultSchema}".audit_log(id) ON DELETE RESTRICT,
 PRIMARY KEY(control_id,generation),
 CHECK ((status='DISABLED' AND manifest_id IS NULL AND manifest_digest IS NULL) OR
        (status='ENABLED' AND manifest_id IS NOT NULL AND manifest_digest IS NOT NULL AND pg_catalog.regexp_like(manifest_digest,'^[0-9a-f]{64}$')))
);

CREATE FUNCTION "${flyway:defaultSchema}".target_control_guard() RETURNS TRIGGER LANGUAGE plpgsql
SET search_path = pg_catalog, "${flyway:defaultSchema}", pg_temp AS $$
DECLARE current_head RECORD;
BEGIN
 IF TG_OP IN ('DELETE','TRUNCATE') THEN RAISE EXCEPTION 'target_control_history_immutable' USING ERRCODE='23514'; END IF;
 IF TG_TABLE_NAME='target_maintenance_publications' THEN
  IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'target_publication_immutable' USING ERRCODE='23514'; END IF;
  SELECT * INTO current_head FROM "${flyway:defaultSchema}".target_maintenance_control_heads WHERE id=NEW.control_id;
  IF NOT FOUND OR (NEW.version,NEW.generation,NEW.status,NEW.manifest_id,NEW.manifest_digest,NEW.audit_id)
   IS DISTINCT FROM (current_head.version,current_head.generation,current_head.status,
    current_head.manifest_id,current_head.manifest_digest,current_head.last_audit_id) THEN
   RAISE EXCEPTION 'target_publication_not_current' USING ERRCODE='23514'; END IF;
  RETURN NEW;
 END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.version<>1 OR NEW.generation<>1 OR NEW.status<>'DISABLED' THEN
   RAISE EXCEPTION 'target_control_initial_disabled' USING ERRCODE='23514'; END IF;
  PERFORM id FROM "${flyway:defaultSchema}".target_bot_connections
   WHERE id=NEW.root_id AND kind='PLATFORM' AND client_id IS NULL FOR UPDATE;
  IF NOT FOUND THEN
   RAISE EXCEPTION 'target_control_platform_only' USING ERRCODE='23514'; END IF;
 ELSE
  IF (NEW.id,NEW.root_id,NEW.authority_connection_id,NEW.provenance_id,NEW.created_at)
   IS DISTINCT FROM (OLD.id,OLD.root_id,OLD.authority_connection_id,OLD.provenance_id,OLD.created_at) THEN
   RAISE EXCEPTION 'target_control_identity_immutable' USING ERRCODE='23514'; END IF;
  IF NEW.version<>OLD.version+1 OR NEW.generation<>OLD.generation+1 OR NEW.last_audit_id=OLD.last_audit_id THEN
   RAISE EXCEPTION 'target_control_monotonic' USING ERRCODE='23514'; END IF;
  IF NEW.status='ENABLED' AND OLD.status<>'DISABLED' THEN
   RAISE EXCEPTION 'target_control_fence_first' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE FUNCTION "${flyway:defaultSchema}".target_control_record() RETURNS TRIGGER LANGUAGE plpgsql
SET search_path = pg_catalog, "${flyway:defaultSchema}", pg_temp AS $$
BEGIN
 INSERT INTO "${flyway:defaultSchema}".target_maintenance_publications
  (control_id,version,generation,status,manifest_id,manifest_digest,audit_id)
 VALUES(NEW.id,NEW.version,NEW.generation,NEW.status,NEW.manifest_id,NEW.manifest_digest,NEW.last_audit_id);
 RETURN NEW;
END $$;
CREATE TRIGGER target_control_guard BEFORE INSERT OR UPDATE OR DELETE ON "${flyway:defaultSchema}".target_maintenance_control_heads
 FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".target_control_guard();
CREATE TRIGGER target_control_record AFTER INSERT OR UPDATE ON "${flyway:defaultSchema}".target_maintenance_control_heads
 FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".target_control_record();
CREATE TRIGGER target_publication_guard BEFORE INSERT OR UPDATE OR DELETE ON "${flyway:defaultSchema}".target_maintenance_publications
 FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".target_control_guard();
CREATE TRIGGER target_control_truncate_guard BEFORE TRUNCATE ON "${flyway:defaultSchema}".target_maintenance_control_heads
 FOR EACH STATEMENT EXECUTE FUNCTION "${flyway:defaultSchema}".target_control_guard();
CREATE TRIGGER target_publication_truncate_guard BEFORE TRUNCATE ON "${flyway:defaultSchema}".target_maintenance_publications
 FOR EACH STATEMENT EXECUTE FUNCTION "${flyway:defaultSchema}".target_control_guard();
