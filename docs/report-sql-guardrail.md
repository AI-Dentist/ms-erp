# Bounded read-only report queries

Report SQL is validated by the existing closed SELECT grammar over approved reporting views before execution. The reporting connection still uses an independent SELECT-only login, a JDBC maximum of 500 rows and a three-second query timeout.

- Missing `LIMIT` is normalized to `LIMIT 500` in the SQL actually executed and returned to callers.
- Explicit `LIMIT` values must be between 1 and 500 inclusive; values above 500 are rejected, not truncated silently.
- `EXEC` and `EXECUTE`, other non-SELECT/procedural tokens, comments and semicolons are rejected before reaching JDBC.
- Existing projection, view, predicate and function restrictions remain in force; no general SQL parser or permissive rewriting was introduced.

Focused verification: `./mvnw -q -o -Dtest=SqlReadOnlyGuardrailTest,ReportJdbcSafetyTest test`.
