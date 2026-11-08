# Durable catalog synchronization

Catalog creation now commits its aggregate, audit entry and PostgreSQL outbox event together. A background dispatcher publishes the stored JSON to RabbitMQ; creation never waits for a broker. This replaces the previous best-effort after-commit publisher.

## Delivery contract

- `RabbitMQCatalogEventPublisher.publicar` uses REQUIRED transaction propagation: joins the existing creation transaction, or starts one for standalone publication. Serialization and SQL errors propagate and roll back the transaction; they are not treated as broker outages.
- V6 creates `catalog_event_outbox`: unique `event_id`, exchange, routing key, JSON payload, creation time, attempts, next attempt time and sent time. The existing event records and their payload fields/routing keys are unchanged ([SPECS §3.1](../SPECS.md)).
- Each dispatch call handles at most the configured batch. Each event gets its own REQUIRES_NEW transaction, selecting one due unsent row with `FOR UPDATE SKIP LOCKED`. Other instances skip locked rows. There is no broker I/O in the aggregate-creation transaction.
- Messages are persistent JSON, with the event UUID as both message ID and correlation ID. Delivery is recorded only after a correlated ACK **and no mandatory return**. ACK plus `NO_ROUTE` is a failure, not success. Spring AMQP 4 sets `CorrelationData.getReturned()` before completing its confirm future.
- Connection exceptions, NACK, return, timeout and local sender saturation retain the row for retry. A late ACK after timeout cannot mark it delivered. SQL failures during dispatch propagate and roll back its SQL updates.
- After `max-attempts` unconfirmed decisions (default 5; hard bounds 1–10), the row becomes `FALLIDO` and is never selected automatically again. That transition and `CATALOGO_EVENTO_PUBLICACION_FALLIDA` audit commit atomically; the ADMIN `listarAuditLogs` query exposes the event ID, routing key, attempts, `FALLIDO` and fixed reason `PUBLICACION_NO_CONFIRMADA`, never payload/contact data. A failure to persist either rolls back the whole decision.
- Sent and failed rows are retained. There is no automatic deletion, rearm or dead-letter drop. Consumers must deduplicate by `eventId`: a crash after broker acceptance but before the SQL commit can cause an **at-least-once duplicate with the same eventId and payload**. This is not exactly-once delivery and does not guarantee global ordering or consumer processing.

## Update and deactivation events (contract extension S9)

The same outbox now also carries **updates and logical deactivations** of patients, dentists and offices. Contract note for the MS2/MS3 teams: these are three **new routing keys** on `dental.topic.exchange`; bind a queue to them to receive the changes. Existing `catalogo.*.creado` keys and payloads are unchanged.

| Routing key | Published when | Payload |
|---|---|---|
| `catalogo.paciente.actualizado` | `actualizarPaciente` succeeds; `eliminarPaciente` deactivates an active patient | `eventId`, `pacienteId`, `ciNit`, `nombreCompleto`, `telefonoWhatsapp`, `fechaNacimiento`, `activo` |
| `catalogo.odontologo.actualizado` | `actualizarOdontologo` succeeds; `eliminarOdontologo` deactivates an active dentist | `eventId`, `odontologoId`, `nombreCompleto`, `matriculaProfesional`, `especialidades` (names), `activo` |
| `catalogo.consultorio.actualizado` | `actualizarConsultorio` succeeds; `eliminarConsultorio` deactivates an active office | `eventId`, `consultorioId`, `codigo`, `nombre`, `estado` |

```json
{"eventId": "uuid", "pacienteId": "uuid", "ciNit": "8945123 SC", "nombreCompleto": "Ana María Rojas Vaca",
 "telefonoWhatsapp": "+59171111111", "fechaNacimiento": "1990-01-31", "activo": false}
{"eventId": "uuid", "odontologoId": "uuid", "nombreCompleto": "Valeria Roca", "matriculaProfesional": "COL-OD-4512",
 "especialidades": ["Ortodoncia"], "activo": true}
{"eventId": "uuid", "consultorioId": "uuid", "codigo": "CONS-01", "nombre": "Consultorio 1", "estado": "INACTIVO"}
```

- Each payload is the corresponding `*.creado` payload ([SPECS §3.1](../SPECS.md)) plus `activo`; the office payload is identical to `creado` because it already carries `estado`. Every event has a fresh `eventId`, which is also the deduplication key.
- **Full state, not a diff**: consumers upsert by the entity id. A deactivation is an `actualizado` event with `activo = false` (patients, dentists) or `estado = "INACTIVO"` (offices, which have no `activo` column). `estado` values: `DISPONIBLE`, `MANTENIMIENTO`, `INACTIVO`.
- Published inside the update transaction like creations: nothing is enqueued for a rejected or rolled-back operation, and repeating a deactivation on an already inactive entity enqueues nothing. Specialties, services and users publish no events (there is no `*.creado` event for them).
- Delivery guarantees are the ones above (at-least-once, no global ordering). The payload carries no timestamp, so two updates of one entity that arrive out of order cannot be ordered by content; re-read MS1 (`obtenerPacientePorId`, `listarOdontologos(soloActivos: false)`, `listarConsultorios`) when the final state matters.
- **Listings are unchanged**: `listarPacientes`, `listarEspecialidades` and `listarServiciosOdontologicos` return only active rows, `listarOdontologos` only active ones unless `soloActivos: false`, and `listarConsultorios` returns every office (inactive ones with `estado: "INACTIVO"`). Deactivation never deletes rows, so ids stay valid for existing orders.

