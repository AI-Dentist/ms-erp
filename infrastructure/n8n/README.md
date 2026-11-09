# Import the ERP multichannel workflow

Import `n8n-workflow-multicanal.json` into n8n, configure credentials, then activate it. The export is intentionally inactive and contains no credential values or pinned execution data.

1. Configure the **ERP Webhook Secret** Header Auth credential: header name `X-ERP-Webhook-Secret`, value equal to MS1's `N8N_WEBHOOK_SECRET`.
2. Set n8n environment references `META_PHONE_NUMBER_ID` and `META_ACCESS_TOKEN` for the Meta free test-number API; register the authorized test recipient in Meta. The requested endpoint is pinned to v20.0: verify vendor availability before a real rollout.
3. Select the **Clinic Telegram Bot** credential and set `CHAT_ID` for the clinic default. An explicit `destinatario.telegramChatId` overrides it.
4. Select **Clinic Gmail SMTP** credentials, configure the account's supported SMTP/app-password policy, and set `GMAIL_FROM_ADDRESS`. No password or token belongs in this repository.
5. Activate the workflow and set MS1 `N8N_ENABLED=true` and `N8N_WEBHOOK_URL=http://localhost:5678/webhook/erp-notificaciones` (use a trusted reachable hostname when containers are separate).

The authenticated webhook receives the S17 nested payload, normalizes it and escapes Markdown/HTML data. A gate per channel either calls its provider or emits a skipped result. Two append joins wait for all branches; the final response excludes skipped channels and returns 200 only if every active branch succeeded (502 on provider failure). A 200 means webhook/provider acceptance, not a read receipt.

Success/error/manual execution-data retention is disabled in this export to avoid persisting contact/financial payloads. Keep that policy aligned with clinic privacy requirements. MS1 logs redacted fallback events, never full patient payloads or credentials.

Verification: `./mvnw -q -o -Dtest=MultichannelWorkflowContractTest test` validates the JSON contract and graph references; `node scripts/verify-n8n-workflow.mjs` executes 14 offline checks of committed node scripts and routing with synthetic inputs. These checks do not activate n8n or call Meta, Telegram or Gmail. A real import and authorized provider smoke test remain operational checks in an environment with n8n running and credentials configured.

The coordinator mirrors the JSON into `platform-infrastructure/automation/n8n/workflows/n8n-workflow-multicanal.json`; this repository does not modify the sibling project. Existing flat receipt/reminder payloads require their existing compatible workflow and an explicit URL override; this workflow accepts S17 payloads only.
