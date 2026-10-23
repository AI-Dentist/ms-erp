# Evidencia de smoke local (MS1, perfil `local`)

Prueba viva por HTTP contra MS1 ya levantado (no la arranca ni la detiene). Solo desarrollo.

- **Comando:** `bash scripts/local-smoke.sh` (variables opcionales: `MS1_URL`, `DEV_USER`, `RABBIT_API`, `ERP_PG_CONTAINER`). Requiere `curl` y `python` 3 (no usa `jq`); `docker exec erp-postgres psql` y la API de gestión de RabbitMQ son opcionales (si faltan, esos checks imprimen `SKIP`).
- **Fecha:** 2026-10-22 (zona America/La_Paz), ejecución final 23:48.
- **Entorno:** MS1 `local` en `http://localhost:8080`; PostgreSQL 18 (`erp-postgres`, puerto host 55432); RabbitMQ 4 (`erp-rabbitmq`); SeaweedFS S3 `127.0.0.1:8333`, bucket `erp-receipts`.
- **Resultado final: PASS=80 FAIL=0 SKIP=0, código de salida 0.**
- **Reejecuciones:** la ejecución final fue la 5.ª; las 4 anteriores fallaron por defectos **del propio script** (no de MS1): conversión de rutas MSYS en `curl -o /dev/null`, retorno de carro de Python en Windows al construir JSON, y una cola RabbitMQ temporal no durable (RabbitMQ 4 la rechaza con 400: `transient_nonexcl_queues` deprecated). Esas ejecuciones parciales dejaron órdenes/recibos de smoke (ver "Datos que deja el smoke").
- No se encontró ningún defecto de MS1.

## Checks

