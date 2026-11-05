# Saga de facturación: rechazo durable y compensación

**Entrada durable y emisor confirmado verificados localmente.** El dispatcher se conecta en el siguiente slice; no es aceptación E2E.

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

## Emisor confirmado

`ConfirmedBillingRejectionSender` publica bytes JSON persistentes con eventId estable como messageId/correlación. Solo ACK sin retorno es éxito; NACK, retorno, timeout o fallo AMQP confirmado excepcionalmente no lo son. El worker y su cola son acotados, el deadline cubre envío y confirmación, y los wrappers de futures se desenvuelven con límite. Errores de programación no se convierten en fallos de broker. No se envía SQL al worker.

Las seis variantes del test público del emisor corroboran estas reglas sin base ni broker real. Los parámetros de despacho siguientes se consumen al conectar el dispatcher en el próximo slice.

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

## Verificación y límites

El conjunto de trabajo pasó 49/49 enfocadas y 639/639 de suite; incluye dispatcher del próximo slice. Revisiones de fuente sin bloqueos; ejecución independiente/wire/E2E no disponibles. El emisor no marca por sí mismo filas como ENVIADO/FALLIDO: eso corresponde al dispatcher transaccional.
