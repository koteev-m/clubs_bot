#!/usr/bin/env python3
"""Opt-in disposable PG16 security probes. No live DB/SSH/configuration.

CLB_SNAPSHOT_DISPOSABLE_PG=1 python3 -B scripts/tests/test_stage_snapshot_postgres.py
Owns one no-network tmpfs container; no volumes, ports or host/source mounts.
"""
import importlib.util
import json
import os
from pathlib import Path
import selectors
import shutil
import subprocess
import time
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[2]
S = importlib.util.spec_from_file_location('collector', ROOT/'scripts/deploy/stage-readonly-snapshot-collector.py')
C = importlib.util.module_from_spec(S)
S.loader.exec_module(C)
PG_IMAGES = {
 'repository': 'postgres:16-alpine@sha256:46258a3eb38adf37e77ca5bd41f93ca8b1034f925cf37190a7a8015ba151f3ca',
 'review16.14': 'postgres@sha256:16bc17c64a573ef34162af9298258d1aec548232985b33ed7b1eac33ba35c229',
}
TABLE = """CREATE TABLE public.flyway_schema_history(
 installed_rank integer NOT NULL PRIMARY KEY,version varchar(50),description varchar(200) NOT NULL,
 type varchar(20) NOT NULL,script varchar(1000) NOT NULL,checksum integer,installed_by varchar(100) NOT NULL,
 installed_on timestamp NOT NULL DEFAULT now(),execution_time integer NOT NULL,success boolean NOT NULL);
CREATE INDEX flyway_schema_history_s_idx ON public.flyway_schema_history(success);
INSERT INTO public.flyway_schema_history(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success)
 SELECT i,lpad(i::text,3,'0'),'synthetic','SQL','fixture',i,'synthetic',1,true FROM generate_series(1,76)i;
"""