## Configuration

Defaults are declared in Java, so no additional YAML import is required. Override with Spring properties/environment variables.

| Property (`erp.catalog-outbox.` prefix) | Default | Bounds / meaning |
|---|---:|---|
| `enabled` | `true` | Enables scheduled dispatch; false leaves enqueue and explicit `dispatch()` available |
| `poll-delay-ms` | `1000` | Spring fixed delay between completed polls; configure a positive value |
| `batch-size` | `1` | 1–10 events per poll, separate transaction per row |
| `max-attempts` | `5` | 1–10 committed dispatch decisions, including a successful send; `ERP_CATALOG_OUTBOX_MAX_ATTEMPTS` |
| `send-timeout-ms` | `2000` | 50–3000 ms total local wait including send and confirm |
| `initial-backoff-ms` | `1000` | At least 100 ms |
| `max-backoff-ms` | `300000` | At least initial backoff; at most 86400000 ms |

Retry delay after failed attempt N is `min(initial * 2^(N-1), max)`, calculated without overflow. `attempts` counts committed dispatch decisions including successful sends. Unconfirmed attempts retry only below the configured limit; the last becomes `FALLIDO`. Lowering the limit also terminalizes an already-exhausted pending row without another send or counter increment. A database rollback/lost commit can repeat a wire attempt with the same event ID; the counter limit is not an exactly-once or hard lifetime wire-attempt guarantee. Next attempt time is measured using PostgreSQL `clock_timestamp()` after the send, not the transaction start. Invalid resource bounds fail startup. Changing these properties requires restart.

The Java RabbitMQ configuration enables correlated confirms, publisher returns and mandatory sends without replacing Boot's JSON converter. Connection, handshake and channel RPC timeouts are 2000 ms. The configured exchange remains `erp.messaging.exchange` (`dental.topic.exchange` in the existing deployment configuration).

## Lock duration and outage behavior

Only the selected outbox row is locked while sending. The default local broker-wait portion is at most 2 seconds per row (hard maximum 3 seconds), plus SQL execution and transaction completion; batch size does not multiply the duration of a single row lock. Database connectivity/stalls are outside that broker deadline.

A single daemon sender thread with one bounded queue slot isolates even a socket call that ignores interruption from the database transaction. On timeout its future is cancelled; the row is retried, never declared delivered. A stuck send may later reach the broker, so duplicates remain possible. If that thread remains stuck, queued/saturated calls fail or time out without growing threads or memory, and rows remain durable. Investigate connectivity and restart the affected instance if necessary; monitor backlog age rather than treating successful aggregate creation as delivery evidence.

## Operations and verification

Monitor pending backlog, oldest creation time, due time and terminal failures. V11 adds `estado` (`PENDIENTE|ENVIADO|FALLIDO`) and fixed-code `last_error`, deriving existing sent rows as `ENVIADO` without changing IDs, payloads or counters. Pending-only indexing excludes failed/sent rows. Failed rows need operator investigation; no public rearm command is added. ADMIN audit visibility is the latest 500 entries, not an exhaustive reconciliation listing. Do not log payloads: patient contact data is present. Restrict outbox DB access and define an operational retention policy for sent rows before production use; this adapter deliberately implements no purge.

```sql
SELECT count(*) AS pending, min(created_at) AS oldest, max(attempts) AS highest_attempt
FROM catalog_event_outbox WHERE estado = 'PENDIENTE';

SELECT event_id, routing_key, attempts, last_error, created_at
FROM catalog_event_outbox WHERE estado = 'FALLIDO';
```

Focused checks (Java 25):

```sh
./mvnw '-Dtest=com.dentalplatform.erp.mastercatalog.**.*Test' test
./mvnw -Dtest=CatalogOutboxTerminalTest,CatalogOutboxLimitConfigurationTest test
```

Tests use real embedded PostgreSQL 18 for Flyway, aggregate/audit/outbox atomicity, duplicate SQL rollback, retry state and concurrent dispatchers. The RabbitTemplate broker boundary is simulated for outage, ACK, NACK, return, missing/late confirm and blocked send. Existing GraphQL creation tests inspect durable payloads and assert no creation-time broker calls; rejected inputs preserve rows and counters. IntegrationTestBase disables scheduled dispatch with `erp.catalog-outbox.enabled=false`; dispatcher tests call it explicitly.

**Not verified here:** live RabbitMQ wire delivery, network partitions against a real broker, consumer deduplication and process-kill crash recovery. Those remain deployment/integration acceptance work, not claims made by the simulated boundary tests.
