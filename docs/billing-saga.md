# Saga de facturación: rechazo durable y compensación

**Verificación local completada: 49/49 pruebas enfocadas y 639/639 en la suite.** Las revisiones independientes de fuente no encontraron bloqueos. No es aceptación E2E ni certificación del broker real.

## Entrada y rechazo durable

La entrada AMQP usa exclusivamente `messageId` como `consultaFinalizadaEventId`; nunca toma `eventId` del body como respaldo. La factory recibe bytes sin deserialización Java ni interpretación de `__TypeId__`. JSON ilegible o correlación sin UUID válido es poison y se rechaza sin requeue hacia la DLQ. Un JSON correlacionable que no convierte al DTO produce `PAYLOAD_INVALIDO`.

Cada intento cruza el proxy transaccional de `FacturacionSagaUseCase`. Bajo el lock de consulta existente, una orden ya facturada o un cierre ya rechazado retorna antes de convertir o validar otros campos. El comando debe corresponder a la consulta bloqueada. El procesador existente participa en esa misma transacción.

Los rechazos definitivos son `PACIENTE_INEXISTENTE`, `ODONTOLOGO_INEXISTENTE` (solo si se proporciona), `SERVICIO_INEXISTENTE`, `SERVICIO_INACTIVO`, `MONTO_INVALIDO` y `PAYLOAD_INVALIDO`. Se preservan paciente inactivo, odontólogo opcional y redondeo HALF_UP: `-0.004` se acepta como cero; `-0.005` se rechaza. Al agotarse los intentos transitorios se registra `REINTENTOS_AGOTADOS`; los fallos técnicos no clasificados y las interrupciones no se convierten en rechazos de negocio.

Tras el rollback, `registrarRechazo` abre una transacción nueva, readquiere el lock y vuelve a comprobar la orden. `rechazos_facturacion` es tanto el rechazo durable como la fila de outbox; su cierre es único. Solo la inserción nueva agrega `FACTURACION_CONSULTA_RECHAZADA` a auditoría, en la misma transacción. Motivos fijos de 1 a 500 caracteres, sin payload ni datos personales. Un cierre nuevo de la misma consulta no se deduplica por el cierre anterior.

El handler retorna normalmente —permitiendo el ACK del contenedor— solo tras éxito de facturación, guarda idempotente o rechazo confirmado en la base. Si registrar falla, conserva la entrega con `ImmediateRequeueAmqpException`, una espera explícita y un ERROR estructurado:

```text
event=billing.delivery.retained consultaId=<uuid> closureId=<uuid> intentos=<n> redeliveryDelayMs=<ms>
```

No se registra el payload, mensaje de excepción ni stack en ese log propio. Los intentos y la espera están acotados **por entrega**. Si la base nunca vuelve, RabbitMQ debe seguir reteniendo/reentregando el mensaje: no existe un límite global que lo descarte, ACK falso ni DLQ de negocio. Revisá ese ERROR y la salud de la base; una interrupción conserva su flag y no compensa.

### Configuración de entrada

Prefijo `erp.billing-saga.retry.`:

| Propiedad | Variable | Default / límite |
| --- | --- | --- |
| `max-attempts` | `ERP_BILLING_SAGA_RETRY_MAX_ATTEMPTS` | 3; 2..10, incluye el primero |
| `initial-delay-ms` | `ERP_BILLING_SAGA_RETRY_INITIAL_DELAY_MS` | 100; positivo |
| `max-delay-ms` | `ERP_BILLING_SAGA_RETRY_MAX_DELAY_MS` | 2000; no menor al inicial |
| `redelivery-delay-ms` | `ERP_BILLING_SAGA_RETRY_REDELIVERY_DELAY_MS` | 1000; 100..30000 |

El backoff de negocio duplica el inicial hasta el máximo, sin overflow; no hay pausa tras el último intento ni retry de negocio ante un rechazo definitivo. La espera de redelivery es adicional cuando no se puede confirmar el rechazo durable o el fallo técnico no está clasificado.

## Publicación del outbox

