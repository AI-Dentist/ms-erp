# Authenticated n8n automation


| Variable | Default | Effect |
| --- | --- | --- |
| `N8N_ENABLED` | `false` | Opt-in in all profiles, including local |
| `N8N_WEBHOOK_URL` | `http://localhost:5678/webhook/erp-notificaciones` | Configurable webhook destination; old flat events require a compatible workflow/URL |
| `N8N_WEBHOOK_SECRET` | Empty | Sent as X-ERP-Webhook-Secret; empty/blank disables all delivery (fail closed) |

S17 adds a separate [multichannel route and importable workflow](multichannel-notifications.md): only explicit-data notifications simulate disabled/blank configuration or network unavailability in dev/local; production overrides dev/local, and default/unknown profiles return NO_ENVIADO. HTTP rejection or other runtime failure after attempting transport returns NO_ENVIADO in every profile. Simulation reports success without confirming that a notification was sent; a timed-out request may still complete remotely.

Configure the same secret in the external n8n workflow. Never put a real secret in source control. Requests carry `pacienteNombre`, `telefono`, `montoBs`, `concepto` (`Cuota <number> de orden <uuid>`) and `fechaVencimiento`; existing `nombrePaciente`, `telefonoWhatsapp`, `vencimiento` and QR fields remain. The legacy quota-ID/no-explicit-data reminder path never simulates. It returns `notificacionEnviada=false` and a reason when disabled or unavailable; successful HTTP means webhook acceptance, not provider or recipient delivery. Its 24-hour deduplication claim is released on failure. A timeout has an uncertain remote outcome, so retrying can duplicate a reminder; reconcile before retrying. Receipt notifications remain best effort after commit.
