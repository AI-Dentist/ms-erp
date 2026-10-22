-- OPERATOR ONLY. Never sourced by Flyway, the application, or startup automation.
-- Run after V5 using a privileged provisioning connection to the application database.
-- Supply psql variables report_user, report_password, app_user through your approved secret
-- injection mechanism (avoid command-line secrets, shell history and statement logging).
-- Example invocation (variables already set in a protected operator psql session):
--   \i scripts/reporting-role.sql
-- Then configure ERP_REPORT_DB_USER / ERP_REPORT_DB_PASSWORD and optionally ERP_REPORT_DB_URL.
-- JDBC URLs must not contain user/password parameters (they override JDBC credential properties).
-- The role MUST be new, distinct from the application login and own no database/schema/object.
-- No existing login/password, PUBLIC grant, or database-wide permission is changed here.
-- This script grants no base-table, sequence, system-table, write, membership or ownership rights.
-- Existing PUBLIC grants are not silently revoked: execution rejects unsafe effective permissions.
-- Use a trusted database with no PUBLIC write/base-table SELECT grants; audit independently.
--
-- Reporting contract:
--   report_income(day, category, payment_method, amount_bs, movement_count): income by local day
--   report_orders(status, order_count, total_bs, balance_bs): totals by status, no patient/order IDs
--   report_services(code, price_bs, active): no free-text names/descriptions or personal data
-- SQL: single ASCII SELECT (<=4000 chars), bare allowlisted columns or *, aggregate
-- SUM/COUNT/AVG/MIN/MAX(column), COUNT(*); optional WHERE column comparison literal
-- joined by AND, GROUP BY columns, ORDER BY columns [ASC|DESC], LIMIT 1..500.
-- Literals: bounded decimal, true/false or short simple quoted ASCII text/date.
-- No aliases/joins/subqueries/CTEs/unions/casts/comments/semicolons/schema qualifiers/functions
-- beyond those aggregates. Unsupported or uncertain syntax fails closed; DB errors are generic Spanish.
-- Real AI requires OPENAI_API_KEY. No key => unavailable, never canned SQL. The original bounded
-- question is an untrusted user message; only approved schema metadata accompanies it, never DB rows.
-- Dates, ranges, categories and metrics must be preserved; unsupported requests fail closed.
-- Do not include personal data in questions or enable prompt/provider payload logging.
-- Configure the existing Spring AI OpenAI provider/model; see README.md for environment settings.
-- Cache: in-memory FIFO 64 total reports, 600-second absolute TTL, owner = authenticated principal name.
-- Export (ADMIN + owner only): GET /api/v1/reports/export/{xlsx|pdf}?reporteId=UUID
-- 400 text/plain UTF-8: Formato de reporte no permitido
-- 404 text/plain UTF-8: Reporte no disponible (missing/malformed/unknown/expired/wrong owner)
-- 403: authenticated non-ADMIN, before cache access; unauthenticated production requests use JWT security.
-- 503 text/plain UTF-8: No se pudo exportar el reporte
-- Successful exports use cached rows only, never execute SQL. XLSX uses string cells for text,
-- numeric cells for amounts. PDF contains headers/rows. No files are stored.
-- All handled exports: Cache-Control: no-store, X-Content-Type-Options: nosniff.
-- Success: attachment; filename="reporte-<canonical UUID>.<xlsx|pdf>", exact MIME for format.
-- Runtime limits: pool=2, acquire/query=3s, socket=5s, rows=500, columns=16, cell text=128.

\set ON_ERROR_STOP on
\if :{?report_user}
\else
  \echo 'Missing report_user'
  \quit 3
\endif
\if :{?report_password}
\else
  \echo 'Missing report_password'
  \quit 3
\endif
\if :{?app_user}
\else
  \echo 'Missing app_user'
  \quit 3
\endif
SELECT length(:'report_user') BETWEEN 1 AND 63
       AND lower(:'report_user') <> lower(:'app_user')
       AND length(:'report_password') >= 16
       AND NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'report_user') AS role_safe \gset
\if :role_safe
  BEGIN;
  SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD %L',
                :'report_user', :'report_password') \gexec
  SELECT format('ALTER ROLE %I SET default_transaction_read_only = on', :'report_user') \gexec
  SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'report_user') \gexec
  SELECT format('GRANT USAGE ON SCHEMA public TO %I', :'report_user') \gexec
  SELECT format('GRANT SELECT ON public.report_income, public.report_orders, public.report_services TO %I', :'report_user') \gexec
  COMMIT;
\else
  \echo 'Refusing existing, blank or shared role, or password shorter than 16 characters'
  \quit 3
\endif
