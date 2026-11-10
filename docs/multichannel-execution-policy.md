# Multichannel execution policy

The S17 application use case and transport are separate from the original receipt/overdue-reminder port. Their result does not change the legacy ledger, retries or failure semantics.

- Live webhook acceptance requires enablement, a configured URL, a nonblank webhook secret and HTTP 2xx. The secret is transmitted only as `X-ERP-Webhook-Secret`. The response body is discarded: acceptance does not prove provider or recipient delivery.
- Only explicit dev/local profiles allow `exito=true`, `SIMULADO_SIN_N8N` / `SIMULADO_OK` for disabled/blank configuration or network unavailability. No notification is thereby confirmed sent. A prod/production profile overrides dev/local; unknown/default profiles return `exito=false`, `NO_ENVIADO`.
- HTTP rejections and other runtime/configuration failures after attempting transport return `exito=false`, `NO_ENVIADO`, even in development. A timed-out request may still finish remotely.
- The use case requires ADMIN/CAJERO and validates the caller-supplied name, E.164 phone, email, date, bounded positive money and active channels before transport. Empty/null channels select WhatsApp, Telegram and Gmail.
- No patient database lookup supplies contact data. Names, contacts, message and financial detail are redacted from failure logs, including exception messages and credentials.

`MultichannelExecutionPolicyTest` verifies disabled profiles, production precedence and non-network rejection. The public MCP/GraphQL contract and demonstration are documented in [multichannel notifications](multichannel-notifications.md).