| # | Check | HTTP / resultado | Evidencia clave | |
|---|---|---|---|---|
| 1 | Preflight `OPTIONS /graphql` (Origin `http://localhost:4201`, headers `content-type,idempotency-key,x-dev-user,authorization`) | 200 | `Access-Control-Allow-Origin: http://localhost:4201` exacto; permite los 4 headers | PASS |
| 1 | Preflight con `http://evil.example` (`/graphql` y `/export/pdf`) | 403 | sin `Allow-Origin` | PASS |
| 1 | Preflight `/api/v1/reports/export/pdf` | 200 | `Allow-Origin: http://localhost:4201` | PASS |
| 2 | Sin `X-Dev-User` / `no-existe` | 401 / 401 | — | PASS |
| 2 | `admin-scz` | 200 | datos de empresa | PASS |
| 2 | `caja-scz` → `listarUsuarios` | 200 | error GraphQL `FORBIDDEN` | PASS |
| 3 | Introspección estándar | 200 | guardada en `docs/graphql/introspection-live.json`; **igual** a `docs/graphql/introspection.json` (42 tipos, campos, args, inputs y enums) | PASS |
| 4 | `obtenerConfiguracionEmpresa` | 200 | "Clínica Dental Sonrisa Cruceña", Bs | PASS |
| 4 | `listarPacientes("LOCAL")` / `listarOdontologos` / `listarEspecialidades` / `listarConsultorios` | 200 | ≥5 pacientes; 5, 7 y 6 filas (incluyen seed V2) | PASS |
| 4 | `listarServiciosOdontologicos` | 200 | "Exodoncia de Tercer Molar Retenido" **1250.00 Bs**; "Restauración Fotopolimerizable con Resina" **280.00 Bs** | PASS |
| 4 | `listarOrdenesCobro(PENDIENTE)` | 200 | 5 órdenes (en la ejecución final) | PASS |
| 4 | `obtenerOrdenCobroDetalle(a7…002)` | 200 | CREDITO, cuotas 333.33 / 333.33 / 333.34 | PASS |
| 4 | `listarCuotasPendientesPorPaciente`, `listarRoles`, `listarUsuarios`, `listarAuditLogs` | 200 | 3 cuotas; ADMIN/CAJERO/ODONTOLOGO/RECEPCIONISTA; 8 usuarios; lista (vacía en este momento) | PASS |
| 5 | `crearOrdenCobroManual` (paciente `a5…005`, servicios `a6…001`+`a6…002`) | 200 | subtotal 1530.00 | PASS |
| 5 | `configurarPlanDePagos` CREDITO, 3 cuotas, descuento 29.99 | 200 | total 1500.01; cuotas **500.00 / 500.00 / 500.01** (suma = total; el centavo va a la última) | PASS |
| 5 | `cobrarCuotaEnEfectivo` cuota 1, key K1 | 200 | recibo `REC-2026-000005`, 500.00 | PASS |
| 5 | Replay K1 | 200 | `Idempotency-Replayed: true`, cuerpo idéntico | PASS |
| 5 | Misma cuota con key K2 | 200 + error | `BAD_REQUEST` "La cuota no está pendiente de pago" | PASS |
| 5 | K1 con payload distinto | **422** | `{"error":"La Idempotency-Key ya fue usada con una solicitud diferente"}` | PASS |
| 5 | psql (solo lectura) | — | 1 movimiento INGRESO y 1 recibo para la cuota; cuota PAGADA; saldo de la orden 1500.01 − 500.00 = **1000.01**; orden PARCIAL | PASS |
| 5 | `registrarEgresoCaja` 12.34 con key + replay | 200 / 200 | `Idempotency-Replayed: true`, cuerpo idéntico, 1 sola fila en `movimientos_caja` | PASS |
| 5 | `obtenerFlujoCaja` semana lun 19-oct 00:00 → dom 25-oct (-04:00) | 200 | incluye el ingreso y el egreso del smoke y 7 movimientos sembrados; ingresos 3100.00, egresos 532.52 | PASS |
| 6 | `generarQrPagoFacil` cuota 2 | 200 | id `MOCK-…`, estado GENERADO, imagen base64 decodificable, `montoBs` = 500.00 = cuota | PASS |
| 6 | `simularConfirmacionQrDev` | 200 | recibo `REC-2026-000006`, 500.00 | PASS |
| 6 | Replay con la misma key | 200 | cuerpo idéntico | PASS |
| 6 | Simulación con key nueva | 200 | devuelve el mismo `REC-2026-000006`; 1 sola fila de caja para la cuota | PASS |
| 6 | Estado de cuota 2 | 200 | `PAGADA`; orden PARCIAL, saldo 500.01, cuota 3 PENDIENTE | PASS |
| 7 | `Recibo.pdfUrlS3` como `admin-scz` | 200 | `http://localhost:8333/erp-receipts/<uuid>.pdf?<firma>` disponible al 1.er intento; `caja-scz` también; `recepcion-scz` → `FORBIDDEN` | PASS |
| 7 | GET URL presignada sin auth | 200 | magic `%PDF`, `content-type: application/pdf`, 1123 bytes | PASS |
| 7 | GET del mismo objeto sin query string | 403 | acceso anónimo denegado | PASS |
| 7 | `GET /api/v1/recibos/{id}/pdf` | 404 (admin) / 401 (anónimo) | la ruta **no existe**; el frontend debe usar `pdfUrlS3` | PASS (documentado) |
| 8 | `generarReporteInteligente` con la pregunta exacta | 200 + error GraphQL | `Reportes IA no disponibles: configure el proveedor` (`BAD_REQUEST`) | **NO DISPONIBLE** (sin proveedor IA; no hay respuesta simulada) |
| 8 | `GET /export/pdf?reporteId=<uuid aleatorio>` como admin | 404 | "Reporte no disponible" | PASS |
| 8 | Igual como `recepcion-scz` / formato `csv` | 403 / 400 | "Formato de reporte no permitido" | PASS |
| 9 | `erp.n8n.enabled=false` en `application-local.yml` | — | además `enviado_whatsapp=false` en los 2 recibos | PASS |
| 10 | `crearPaciente` + outbox sin cola enlazada | 200 | `catalog_event_outbox`: `catalogo.paciente.creado`, `sent_at` NULL, intentos crecientes | PASS |
| 10 | Con cola temporal `erp-smoke-tmp-*` enlazada a `catalogo.paciente.creado` | — | `sent_at` no nulo en ~4 s (4 intentos); la cola recibió 1 mensaje con esa routing key; cola eliminada | PASS |

### Notas

- **Estado QR:** el esquema no tiene `consultarEstadoQrPagoFacil`. Se usó `obtenerOrdenCobroDetalle { cuotas { estado } }` (más `obtenerFlujoCaja { movimientos { recibo } }` para el recibo).
- **RabbitMQ:** en el exchange `dental.topic.exchange` solo existe el binding de `clinica.consulta.finalizada` (cola `erp.consulta.finalizada.queue`). **Ninguna cola está enlazada a `catalogo.*`**: la publicación es `mandatory`, así que el broker responde `NO_ROUTE` y el outbox reintenta con backoff sin marcar `sent_at`. Es el comportamiento diseñado; MS2/MS3 deben declarar sus colas/bindings para recibir los eventos. El smoke solo crea una cola temporal durable `erp-smoke-tmp-<n>` y la borra al salir (comprobado: no queda ninguna).
- **PDF post-commit:** el PDF se genera tras el commit; el script sondea hasta 10 s (aquí bastó 1 intento).

## Keycloak: Bearer (T7, 2026-10-23)