El dispatcher procesa solo filas comprometidas `PENDIENTE` y vencidas, una transacción por evento con `FOR UPDATE SKIP LOCKED`. Publica el contrato de `facturacion.consulta.rechazada` en `dental.topic.exchange`: exactamente `eventId`, `consultaId`, `consultaFinalizadaEventId`, `motivoCodigo`, `motivo` y `rechazadaEn`. El `messageId` AMQP y la correlación son siempre el mismo `eventId` de compensación; el mensaje JSON es persistente.

Solo ACK de publisher **sin retorno mandatory** permite `ENVIADO`. NACK, retorno, falta de confirmación o fallo de conexión reintentan el mismo evento con backoff persistido. El worker, la cola y la espera de envío/confirmación son acotados. No se mantiene una conexión/lock de base indefinidamente por un socket de publicación trabado.

Al agotar el límite pasa a `FALLIDO` y registra `FACTURACION_RECHAZADA_PUBLICACION_FALLIDA` en auditoría en la misma transacción. `listarAuditLogs` (ADMIN) muestra el `eventId`, cierre, intentos, estado y motivo técnico fijo, sin diagnósticos sensibles. `FALLIDO` no se selecciona otra vez automáticamente. Corregí broker/topología y usá un procedimiento operativo autorizado; no hay endpoint de redrive automático.

La entrega es **al menos una vez**: un ACK seguido de fallo del commit puede reenviar el mismo `eventId`; MS2 debe deduplicar y aplicar la guarda del cierre vigente. Una compensación del cierre A no autoriza revertir un cierre B posterior. La cola/binding de MS2 pertenece a la topología del contrato en platform-infrastructure; el ERP no duplica su declaración.

### Configuración del outbox

Prefijo `erp.billing-saga.outbox.`; variables `ERP_BILLING_SAGA_OUTBOX_<SUFIJO>`:

| Propiedad / sufijo | Default / límite |
| --- | --- |
| `enabled` / `ENABLED` | true |
| `max-attempts` / `MAX_ATTEMPTS` | 5; 1..10 |
| `batch-size` / `BATCH_SIZE` | 1; 1..10 |
| `send-timeout-ms` / `SEND_TIMEOUT_MS` | 2000; 50..3000 |
| `initial-backoff-ms` / `INITIAL_BACKOFF_MS` | 1000; mínimo 10 |
| `max-backoff-ms` / `MAX_BACKOFF_MS` | 300000; no menor al inicial, máximo 86400000 |
| `poll-delay-ms` / `POLL_DELAY_MS` | 1000; 50..60000 |

Routing: `erp.messaging.facturacion-rechazada-routing-key`, variable `ERP_BILLING_SAGA_REJECTED_ROUTING_KEY`, default `facturacion.consulta.rechazada`.

## Verificación y límites de evidencia

```bash
./mvnw -Dtest=BillingSagaRetryIntegrationTest,BillingSagaRetryPolicyBoundsTest,BillingRejectionIntegrationTest,BillingRejectionLedgerIntegrationTest,BillingRejectionOutboxIntegrationTest test
./mvnw test
```

Evidencia histórica observada: retry RED 6/3 fallos → GREEN 6/6 y suite 596/596; rechazo RED combinado 22/15 fallos → GREEN 26/26 y suite 616/616. Las revisiones independientes de fuente no encontraron bloqueos; sus ejecuciones funcionales no estuvieron disponibles. Últimas unidades: RED 22/15 fallos y regresión de confirmación excepcional RED 1/1 antes de sus cambios; luego GREEN 49/49 y suite predeterminada 639/639, sin fallos, errores ni omisiones. La revisión de fuente de ese delta no encontró bloqueos; no fue ejecución funcional independiente.

Los casos usan PostgreSQL embebido sintético, adaptador AMQP público y broker simulado con confirms/returns. No equivalen a ACK/requeue/DLQ en el wire, reapertura real en MS2 ni aceptación de producción. La aceptación entre servicios es una etapa separada; no activa Docker, PagoFácil, IA, n8n ni publicación por sí sola.
