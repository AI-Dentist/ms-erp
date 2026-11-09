# Multichannel execution policy

The S17 application use case and transport are separate from the original receipt/overdue-reminder port. Their result does not change the legacy ledger, retries or failure semantics.

- Live delivery requires enablement, a configured URL, a nonblank webhook secret and a successful HTTP 2xx response. The secret is transmitted only as `X-ERP-Webhook-Secret`.
- Only explicit dev/local profiles allow `SIMULADO_SIN_N8N` / `SIMULADO_OK` for unavailable/disabled transport. A prod/production profile overrides dev/local; unknown/default profiles fail closed.
- HTTP rejections and invalid configuration return `exito=false`, `NO_ENVIADO`, even in development. A simulation is not proof of real delivery; a timed-out request may still finish remotely.
- The use case requires ADMIN/CAJERO and validates the caller-supplied name, E.164 phone, email, date, bounded positive money and active channels before transport. Empty/null channels select WhatsApp, Telegram and Gmail.
- No patient database lookup supplies contact data. Names, contacts, message and financial detail are redacted from failure logs, including exception messages and credentials.

`MultichannelExecutionPolicyTest` verifies disabled profiles, production precedence and non-network rejection. The public MCP/GraphQL contract and demonstration are documented in [multichannel notifications](multichannel-notifications.md).
