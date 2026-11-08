# Additive MCP business tools


The authenticated `/mcp` server and LLM tool allowlist expose 14 tools: the original nine plus these five additive names. The deterministic dev/local fallback remains explicitly labeled as non-AI; no OpenAI key is needed for tests.

| Canonical tool | Parameters | Authorization / result |
| --- | --- | --- |
| `crearServicioArancel` | `codigo`, `nombre`, `categoria`, `precioBs` | ADMIN; audited service creation; category persists (nullable for legacy records) and is readable as `ServicioOdontologico.categoria` |
| `registrarEgresoCaja` | `montoBs`, `categoria`, `glosa` | ADMIN/CAJERO; validated EFECTIVO expense, cashier derived from authenticated identity |
| `reprogramarCuotaPlanPago` | `cuotaId`, `nuevaFechaVencimiento` (YYYY-MM-DD) | ADMIN/CAJERO/RECEPCIONISTA; only one pending installment date changes; paid/cancelled quotas rejected; audit and change commit atomically |
| `auditarSaludNegocio` | None | ADMIN/CAJERO; read-only executive diagnosis, overdue count, today's flow, unusual expenses, recommendations |
| `dispararAlertaCobranzaWhatsapp` | Optional `cuotaIds` | ADMIN/CAJERO; same bounded/deduplicated collection use case and explicit not-sent fallback as legacy n8n tool |

Canonical money inputs reject more than two decimals, negative values and values above NUMERIC(12,2); expenses must be greater than zero. The original tool names remain registered with their original parameters and behavior. Service category is optional in storage; existing constructors and creation calls still work. Later legacy edits and logical deactivation preserve it. Whole-plan rescheduling remains distinct from the new single-quota operation: the latter preserves quota IDs, paid snapshots, amounts, other due dates and order balances while taking the existing order lock.

The approved unusual-expense heuristic compares each expense of today with **twice the mean individual expense of the preceding 30 calendar days**, excluding today. It flags strictly greater amounts only; without prior expenses, threshold is null and no anomaly is invented. The tool reports the historical count, threshold rounded to two decimals for display only (classification compares the unrounded ratio), observed unusual movement IDs/amounts and recommended actions. Recommendations never execute CRUD. “Monitorea mi negocio” includes this diagnosis alongside the existing summary/reminders; provider failure and missing-key rules remain unchanged.
