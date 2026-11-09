# Multichannel collection notifications (S17)

`dispararNotificacionMulticanal` adds WhatsApp Meta, Telegram and Gmail without changing legacy receipt or overdue-reminder delivery semantics. Both notification tools require ADMIN or CAJERO on the **current request**, including callers that reuse another user's MCP session.

## Tool inputs

| Parameter | Meaning |
| --- | --- |
| `pacienteNombre` | Required name, at most 150 characters |
| `telefono` | Required E.164 number, e.g. `+59170012345` |
| `email` | Recipient address; omitted/blank defaults to the demonstration address `paciente@ejemplo.com` |
| `telegramChatId` | Optional; omitted/blank sends `""` so n8n uses `CHAT_ID`, and reports destination `Chat Clínica` |
| `montoBs` | Positive NUMERIC(12,2)-bounded amount, no fractional rounding |
| `concepto` | Required description, at most 500 characters |
| `fechaVencimiento` | Required valid YYYY-MM-DD date |
| `canales` | Array, comma-separated text or `TODOS`; omitted/null/empty means WHATSAPP, TELEGRAM, GMAIL |

Channel names are normalized to uppercase, deduplicated in requested order, and restricted to those three providers. Control characters, invalid contact/date/channel input and invalid money are rejected **before any HTTP request**. In real deployments always supply an explicitly authorized recipient email; the example default is not a substitute for patient contact data.

The existing `dispararAlertaCobranzaWhatsapp` MCP name accepts the same eight data fields. It also retains optional `cuotaIds` for compatibility: with no multichannel fields, quota-based/no-argument callers use the original overdue-reminder use case, not the new simulation path. Mixing nonempty quota IDs and explicit multichannel data is rejected. The old Java quota-list overload remains available.

## Delivery and result

Set `N8N_WEBHOOK_URL` (default `http://localhost:5678/webhook/erp-notificaciones`), `N8N_ENABLED=true` and `N8N_WEBHOOK_SECRET` matching the n8n Header Auth credential. Blank secrets still disable live transport. Connection/read timeouts retain the 100–5000 ms bounds, with 1000/2000 ms defaults.

Each POST carries `tipoEvento=NOTIFICACION_COBRANZA_MULTICANAL`, `canalesActivos`, nested `destinatario`, nested `datosFinancieros` (`moneda=BOB`, `simbolo=Bs`) and `mensajeFormateado`. Financial amounts and message text use two decimal places. No database lookup supplies recipient data for this route.

| Condition | `exito` | `modoEjecucion` | Per-channel `estado` |
| --- | --- | --- | --- |
| Authenticated configured n8n returns 2xx | true | N8N_LIVE | ENVIADO |
| Network failure/timeout or disabled/unconfigured transport in dev/local only | true | SIMULADO_SIN_N8N | SIMULADO_OK |
| prod/production/default without available transport, or HTTP/configuration rejection in any profile | false | NO_ENVIADO | NO_ENVIADO |

A prod/production profile always prevents simulation, even alongside local/dev. Simulation is explicitly **not a delivery confirmation** (a timed-out webhook may still finish remotely; do not automatically retry it) and never sets `notificacionN8nEnviada=true`. Errors log only the mode, validated channel names, failure class and a payload with recipient, financial detail and message redacted; credentials and patient/contact text are never logged. A 2xx response is webhook acceptance, not proof of final recipient delivery or read status.

Providers are `META_CLOUD_TEST_NUMBER`, `TELEGRAM_BOT_API` and `GMAIL_SMTP`. Destinations are the supplied phone, chat ID (or `Chat Clínica`) and email respectively.

## Agent and GraphQL

The deterministic dev/local fallback recognizes **Notificar cobro pendiente por WhatsApp, Telegram y Gmail**. Without explicit data it uses only labeled synthetic demonstration values: Juan Pérez, +59170012345, paciente@ejemplo.com, Bs 350.00, Cuota 2/3 - Tratamiento de Ortodoncia, 2026-11-05. It never reads real patient data for this demonstration. Explicit notifications use `pacienteNombre="..." telefono=... montoBs=... concepto="..." fechaVencimiento=...` with optional `email`, `telegramChatId` and `canales`; incomplete explicit data is not silently replaced by demo data. Negated notification instructions do not send anything.

`AgenteInteraccionResponse` and the same-shape `RespuestaAgenteErp` add nullable `notificacionMulticanal` containing `exito`, `modoEjecucion` and `resultadosPorCanal { canal proveedor estado destino }`. Root return types and old fields stay intact. The structured result is per invocation and is not separately persisted with recipient PII: history readback returns null for that field rather than inventing a new delivery result. Existing history still retains its previous instruction/answer metadata policy.

## Workflow setup

Import `infrastructure/n8n/n8n-workflow-multicanal.json` (inactive by default), select/configure its Header Auth, Telegram bot and Gmail SMTP credentials, and set `META_PHONE_NUMBER_ID`, `META_ACCESS_TOKEN`, `CHAT_ID` and `GMAIL_FROM_ADDRESS` in n8n. Header Auth must check `X-ERP-Webhook-Secret` against the same secret configured in MS1. No real credential value is committed.

Activate the workflow to use production path `POST /webhook/erp-notificaciones` (not `/webhook-test`). Branches gate on active channels; WhatsApp uses the requested v20.0 Meta endpoint, Telegram sends escaped Markdown, and Gmail sends escaped HTML with subject Aviso de Cobranza - Clínica Dental. Join all branches before responding; provider failures must not be confirmed as successful delivery. Workflow execution-data retention is disabled by default to avoid storing recipient payloads.

Verification without provider credentials: `./mvnw -q -o -Dtest=MultichannelNotificationHttpTest,MultichannelExecutionPolicyTest,MultichannelWorkflowContractTest test` and `node scripts/verify-n8n-workflow.mjs`. The Node check exercises committed Code-node logic with synthetic inputs; it is not a live n8n import or provider smoke test.

The workflow accepts the S17 nested payload. Existing integrations using the old flat receipt/reminder events should keep their compatible workflow and explicitly override the URL; the legacy Java transport itself is unchanged. The cross-repository coordinator owns mirroring this artifact into platform-infrastructure and frontend badge integration.
