# Billing saga: reintentos y rechazo durable

**Base y entrada AMQP verificadas localmente; saga todavía parcial.** Pruebas enfocadas 26/26 y suite predeterminada 616/616; sin dispatcher ni aceptación completa/E2E.
El retry cruza el proxy transaccional en cada intento: el rollback termina antes del siguiente intento o del registro del rechazo. Otros consumidores y la entrada tipada heredada conservan su comportamiento.

## Configuración

| Propiedad (`erp.billing-saga.retry.*`) | Variable de entorno | Default |
| --- | --- | --- |
| `max-attempts` | `ERP_BILLING_SAGA_RETRY_MAX_ATTEMPTS` | 3 |
| `initial-delay-ms` | `ERP_BILLING_SAGA_RETRY_INITIAL_DELAY_MS` | 100 |
| `max-delay-ms` | `ERP_BILLING_SAGA_RETRY_MAX_DELAY_MS` | 2000 |

`max-attempts` incluye el primer intento. Antes del reintento n (n=1 para el primero), pausa `min(max-delay-ms, initial-delay-ms * 2^(n-1))`, con duplicación sin overflow. No hay pausa tras el último intento ni ante errores permanentes. Valores no positivos o máximo menor que inicial impiden el arranque. El bean `billingSagaRetryPause` (`LongConsumer`) realiza el sleep y puede sustituirse en tests; una interrupción conserva el flag y solicita requeue, sin continuar el bucle.

La lista conservadora admite errores Spring de acceso transitorio/recuperable o recurso DB, sockets y conexión/IO AMQP; también SQL transitorio/recuperable o estados `08*`, `40001`, `40P01`, `55P03`, `57P03`. Un fallo al crear transacción necesita una causa de recurso/conexión reconocida. Se recorren como máximo 32 causas; cadenas incompletas, reglas de negocio, argumentos inválidos y SQL permanente no se reintentan. Otros errores no se consideran transitorios por defecto. No se registran mensajes de excepción ni datos del payload.

## Slice A: base de rechazo y outbox

`FacturacionSagaUseCase.procesar` adquiere el lock de consulta existente y comprueba orden/rechazo antes de evaluar el supplier. El lock permanece en la misma transacción que el procesador existente. Un comando con consultaId distinto del bloqueado es PAYLOAD_INVALIDO. Una consulta facturada no se rechaza; un cierre rechazado no se factura después.

Los códigos definitivos son PACIENTE_INEXISTENTE, ODONTOLOGO_INEXISTENTE (solo si se proporciona), SERVICIO_INEXISTENTE, SERVICIO_INACTIVO, MONTO_INVALIDO y PAYLOAD_INVALIDO. Se preservan paciente inactivo, odontólogo opcional y redondeo HALF_UP de Money: -0.004 se acepta como cero; -0.005 se rechaza.

`registrarRechazo` usa una transacción nueva tras rollback, readquiere el lock y vuelve a comprobar la orden. `rechazos_facturacion` ES el rechazo durable y su outbox: event_id propio, consulta_id, consulta_finalizada_event_id único, motivo_codigo, motivo fijo sin datos personales y rechazada_en. Nace PENDIENTE con attempts=0; dispone de next_attempt_at, last_error/sent_at y estados ENVIADO/FALLIDO para el futuro dispatcher. No hay FK al MS2 ni deduplicación global por consultaId.

Solo una inserción nueva registra FACTURACION_CONSULTA_RECHAZADA mediante el caso de uso de auditoría, en la misma transacción. Operadores pueden consultar `listarAuditLogs`: entidadAfectada=rechazos_facturacion, entidadId=eventId de compensación y detalle con consultaId, consultaFinalizadaEventId y motivoCodigo. Duplicados no añaden auditorías. Un fallo de auditoría revierte también el rechazo/outbox.

## Slice B: entrada AMQP cruda

Solo el método que recibe `Message` está anotado. Su factory dedicada usa conversión exclusivamente a bytes: ni content type Java serializado ni __TypeId__ provocan deserialización de transporte. La conversión JSON es explícita en el handler, sin cambiar el converter global.

| Entrada o fallo | Resultado |
| --- | --- |
| messageId no UUID estricto, JSON ilegible o consultaId no UUID | Poison: AmqpRejectAndDontRequeueException; sin escrituras |
| JSON correlacionable pero DTO no convertible | PAYLOAD_INVALIDO, convertido solo después de las guardas |
| Rechazo definitivo | Registro durable y auditoría atómicos antes del retorno normal |
| Fallo al persistir rechazo o fallo técnico no clasificado | ImmediateRequeueAmqpException con causa original; nunca ACK ni DLQ |
| Reintentos transitorios agotados | Requeue explícito conservando la causa original; compensación pendiente |

La identidad del cierre es exclusivamente el messageId AMQP, nunca eventId del body. Redelivery del mismo cierre no duplica rechazo; un cierre nuevo de la misma consulta no queda suprimido por el anterior. No se registran excepciones ni payloads en logs propios del handler. La política global default-requeue-rejected no cambia; el handler pide requeue explícitamente para fallos no poison.

## Verificación y límites

Prueba enfocada: `./mvnw -Dtest=BillingSagaRetryIntegrationTest test`. Se observó RED (6 casos, 3 fallos de aserción, 0 errores) antes de producción; luego GREEN 6/6 y suite predeterminada 596/596, sin fallos, errores ni omisiones. Los casos prueban el adaptador público, recuperación, límite/backoff y controles previos; no prueban todavía todas las categorías, configuración inválida, interrupciones ni rollback de escrituras parciales. La revisión independiente de fuente no encontró bloqueos, pero su ejecución funcional no estuvo disponible. Ese corte documentado permite avanzar el slice; no certifica la saga completa ni E2E.

Para rechazo, el padre observó RED antes de producción: red-rejection-01, 16 casos/14 fallos de aserción/0 errores/0 omisiones; red-rejection-02 combinado, 22 casos/15 fallos/0 errores/0 omisiones. Los tests directos BillingRejectionLedgerIntegrationTest corroboran las reglas ya RED mediante el caso de uso público, auditoría y consultas financieras; no son el RED inicial. Luego se observaron GREEN 26/26 y suite predeterminada 616/616, sin fallos, errores ni omisiones. La nueva revisión independiente de fuente no encontró bloqueos; no ejecutó pruebas propias ni certificó wire/E2E. Los resultados funcionales son locales, no ejecución independiente.

El corte SOURCE de la primera unidad bajo disposición 07f6 sigue vigente: sin repetir verificador ni native START para esa brecha. **Pendientes:** REINTENTOS_AGOTADOS tras agotar retry, dispatcher/publicación confirmada, límites de entrega y visibilidad terminal, escenarios de carrera/re-cierre y rollback efectivo en MS2. El estado FALLIDO y el séptimo código están reservados en el esquema, no constituyen dispatcher ni compensación por agotamiento implementados. No se aporta prueba de broker real, ACK/requeue/DLQ en el wire ni aceptación de producción.