@unittest.skipUnless(os.environ.get('CLB_SNAPSHOT_DISPOSABLE_PG') == '1', 'explicit local disposable PG opt-in')
class PostgresTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.name = 'clb-snapshot-fix-' + uuid.uuid4().hex[:12]
        cls.cid = None
        cls.tools = shutil.which('docker')
        if not cls.tools:
            raise RuntimeError('Docker required for opted-in gate')
        try:
            if os.environ.get('DOCKER_HOST') and not os.environ['DOCKER_HOST'].startswith('unix://'):
                raise RuntimeError('local Docker socket required')
            endpoint = cls.docker('context','inspect','--format','{{.Endpoints.docker.Host}}').stdout.strip()
            if not endpoint.startswith('unix://'):
                raise RuntimeError('local Docker context required')
            image = PG_IMAGES[os.environ.get('CLB_SNAPSHOT_PG_FIXTURE', 'repository')]
            if cls.docker('image','inspect',image,check=False).returncode:
                cls.docker('pull',image)
            cls.cid = cls.docker('run','--detach','--rm','--pull','never','--name',cls.name,
                '--network','none','--read-only','--security-opt','no-new-privileges',
                '--tmpfs','/var/lib/postgresql/data:rw,nosuid,nodev,size=256m',
                '--tmpfs','/var/run/postgresql:rw,nosuid,nodev,size=16m','--tmpfs','/tmp:rw,nosuid,nodev,size=16m',
                '-e','POSTGRES_HOST_AUTH_METHOD=trust','-e','POSTGRES_DB=clubs',
                image,'postgres','-c','listen_addresses=').stdout.strip()
            deadline = time.monotonic()+25
            while time.monotonic()<deadline:
                if cls.sql('SELECT 1;',check=False).returncode==0:
                    break
                time.sleep(.15)
            else:
                raise RuntimeError('disposable PG startup failed')
            cls.sql('CREATE ROLE clubs LOGIN NOSUPERUSER NOCREATEROLE NOCREATEDB; ALTER SCHEMA public OWNER TO clubs;')
            cls.version = cls.sql('SHOW server_version;').stdout.strip()
            print('Disposable PostgreSQL version: '+cls.version, flush=True)
        except BaseException:
            if cls.cid: cls.docker('rm','--force',cls.cid,check=False)
            raise

    @classmethod
    def tearDownClass(cls):
        if cls.cid:
            result=cls.docker('rm','--force',cls.cid,check=False)
            if result.returncode: raise RuntimeError('disposable fixture cleanup failed')

    @classmethod
    def docker(cls,*args,data=None,check=True):
        r=subprocess.run([cls.tools,*args],input=data,capture_output=True,text=True,timeout=120 if args[0]=='pull' else 20)
        if check and r.returncode: raise RuntimeError('fixture SQL/transport failed (private diagnostics withheld)')
        return r

    @classmethod
    def psql(cls,user='postgres',app='fixture-control'):
        return [cls.tools,'exec','-i','--user','postgres',cls.cid,'/usr/bin/env','-i',
            'PATH=/usr/local/bin:/usr/bin:/bin','PGAPPNAME='+app,'/usr/local/bin/psql',
            '-X','-q','-A','-t','-w','-U',user,'-d','clubs','--set=ON_ERROR_STOP=1']

    @classmethod
    def sql(cls,query,user='postgres',check=True):
        r=subprocess.run(cls.psql(user),input=query,capture_output=True,text=True,timeout=12)
        if check and r.returncode: raise RuntimeError('fixture SQL failed (private diagnostics withheld)')
        return r

    def setUp(self):
        self.sql('DROP SCHEMA public CASCADE; CREATE SCHEMA public AUTHORIZATION clubs;')
        self.sql(TABLE,user='clubs')
        self.victims=[]

    def tearDown(self):
        for proc,app,pid in self.victims:
            self.sql('SELECT pg_catalog.pg_terminate_backend('+pid+');',check=False)
            proc.communicate(timeout=3)

    def read(self,prefix=''):
        r=subprocess.run(self.psql('clubs'),input=(prefix+C.SQL).encode(),capture_output=True,timeout=12)
        if r.returncode: return {'availability':'unknown'}
        return C.summarize_database(C.database_wire(r.stdout))

    def victim(self):
        app='clb-snapshot-control-'+uuid.uuid4().hex[:12]
        proc=subprocess.Popen(self.psql('clubs',app),stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        proc.stdin.write('SELECT pg_catalog.pg_sleep(30);\n');proc.stdin.close();proc.stdin=None
        deadline=time.monotonic()+4
        while time.monotonic()<deadline:
            pid=self.sql("SELECT pid FROM pg_catalog.pg_stat_activity WHERE application_name='"+app+"';").stdout.strip()
            if pid.isdigit():
                self.victims.append((proc,app,pid));return pid
            time.sleep(.03)
        raise RuntimeError('control session missing')

    def alive(self,pid):
        return self.sql('SELECT EXISTS(SELECT FROM pg_catalog.pg_stat_activity WHERE pid='+pid+');').stdout.strip()=='t'

    def test_ordinary_table_observed_and_no_mutation(self):
        before=self.sql('SELECT count(*),sum(checksum) FROM public.flyway_schema_history;').stdout
        value=self.read()
        self.assertEqual(value['availability'],'observed')
        self.assertEqual(value['catalog_validation'],'locked_heap_table')
        self.assertEqual(value['latest_version'],76)
        self.assertEqual(value['pending_target'],[77,78,79])
        self.assertEqual(before,self.sql('SELECT count(*),sum(checksum) FROM public.flyway_schema_history;').stdout)

    def test_view_side_effect_denied_and_control_session_survives(self):
        pid=self.victim()
        self.sql('DROP TABLE public.flyway_schema_history; CREATE VIEW public.flyway_schema_history AS '
            "SELECT 76 AS installed_rank,'076'::varchar(50) AS version,76 AS checksum,"
            'pg_catalog.pg_terminate_backend('+pid+') AS success;',user='clubs')
        self.assertEqual(self.read(),{'availability':'unknown'})
        self.assertTrue(self.alive(pid))

    def test_missing_relation_not_found_from_catalog(self):
        self.sql('DROP TABLE public.flyway_schema_history;',user='clubs')
        self.assertEqual(self.read(),{'availability':'not_found','catalog_validation':'canonical_absence'})

    def test_permission_denied_is_unknown_not_not_found(self):
        self.sql('ALTER TABLE public.flyway_schema_history OWNER TO postgres; REVOKE ALL ON public.flyway_schema_history FROM clubs;')
        self.assertEqual(self.read(),{'availability':'unknown'})

    def test_namespace_unavailable_is_unknown(self):
        self.sql('ALTER SCHEMA public OWNER TO postgres; REVOKE ALL ON SCHEMA public FROM clubs; REVOKE ALL ON SCHEMA public FROM PUBLIC;')
        self.assertEqual(self.read(),{'availability':'unknown'})

    def test_wrong_types_extra_and_missing_columns_denied(self):
        for ddl in ('ALTER COLUMN version TYPE text','ADD COLUMN unexpected integer','DROP COLUMN checksum'):
            with self.subTest(ddl=ddl):
                self.sql('BEGIN; ALTER TABLE public.flyway_schema_history '+ddl+'; COMMIT;',user='clubs')
                self.assertEqual(self.read(),{'availability':'unknown'})
                self.sql('DROP TABLE public.flyway_schema_history;',user='clubs');self.sql(TABLE,user='clubs')

    def test_rls_and_policy_hooks_denied_without_executing(self):
        pid=self.victim()
        self.sql('CREATE POLICY malicious ON public.flyway_schema_history FOR SELECT USING(pg_catalog.pg_terminate_backend('+pid+'));',user='clubs')
        for ddl in ('','ALTER TABLE public.flyway_schema_history ENABLE ROW LEVEL SECURITY;',
                    'ALTER TABLE public.flyway_schema_history FORCE ROW LEVEL SECURITY;'):
            with self.subTest(ddl=ddl):
                if ddl: self.sql(ddl,user='clubs')
                self.assertEqual(self.read(),{'availability':'unknown'})
                self.assertTrue(self.alive(pid))

    def test_rules_and_expression_indexes_denied(self):
        self.sql('CREATE RULE diagnostic_rule AS ON INSERT TO public.flyway_schema_history DO ALSO NOTHING;',user='clubs')
        self.assertEqual(self.read(),{'availability':'unknown'})
        self.sql('DROP RULE diagnostic_rule ON public.flyway_schema_history; CREATE INDEX expression_idx ON public.flyway_schema_history((checksum+1));',user='clubs')
        self.assertEqual(self.read(),{'availability':'unknown'})

    def test_materialized_and_foreign_tables_denied(self):
        self.sql("DROP TABLE public.flyway_schema_history; CREATE MATERIALIZED VIEW public.flyway_schema_history AS SELECT 1 AS installed_rank,'001'::varchar(50) AS version,1 AS checksum,true AS success;",user='clubs')
        self.assertEqual(self.read(),{'availability':'unknown'})
        self.sql("DROP MATERIALIZED VIEW public.flyway_schema_history; CREATE EXTENSION file_fdw; CREATE SERVER fixture_file FOREIGN DATA WRAPPER file_fdw; CREATE FOREIGN TABLE public.flyway_schema_history(installed_rank integer,version varchar(50),checksum integer,success boolean) SERVER fixture_file OPTIONS(filename '/tmp/nonexistent-fixture',format 'csv'); GRANT SELECT ON public.flyway_schema_history TO clubs;")
        r=self.sql(C.SQL,user='clubs',check=False)
        self.assertEqual(r.returncode,0)  # FDW was not invoked (missing file would fail).
        self.assertEqual(C.summarize_database(C.database_wire(r.stdout.encode())),{'availability':'unknown'})

    def test_hostile_search_path_and_temp_view_never_execute(self):
        pid=self.victim()
        self.sql('CREATE SCHEMA hostile AUTHORIZATION clubs;')
        prefix="CREATE FUNCTION hostile.eq_oid(oid,oid) RETURNS boolean LANGUAGE SQL AS 'SELECT pg_catalog.pg_terminate_backend("+pid+")'; CREATE OPERATOR hostile.= (LEFTARG=oid,RIGHTARG=oid,FUNCTION=hostile.eq_oid); CREATE TEMP VIEW flyway_schema_history AS SELECT 1 AS installed_rank,'001'::varchar(50) AS version,1 AS checksum,pg_catalog.pg_terminate_backend("+pid+") AS success; SET search_path=hostile,pg_catalog,pg_temp,public;\n"
        self.assertEqual(self.read(prefix)['availability'],'observed')
        self.assertTrue(self.alive(pid))

    def phased(self,marker,app='fixture-control'):
        first,rest=C.SQL.split(marker,1)
        proc=subprocess.Popen(self.psql('clubs',app),stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,bufsize=0)
        proc.stdin.write((first+marker+'\\echo FIXTURE-BOUNDARY\n').encode());proc.stdin.flush()
        with selectors.DefaultSelector() as sel:
            sel.register(proc.stdout,selectors.EVENT_READ)
            if not sel.select(5): proc.kill();proc.communicate();raise RuntimeError('phase boundary failed')
            line=proc.stdout.readline()
            if line.strip()!=b'FIXTURE-BOUNDARY': proc.kill();proc.communicate();raise RuntimeError('invalid phase response')
        return proc,rest

    def finish(self,proc,rest):
        raw,err=proc.communicate(rest.encode(),timeout=8)
        if proc.returncode:return {'availability':'unknown'}
        return C.summarize_database(C.database_wire(raw))

    def test_ddl_replacement_between_precheck_and_lock_fails_closed(self):
        pid=self.victim()
        proc,rest=self.phased('\\gset pre_\n')
        try:
            self.sql("DROP TABLE public.flyway_schema_history; CREATE VIEW public.flyway_schema_history AS SELECT 1 AS installed_rank,'001'::varchar(50) AS version,1 AS checksum,pg_catalog.pg_terminate_backend("+pid+") AS success;",user='clubs')
            self.assertEqual(self.finish(proc,rest),{'availability':'unknown'})
            self.assertTrue(self.alive(pid))
        finally:
            if proc.poll() is None: proc.kill();proc.communicate()

    def test_post_lock_ddl_waits_until_read_transaction_finishes(self):
        proc,rest=self.phased('\\gset locked_\n')
        ddl=subprocess.Popen(self.psql('clubs','fixture-ddl-waiter'),stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        ddl.stdin.write('ALTER TABLE public.flyway_schema_history RENAME TO previous_history;\n');ddl.stdin.close();ddl.stdin=None
        try:
            deadline=time.monotonic()+3
            while time.monotonic()<deadline:
                wait=self.sql("SELECT wait_event_type FROM pg_catalog.pg_stat_activity WHERE application_name='fixture-ddl-waiter';").stdout.strip()
                if wait=='Lock':break
                time.sleep(.03)
            self.assertEqual(wait,'Lock')
            self.assertEqual(self.finish(proc,rest)['availability'],'observed')
            ddl.communicate(timeout=5);self.assertEqual(ddl.returncode,0)
        finally:
            for p in (proc,ddl):
                if p.poll() is None:p.kill();p.communicate()

    def test_ordinary_table_replacement_invalidates_prechecked_oid(self):
        proc,rest=self.phased('\\gset pre_\n')
        try:
            self.sql('DROP TABLE public.flyway_schema_history;',user='clubs');self.sql(TABLE,user='clubs')
            self.assertEqual(self.finish(proc,rest),{'availability':'unknown'})
            self.assertEqual(self.read()['availability'],'observed')
        finally:
            if proc.poll() is None:proc.kill();proc.communicate()

    def test_namespace_rename_bytea_replacement_cannot_be_observed(self):
        # F1: NOCREATEDB does not exclude CREATE on this existing database.
        # The unsafe posture must refuse even when schema DDL commits mid-read.
        self.sql('GRANT CREATE ON DATABASE clubs TO clubs;')
        proc=None;blocker=None
        try:
            old_oid=self.sql("SELECT 'public.flyway_schema_history'::regclass::oid;").stdout.strip()
            # A fixture-only advisory gate pauses a vulnerable reader *after*
            # locked_ validation. A fixed reader refuses before reaching it.
            blocker=subprocess.Popen(self.psql('clubs','fixture-namespace-gate'),stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,stderr=subprocess.PIPE,bufsize=0)
            blocker.stdin.write(b'BEGIN; SELECT pg_catalog.pg_advisory_xact_lock(71931);\n');blocker.stdin.flush()
            deadline=time.monotonic()+2
            while time.monotonic()<deadline:
                held=self.sql("SELECT EXISTS(SELECT FROM pg_catalog.pg_locks l JOIN pg_catalog.pg_stat_activity a ON a.pid=l.pid "
                    "WHERE a.application_name='fixture-namespace-gate' AND l.locktype='advisory' AND l.granted);").stdout.strip()
                if held=='t':break
                time.sleep(.02)
            self.assertEqual(held,'t')
            script=C.SQL.replace('\\gset locked_\n','\\gset locked_\n'
                'SELECT pg_catalog.pg_advisory_xact_lock(71931) AS fixture_pause\n\\gset\n',1)
            proc=subprocess.Popen(self.psql('clubs','fixture-namespace-reader'),stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,stderr=subprocess.PIPE)
            proc.stdin.write(script.encode());proc.stdin.close();proc.stdin=None
            deadline=time.monotonic()+2
            while time.monotonic()<deadline:
                if proc.poll() is not None:break
                waiting=self.sql("SELECT EXISTS(SELECT FROM pg_catalog.pg_stat_activity WHERE "
                    "application_name='fixture-namespace-reader' AND wait_event='advisory');").stdout.strip()
                if waiting=='t':break
                time.sleep(.02)
            else:self.fail('reader neither refused nor reached post-validation gate')
            self.sql("ALTER SCHEMA public RENAME TO previous_public; CREATE SCHEMA public; "
                "CREATE TABLE public.flyway_schema_history(installed_rank bytea,version bytea,checksum bytea,success bytea); "
                "INSERT INTO public.flyway_schema_history VALUES "
                "(decode('000003e7','hex'),decode('393939','hex'),decode('000003e7','hex'),decode('01','hex'));",user='clubs')
            self.assertNotEqual(old_oid,self.sql("SELECT 'public.flyway_schema_history'::regclass::oid;").stdout.strip())
            blocker.communicate(b'ROLLBACK;\n',timeout=3)
            raw,_=proc.communicate(timeout=5)
            self.assertEqual(proc.returncode,0)  # No timeout/error can masquerade as a safe refusal.
            self.assertEqual(C.summarize_database(C.database_wire(raw)),{'availability':'unknown'})
            self.assertEqual(self.sql('SELECT max(installed_rank) FROM previous_public.flyway_schema_history;').stdout.strip(),'76')
        finally:
            if proc is not None and proc.poll() is None:proc.kill();proc.communicate()
            if blocker is not None and blocker.poll() is None:blocker.kill();blocker.communicate()
            self.sql('REVOKE CREATE ON DATABASE clubs FROM clubs; DROP SCHEMA IF EXISTS previous_public CASCADE;')

    def test_namespace_create_privilege_is_rechecked_after_relation_lock(self):
        proc,rest=self.phased('\\gset pre_\n')
        try:
            self.sql('GRANT CREATE ON DATABASE clubs TO clubs;')
            self.assertEqual(self.finish(proc,rest),{'availability':'unknown'})
        finally:
            if proc.poll() is None:proc.kill();proc.communicate()
            self.sql('REVOKE CREATE ON DATABASE clubs FROM clubs;')

    def test_same_relation_oid_in_replaced_namespace_is_unknown(self):
        old_oid=self.sql("SELECT 'public.flyway_schema_history'::regclass::oid;").stdout.strip()
        proc,rest=self.phased('\\gset pre_\n')
        try:
            # Fixture administrator moves the same heap between checks; post-lock
            # relation identity alone would miss the changed namespace identity.
            self.sql('ALTER SCHEMA public RENAME TO previous_namespace; '
                'CREATE SCHEMA public AUTHORIZATION clubs; '
                'ALTER TABLE previous_namespace.flyway_schema_history SET SCHEMA public;')
            self.assertEqual(old_oid,self.sql("SELECT 'public.flyway_schema_history'::regclass::oid;").stdout.strip())
            self.assertEqual(self.finish(proc,rest),{'availability':'unknown'})
        finally:
            if proc.poll() is None:proc.kill();proc.communicate()
            self.sql('DROP SCHEMA IF EXISTS previous_namespace CASCADE;')

    def test_namespace_mutability_via_public_or_inherited_owner_is_unknown(self):
        self.sql('CREATE ROLE fixture_namespace_member LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT; '
            'GRANT clubs TO fixture_namespace_member;')
        try:
            for grantee in ('PUBLIC','fixture_namespace_member'):
                with self.subTest(grantee=grantee):
                    self.sql('GRANT CREATE ON DATABASE clubs TO '+grantee+';')
                    try:
                        if grantee!='PUBLIC':
                            self.assertEqual(self.sql("SELECT has_database_privilege('clubs','clubs','CREATE');").stdout.strip(),'f')
                        self.assertEqual(self.read(),{'availability':'unknown'})
                        if grantee!='PUBLIC':
                            self.sql('ALTER SCHEMA public RENAME TO member_can_rename; '
                                'ALTER SCHEMA member_can_rename RENAME TO public;',user=grantee)
                    finally:self.sql('REVOKE CREATE ON DATABASE clubs FROM '+grantee+';')
        finally:
            self.sql('REVOKE clubs FROM fixture_namespace_member; DROP ROLE fixture_namespace_member;')
        self.assertEqual(self.read()['availability'],'observed')

    def test_database_owner_with_schema_ownership_is_unknown(self):
        self.sql('ALTER DATABASE clubs OWNER TO clubs;')
        try:self.assertEqual(self.read(),{'availability':'unknown'})
        finally:self.sql('ALTER DATABASE clubs OWNER TO postgres;')

    def test_supported_namespace_cannot_be_renamed_after_locked_admission(self):
        proc,rest=self.phased('\\gset locked_\n')
        try:
            rename=self.sql('ALTER SCHEMA public RENAME TO forbidden_rename;',user='clubs',check=False)
            self.assertNotEqual(rename.returncode,0)
            self.assertEqual(self.finish(proc,rest)['availability'],'observed')
        finally:
            if proc.poll() is None:proc.kill();proc.communicate()

    def test_post_lock_rls_and_rules_cannot_change_select_hooks(self):
        for ddl in ('ALTER TABLE public.flyway_schema_history ENABLE ROW LEVEL SECURITY;',
                    'CREATE RULE waiting_rule AS ON INSERT TO public.flyway_schema_history DO ALSO NOTHING;'):
            with self.subTest(ddl=ddl):
                proc,rest=self.phased('\\gset locked_\n')
                writer=subprocess.Popen(self.psql('clubs','fixture-hook-waiter'),stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
                writer.stdin.write(ddl);writer.stdin.close();writer.stdin=None
                try:
                    deadline=time.monotonic()+3
                    while time.monotonic()<deadline:
                        wait=self.sql("SELECT wait_event_type FROM pg_catalog.pg_stat_activity WHERE application_name='fixture-hook-waiter';").stdout.strip()
                        if wait=='Lock':break
                        time.sleep(.03)
                    self.assertEqual(wait,'Lock')
                    self.assertEqual(self.finish(proc,rest)['availability'],'observed')
                    writer.communicate(timeout=5);self.assertEqual(writer.returncode,0)
                finally:
                    for process in (proc,writer):
                        if process.poll() is None:process.kill();process.communicate()
                self.sql('ALTER TABLE public.flyway_schema_history DISABLE ROW LEVEL SECURITY;',user='clubs')

    def test_concurrent_index_predicate_is_not_executed_by_data_read(self):
        pid=self.victim()
        self.sql("CREATE FUNCTION public.plan_hook() RETURNS boolean LANGUAGE plpgsql IMMUTABLE AS $$ BEGIN IF pg_catalog.current_setting('application_name')='fixture-data-reader' THEN PERFORM pg_catalog.pg_terminate_backend("+pid+"); END IF; RETURN true; END $$;",user='clubs')
        proc,rest=self.phased('\\gset locked_\n','fixture-data-reader')
        try:
            # CREATE INDEX is compatible with ACCESS SHARE. Its immutable
            # predicate is evaluated under the creator's separate context.
            self.sql('CREATE INDEX concurrent_predicate ON public.flyway_schema_history(checksum) WHERE public.plan_hook();',user='clubs')
            self.assertTrue(self.alive(pid))
            self.assertEqual(self.finish(proc,rest)['availability'],'observed')
            self.assertTrue(self.alive(pid))
            # Negative control: a normal SELECT planner evaluates that predicate.
            control=subprocess.run(self.psql('clubs','fixture-data-reader'),input='SELECT count(*) FROM public.flyway_schema_history;',capture_output=True,text=True,timeout=5)
            self.assertEqual(control.returncode,0)
            self.assertFalse(self.alive(pid))
        finally:
            if proc.poll() is None:proc.kill();proc.communicate()

    def test_idle_partial_script_cannot_retain_relation_lock(self):
        proc,rest=self.phased('\\gset locked_\n','fixture-idle-reader')
        try:
            deadline=time.monotonic()+5
            while time.monotonic()<deadline:
                present=self.sql("SELECT EXISTS(SELECT FROM pg_catalog.pg_stat_activity WHERE application_name='fixture-idle-reader');").stdout.strip()
                if present=='f':break
                time.sleep(.1)
            self.assertEqual(present,'f')
            self.sql('SET lock_timeout=\'500ms\'; ALTER TABLE public.flyway_schema_history RENAME TO released_history;',user='clubs')
            proc.communicate(b'ROLLBACK;\n',timeout=3)
            self.assertNotEqual(proc.returncode,0)
        finally:
            if proc.poll() is None:proc.kill();proc.communicate()

    def test_lock_timeout_is_unknown(self):
        blocker=subprocess.Popen(self.psql('clubs','fixture-blocker'),stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        blocker.stdin.write('BEGIN; LOCK TABLE public.flyway_schema_history IN ACCESS EXCLUSIVE MODE; SELECT pg_sleep(3); ROLLBACK;\n');blocker.stdin.close();blocker.stdin=None
        try:
            deadline=time.monotonic()+2
            while time.monotonic()<deadline:
                held=self.sql("SELECT EXISTS(SELECT FROM pg_catalog.pg_locks l JOIN pg_catalog.pg_stat_activity a ON a.pid=l.pid WHERE a.application_name='fixture-blocker' AND mode='AccessExclusiveLock' AND granted);").stdout.strip()
                if held=='t':break
                time.sleep(.03)
            self.assertEqual(held,'t')
            self.assertEqual(self.read(),{'availability':'unknown'})
        finally:blocker.communicate(timeout=6)


if __name__=='__main__': unittest.main(verbosity=2)
