-- Initial database identity only; no application schema/history.
SET log_statement = 'none';
SET log_min_error_statement = 'panic';
\getenv app_password CLB_INIT_PASSWORD
CREATE ROLE clubs LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD :'app_password';
\unset app_password
REVOKE ALL ON DATABASE clubs FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE clubs TO clubs;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO clubs;
