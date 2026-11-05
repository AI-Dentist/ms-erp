# Billing saga: base transaccional de rechazo

La base ACID está disponible mediante `FacturacionSagaUseCase`; la entrada AMQP y la publicación se conectan en los siguientes slices. No es aceptación completa de T2 ni E2E.

## Slice A: base de rechazo y outbox

`FacturacionSagaUseCase.procesar` adquiere el lock de consulta existente y comprueba orden/rechazo antes de evaluar el supplier. El lock permanece en la misma transacción que el procesador existente. Un comando con consultaId distinto del bloqueado es PAYLOAD_INVALIDO. Una consulta facturada no se rechaza; un cierre rechazado no se factura después.

Los códigos definitivos son PACIENTE_INEXISTENTE, ODONTOLOGO_INEXISTENTE (solo si se proporciona), SERVICIO_INEXISTENTE, SERVICIO_INACTIVO, MONTO_INVALIDO y PAYLOAD_INVALIDO. Se preservan paciente inactivo, odontólogo opcional y redondeo HALF_UP de Money: -0.004 se acepta como cero; -0.005 se rechaza.

`registrarRechazo` usa una transacción nueva tras rollback, readquiere el lock y vuelve a comprobar la orden. `rechazos_facturacion` ES el rechazo durable y su outbox: event_id propio, consulta_id, consulta_finalizada_event_id único, motivo_codigo, motivo fijo sin datos personales y rechazada_en. Nace PENDIENTE con attempts=0; dispone de next_attempt_at, last_error/sent_at y estados ENVIADO/FALLIDO para el futuro dispatcher. No hay FK al MS2 ni deduplicación global por consultaId.

Solo una inserción nueva registra FACTURACION_CONSULTA_RECHAZADA mediante el caso de uso de auditoría, en la misma transacción. Operadores pueden consultar `listarAuditLogs`: entidadAfectada=rechazos_facturacion, entidadId=eventId de compensación y detalle con consultaId, consultaFinalizadaEventId y motivoCodigo. Duplicados no añaden auditorías. Un fallo de auditoría revierte también el rechazo/outbox.

## Verificación y límites

Prueba de la base: `./mvnw -Dtest=BillingRejectionLedgerIntegrationTest test`. Sus cuatro casos públicos verificaron identidad/dedupe, guarda previa a conversión, rollback de rechazo y auditoría y correspondencia con la consulta bloqueada. Las pruebas de entrada AMQP se incorporan en el siguiente slice.

La revisión independiente de fuente no encontró bloqueos; su ejecución funcional no estuvo disponible. No se afirma publicación, límite terminal ni compensación por agotamiento. No hay cambios a catálogos, identidad, PDF ni proveedores externos.
