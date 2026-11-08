# Agent reports and authenticated automation

Existing GraphQL names and export URLs remain supported. This extension adds report fields, streamed downloads and authenticated n8n delivery without changing offline synchronization or idempotency.

## Reports

`ejecutarAgenteErp` and `listarHistorialAgente` retain the **OBJECT** `AgenteInteraccionResponse`. `RespuestaAgenteErp` is declared with the same fields, but is not the mutation return type: this deliberate compatibility decision preserves legacy fragments, introspection and generated clients.

`reporteGenerado` adds `titulo`, `sqlGenerado`, `filas` (arrays of text cells), and `resumenAnalitico` (a factual row/column summary, not invented AI conclusions). `consultaSqlEjecutada` and `filasJson` remain available. Monetary source values are still numeric in `filasJson`; downloads preserve numeric Bs formatting.

The SQL guardrail uses a closed SELECT grammar over approved reporting views. It rejects EXEC/EXECUTE and other write or procedural statements, comments and semicolons. Missing LIMIT becomes LIMIT 500; explicit limits must be between 1 and 500. The dedicated SELECT-only reporting login, JDBC row cap and query timeout remain mandatory; see the [bounded SQL contract](report-sql-guardrail.md).

| Download | Input |
| --- | --- |
| GET `/api/v1/reports/export/{reporteId}/{format}` | Existing path parameters |
| GET `/api/v1/reports/export` | `reporteId` and `format` query parameters |
| POST `/api/v1/reports/export` | JSON `{ "reporteId": "<uuid>", "format": "xlsx" }` |

All three require ADMIN and the identity that generated the cached report. Formats are `xlsx` and `pdf` only. Exports write directly to the servlet output stream (no complete byte-array response); POI/OpenPDF write to that stream, using the existing clinic letterhead and Bs formatting. Responses include the format's Content-Type, an attachment Content-Disposition, Cache-Control: no-store and nosniff. Once a streaming response starts, a transport/export failure can produce a truncated download; clients must not treat an incomplete file as success. Invalid format returns 400; missing/expired/foreign-owner report returns 404; authorization is unchanged.

## Related contracts

- [MCP tools, categories and business diagnosis](mcp-business-tools.md)
- [Authenticated n8n configuration and payload](n8n-automation.md)
