# Billing saga: primera unidad de reintentos

**Estado actual: SOLO primera unidad.** Cubre el retry de S3/S9(1), no la aceptación de la saga completa.
El listener anotado y su entrada tipada ejecutan cada intento mediante el proxy transaccional del caso de uso: el rollback termina antes del siguiente intento. No hay transacción exterior ni advice de contenedor; otros consumidores no cambian.

## Configuración

| Propiedad (`erp.billing-saga.retry.*`) | Variable de entorno | Default |
| --- | --- | --- |
| `max-attempts` | `ERP_BILLING_SAGA_RETRY_MAX_ATTEMPTS` | 3 |
| `initial-delay-ms` | `ERP_BILLING_SAGA_RETRY_INITIAL_DELAY_MS` | 100 |
| `max-delay-ms` | `ERP_BILLING_SAGA_RETRY_MAX_DELAY_MS` | 2000 |

`max-attempts` incluye el primer intento. Antes del reintento n (n=1 para el primero), pausa `min(max-delay-ms, initial-delay-ms * 2^(n-1))`, con duplicación sin overflow. No hay pausa tras el último intento ni ante errores permanentes. Valores no positivos o máximo menor que inicial impiden el arranque. El bean `billingSagaRetryPause` (`LongConsumer`) realiza el sleep y puede sustituirse en tests; una interrupción conserva el flag y solicita requeue, sin continuar el bucle.

La lista conservadora admite errores Spring de acceso transitorio/recuperable o recurso DB, sockets y conexión/IO AMQP; también SQL transitorio/recuperable o estados `08*`, `40001`, `40P01`, `55P03`, `57P03`. Un fallo al crear transacción necesita una causa de recurso/conexión reconocida. Se recorren como máximo 32 causas; cadenas incompletas, reglas de negocio, argumentos inválidos y SQL permanente no se reintentan. Otros errores no se consideran transitorios por defecto. No se registran mensajes de excepción ni datos del payload.

## Verificación y límites

Prueba enfocada: `./mvnw -Dtest=BillingSagaRetryIntegrationTest test`. Se observó RED (6 casos, 3 fallos de aserción, 0 errores) antes de producción; luego GREEN 6/6 y suite predeterminada 596/596, sin fallos, errores ni omisiones. Los casos prueban el adaptador público, recuperación, límite/backoff y controles previos; no prueban todavía todas las categorías, configuración inválida, interrupciones ni rollback de escrituras parciales. La revisión independiente de fuente no encontró bloqueos, pero su ejecución funcional no estuvo disponible. Ese corte documentado permite avanzar el slice; no certifica la saga completa ni E2E.

Al agotarse los intentos se propaga la excepción original. **Pendientes:** rechazo definitivo y `REINTENTOS_AGOTADOS` con outbox atómico, correlación, idempotencia de compensación y DLQ exclusiva para mensajes ilegibles. La configuración global de reject/requeue no cambia: aún puede enviar fallos agotados a DLQ sin compensación. No se aporta prueba de broker real, ACK/requeue/DLQ en el wire ni aceptación de producción.