Keycloak 26.8.1 local (`docker compose up -d keycloak`, realm `dental-platform`, ver [keycloak-local.md](keycloak-local.md)). El smoke tiene una sección 11 opcional (se omite con `SKIP` si el emisor no responde) que obtiene tokens con el cliente de pruebas `ms1-local-tests` y repite las comprobaciones de autorización con `Authorization: Bearer`.

- **Instancia en ejecución en `:8080`** (arrancada antes del cambio, sin emisor): `Bearer` válido de `admin-scz` → **401**; `X-Dev-User` → 200. **Necesita reinicio** para recoger el emisor por defecto. No se reinició.
- **Segunda instancia corta en `:8090`** (mismo perfil `local` y BD, `ERP_PORT=8090`, terminada después por su PID): `MS1_URL=http://localhost:8090 bash scripts/local-smoke.sh` → **PASS=93 FAIL=0 SKIP=0**, exit 0 (los 80 anteriores + 13 de la sección 11).

| # | Check (Bearer salvo indicación) | Resultado |
|---|---|---|
| 11 | tokens `admin-scz`/`caja-scz`/`recepcion-scz` (*password grant*, solo DEV) | `preferred_username`, `aud: ms-erp`, `realm_access.roles` = su rol |
| 11 | `admin-scz` → `listarUsuarios` | 200, ve `caja-scz` |
| 11 | `caja-scz` → `listarUsuarios` | 200 + `FORBIDDEN` (igual que con `X-Dev-User`) |
| 11 | `recepcion-scz` → `obtenerConfiguracionEmpresa` | 200 |
| 11 | `caja-scz` + `X-Dev-User: admin-scz` | `FORBIDDEN`: gana el Bearer, la cabecera se ignora |
| 11 | token manipulado (con y sin `X-Dev-User` válido) | 401 / 401 (sin respaldo en la cabecera) |
| 11 | `admin-scz` → `crearEspecialidad` | 200; `audit_logs.usuario_id` = usuario de BD `admin-scz` |
| 11 | `caja-scz` → `crearEspecialidad` | `FORBIDDEN`, 0 filas creadas |

**Arranque con Keycloak caído (en vivo):** con `erp-keycloak` detenido se arrancó una instancia `local` en `:8090` → `Started ErpApplication` en 18 s, health 200; un Bearer → **401** (no 500, sin `threw exception` en el log, un `WARN` por intento), `X-Dev-User` → 200. Al arrancar Keycloak (healthy), la misma instancia aceptó un token válido (**200**) sin reiniciar.

**PKCE (cliente `mf-erp`):** `/auth` sin `code_challenge_method` → redirección con `error=invalid_request`; con S256 → 200 (formulario de login); `redirect_uri` ajeno → 400; *password grant* → `unauthorized_client`.

Limitación: se usó `curl`/*password grant*; el flujo interactivo del navegador Angular (login + redirect) no se ejecutó. `LiveIntegrationsIT` (Testcontainers) verifica la configuración de `mf-erp` por la API de administración.

## No disponible / no probado

- **Proveedor IA (Spring AI / OpenAI):** sin `OPENAI_API_KEY`. No se probó la generación real de SQL, la estructura de tabla/gráfico, ni las descargas `xlsx`/`pdf` de un reporte real (solo 404/403/400 de la ruta). El script lo intenta si el proveedor responde.
- **PagoFácil real:** solo mock local (`MOCK-…`) y `simularConfirmacionQrDev`; no se probó el callback real ni su firma/credenciales. El webhook `POST /api/v1/webhooks/pagofacil` no se ejercitó en este smoke.
- **Keycloak / JWT:** la tabla de arriba (ejecución del 2026-10-22) solo cubre `X-Dev-User`; el Bearer se probó después, ver "Keycloak: Bearer (T7)".
- **n8n / WhatsApp:** no configurado; no hay evidencia de entrega de mensajes (confirmar un pago no la demuestra).
- **Concurrencia:** el smoke es secuencial; la concurrencia de cobros se cubre en los tests de T4, no aquí.
- **Entrega del consumidor:** no hay consumidor real de `catalogo.*`; solo se verificó la entrega a una cola temporal.
- **Navegador real:** el preflight se probó con `curl`, no desde Angular en `http://localhost:4201`.

## Datos que deja el smoke

Las cuotas sembradas (`a9…001`–`007`) **no se tocaron** (siguen PENDIENTE, salvo la `…006` ya PAGADA del seed). Cada ejecución crea su propia orden, 1 egreso de Bs 12.34 y un paciente `SMK…`; las 5 ejecuciones (4 parciales) dejaron 4 órdenes `smoke …` y recibos `REC-2026-000001`–`000006`. Además queda un evento de outbox (`catalogo.paciente.creado` de una ejecución parcial) sin enviar, reintentando con backoff hasta que exista un binding. No se borró nada.
