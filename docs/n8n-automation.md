# Authenticated n8n automation


| Variable | Default | Effect |
| --- | --- | --- |
| `N8N_ENABLED` | `false` | Opt-in in all profiles, including local |
| `N8N_WEBHOOK_URL` | Existing local webhook URL | Destination for receipt and collection events |
| `N8N_WEBHOOK_SECRET` | Empty | Sent as X-ERP-Webhook-Secret; empty/blank disables all delivery (fail closed) |

Configure the same secret in the external n8n workflow. Never put a real secret in source control. Requests carry `pacienteNombre`, `telefono`, `montoBs`, `concepto` (`Cuota <number> de orden <uuid>`) and `fechaVencimiento`; existing `nombrePaciente`, `telefonoWhatsapp`, `vencimiento` and QR fields remain. Collection tools return `notificacionEnviada=false` and an explicit reason when disabled or unavailable; the agent never claims a reminder was delivered after a failed HTTP request. Existing timeout bounds and retryable collection claims remain unchanged.
