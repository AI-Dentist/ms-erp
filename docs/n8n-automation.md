# Authenticated n8n automation


| Variable | Default | Effect |
| --- | --- | --- |
| `N8N_ENABLED` | `false` | Opt-in in all profiles, including local |
| `N8N_WEBHOOK_URL` | `http://localhost:5678/webhook/erp-notificaciones` | Configurable webhook destination; old flat events require a compatible workflow/URL |
| `N8N_WEBHOOK_SECRET` | Empty | Sent as X-ERP-Webhook-Secret; empty/blank disables all delivery (fail closed) |

S17 adds a separate [multichannel route and importable workflow](multichannel-notifications.md): only that route simulates network failures in dev/local; production/unknown profiles and HTTP rejections return explicit NO_ENVIADO. Existing receipt and overdue-reminder adapters/use cases keep the semantics below.

Configure the same secret in the external n8n workflow. Never put a real secret in source control. Requests carry `pacienteNombre`, `telefono`, `montoBs`, `concepto` (`Cuota <number> de orden <uuid>`) and `fechaVencimiento`; existing `nombrePaciente`, `telefonoWhatsapp`, `vencimiento` and QR fields remain. Collection tools return `notificacionEnviada=false` and an explicit reason when disabled or unavailable; the agent never claims a reminder was delivered after a failed HTTP request. Existing timeout bounds and retryable collection claims remain unchanged.
