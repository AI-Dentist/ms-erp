# Saga de facturación: rechazo durable y compensación

**Entrada y rechazo durable verificados localmente.** La publicación se incorpora en el siguiente slice; no es aceptación E2E.

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

## Verificación y límites

El conjunto local pasó 49/49 pruebas enfocadas y 639/639 de suite. Las pruebas de entrada incluyen agotamiento, fallo de persistencia con cooldown/ERROR sin datos personales y carrera cierre A/cierre B; las revisiones de fuente no encontraron bloqueos. Los resultados del conjunto también incluyen el emisor/dispatcher del siguiente slice. No se atribuyen como ejecución independiente ni broker wire/E2E.
