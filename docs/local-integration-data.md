# Datos sintéticos para integración local

Datos **ficticios** (nombres y CI/NIT obviamente falsos, `LOCAL-000N`) para probar el frontend `mf-erp` contra MS1 en el perfil `local`. Ningún dato corresponde a pacientes reales.

## Mecanismo de carga

- Archivo: `src/main/resources/db/local/R__local_integration_seed.sql`, una migración **repetible** de Flyway.
- Solo el perfil `local` añade `classpath:db/local` a `spring.flyway.locations`; el perfil de producción usa únicamente `classpath:db/migration` y **nunca** ve estos datos.
- Idempotente: todo es `INSERT ... ON CONFLICT DO NOTHING` con UUID fijos del rango `a?000000-0000-0000-0000-…`. No hay `DELETE`, `TRUNCATE` ni `UPDATE`, y las filas del seed V2 no se tocan. Reejecutarla (o que Flyway la reaplique por cambio de checksum) no cambia ninguna fila.
- Las fechas relativas (`CURRENT_DATE`, `now()`, "esta semana") se fijan en la **primera carga**; `ON CONFLICT` conserva los valores originales. En una base local antigua, "esta semana" puede quedar desfasada: recrear la base local (no se hace automáticamente).
- Empresa: se reutiliza la fila de V2 (`00000000-0000-0000-0000-000000000001`); no se crea una segunda.
- Contraseña de los tres usuarios locales: `LocalDev2026!` (bcrypt `$2a$` vía pgcrypto).
- Recibo sembrado `LOCAL-REC-000001`: no puede chocar con el formato real `REC-<año>-<secuencia>` (V3), así que la secuencia `recibo_numero_seq` no se avanza y el primer recibo real sigue siendo `REC-<año>-000001`.
- Pruebas: `LocalSeedMigrationTest` (Flyway real sobre PostgreSQL 18) y `LocalOperationsSchemaTest` (valida cada operación de este documento contra `schema.graphqls`).

## Cabeceras

- `X-Dev-User: admin-scz` — acceso simulado, solo perfil `local` (usuarios: `admin-scz` ADMIN, `caja-scz` CAJERO, `recepcion-scz` RECEPCIONISTA).
- `Idempotency-Key: <UUID>` — un UUID nuevo por mutación; repetir la misma key con el mismo payload no duplica registros.
- Endpoint GraphQL: `POST http://localhost:8080/graphql`.

## IDs sembrados

| ID | Qué es |
|---|---|
| `a1000000-0000-0000-0000-000000000001` | Usuario `admin-scz` (ADMIN) |
| `a1000000-0000-0000-0000-000000000002` | Usuario `caja-scz` (CAJERO) — usar como `usuarioCajeroId` |
| `a1000000-0000-0000-0000-000000000003` | Usuario `recepcion-scz` (RECEPCIONISTA) |
| `a2000000-0000-0000-0000-000000000001` / `…002` | Especialidades `LOC-ODG` / `LOC-CIR` |
| `a3000000-0000-0000-0000-000000000001` / `…002` | Odontólogos `LOCAL-OD-0001` (General) / `LOCAL-OD-0002` (General + Cirugía) |
| `a4000000-0000-0000-0000-000000000001` / `…002` | Consultorios `LOC-C01` / `LOC-C02` |
| `a5000000-0000-0000-0000-000000000001` … `…005` | Pacientes `LOCAL-0001` … `LOCAL-0005` |
| `a6000000-0000-0000-0000-000000000001` | Servicio `LOC-CIR-3MR` "Exodoncia de Tercer Molar Retenido" — 1250.00 Bs |
| `a6000000-0000-0000-0000-000000000002` | Servicio `LOC-ODG-RFR` "Restauración Fotopolimerizable con Resina" — 280.00 Bs |
| `a6000000-0000-0000-0000-000000000003` | Servicio `LOC-ODG-PRF` "Profilaxis Dental Local" — 180.00 Bs |
| `a7000000-0000-0000-0000-000000000001` | Orden **CONTADO** PENDIENTE, 280.00 Bs (paciente 1) — prueba de efectivo |
| `a9000000-0000-0000-0000-000000000001` | Cuota única de esa orden (280.00, PENDIENTE) — **cuota para efectivo** |
| `a7000000-0000-0000-0000-000000000002` | Orden **CREDITO** PENDIENTE, 1250.00 − 250.00 = 1000.00 Bs en 3 cuotas (paciente 2) — prueba de QR |
| `a9000000-0000-0000-0000-000000000002` / `…003` / `…004` | Cuotas 1/2/3: 333.33 / 333.33 / 333.34 (la última lleva el centavo restante). `…002` es la **cuota para QR** |
| `a7000000-0000-0000-0000-000000000003` | Orden PENDIENTE 560.00 Bs (paciente 3) con su cuota única por defecto — **orden para `configurarPlanDePagos`** |
| `a9000000-0000-0000-0000-000000000005` | Cuota única de esa orden (560.00, PENDIENTE) |
| `a7000000-0000-0000-0000-000000000004` | Orden **PARCIAL** 900.00 Bs, saldo 450.00 (paciente 4) |
| `a9000000-0000-0000-0000-000000000006` / `…007` | Cuota 1 PAGADA 450.00 / cuota 2 PENDIENTE 450.00 |
| `aa000000-0000-0000-0000-000000000001` | Movimiento INGRESO EFECTIVO 450.00 de la cuota pagada |
| `ab000000-0000-0000-0000-000000000001` | Recibo `LOCAL-REC-000001` de ese movimiento |
| `aa000000-0000-0000-0000-000000000002` … `…010` | Movimientos para reportes: INGRESO/EGRESO, EFECTIVO/QR_PAGOFACIL/TRANSFERENCIA, esta semana (`…002`–`…007`) y semana pasada (`…008`–`…010`) |
| `a8000000-0000-0000-0000-00000000000N` | Ítems de las 4 órdenes |

Nota: el dominio siempre crea al menos una cuota por orden (`crearOrdenCobroManual` genera un plan CONTADO de 1 cuota), por lo que no existen órdenes sin plan. La orden `a7…003` sirve para reconfigurar el plan (solo se puede mientras no tenga pagos).

## Operaciones GraphQL listas para usar

Fuente única: `src/test/resources/local-integration/operations.graphql` (validadas contra el esquema real por `LocalOperationsSchemaTest`; este documento las embebe textualmente). Cada bloque muestra la operación y sus variables. `DateTime` usa ISO-8601 con offset (`2026-01-01T00:00:00-04:00`), `Date` usa `YYYY-MM-DD` y los montos `BigDecimal` se envían como string o número con 2 decimales. Para "esta semana" en `obtenerFlujoCaja`, ajustá `fechaInicio` al lunes 00:00 (-04:00) de la semana actual.

Las mutaciones que mueven dinero (`cobrarCuotaEnEfectivo`, `generarQrPagoFacil`, `simularConfirmacionQrDev`, `registrarEgresoCaja`) deben enviar `Idempotency-Key`. `simularConfirmacionQrDev` solo existe en el perfil `local`; usá el `pagofacilTransactionId` devuelto por `generarQrPagoFacil`.

### listarPacientes

Variables:

```json
{"busqueda":"LOCAL"}
```

Operación:

```graphql
query ListarPacientes($busqueda: String) {
  listarPacientes(busqueda: $busqueda) { id ciNit nombres apellidos telefonoWhatsapp }
}

```

### listarServiciosOdontologicos

Variables:

```json
{"especialidadId":"a2000000-0000-0000-0000-000000000002"}
```

Operación:

```graphql
query ListarServicios($especialidadId: UUID) {
  listarServiciosOdontologicos(especialidadId: $especialidadId) {
    id codigo nombre precioBaseBs especialidad { codigo nombre }
  }
}

```

### listarOrdenesCobroPendientes

Variables:

```json
{"estado":"PENDIENTE","pacienteId":null}
```

Operación:

```graphql
query ListarOrdenes($estado: EstadoOrdenCobro, $pacienteId: UUID) {
  listarOrdenesCobro(estado: $estado, pacienteId: $pacienteId) {
    id estado modalidadPago montoTotalBs saldoPendienteBs cantidadCuotas paciente { ciNit nombres }
  }
}

```

### obtenerOrdenCobroDetalle

Variables:

```json
{"id":"a7000000-0000-0000-0000-000000000002"}
```

Operación:

```graphql
query ObtenerOrden($id: UUID!) {
  obtenerOrdenCobroDetalle(id: $id) {
    id estado modalidadPago montoSubtotalBs descuentoBs montoTotalBs saldoPendienteBs
    paciente { id ciNit nombres apellidos }
    items { servicio { nombre } piezaDental cantidad precioUnitarioBs subtotalBs }
    cuotas { id numeroCuota montoCuotaBs fechaVencimiento estado fechaPago }
  }
}

```

### listarCuotasPendientesPorPaciente

Variables:

```json
{"pacienteId":"a5000000-0000-0000-0000-000000000002"}
```

Operación:

```graphql
query CuotasPendientes($pacienteId: UUID!) {
  listarCuotasPendientesPorPaciente(pacienteId: $pacienteId) {
    id numeroCuota montoCuotaBs fechaVencimiento estado
  }
}

```

### cobrarCuotaEnEfectivo

Variables:

```json
{"input":{"cuotaId":"a9000000-0000-0000-0000-000000000001","usuarioCajeroId":"a1000000-0000-0000-0000-000000000002","ciNitCliente":"LOCAL-0001","razonSocialCliente":"Ximena Sintética Cruceña Uno"}}
```

Operación:

```graphql
mutation CobrarEfectivo($input: CobrarEfectivoInput!) {
  cobrarCuotaEnEfectivo(input: $input) {
    id numeroRecibo ciNitCliente razonSocialCliente concepto montoTotalBs fechaEmision
  }
}

```

### generarQrPagoFacil

Variables:

```json
{"cuotaId":"a9000000-0000-0000-0000-000000000002","ciNitCliente":"LOCAL-0002","razonSocialCliente":"Rodrigo Sintético Camba Dos"}
```

Operación:

```graphql
mutation GenerarQr($cuotaId: UUID!, $ciNitCliente: String!, $razonSocialCliente: String!) {
  generarQrPagoFacil(cuotaId: $cuotaId, ciNitCliente: $ciNitCliente, razonSocialCliente: $razonSocialCliente) {
    id cuotaId pagofacilTransactionId montoBs estado fechaExpiracion qrImagenBase64
  }
}

```

### simularConfirmacionQrDev

Variables:

```json
{"pagofacilTransactionId":"<pagofacilTransactionId devuelto por generarQrPagoFacil>"}
```

Operación:

```graphql
mutation SimularConfirmacion($pagofacilTransactionId: String!) {
  simularConfirmacionQrDev(pagofacilTransactionId: $pagofacilTransactionId) {
    id numeroRecibo montoTotalBs fechaEmision
  }
}

```

### configurarPlanDePagos

Variables:

```json
{"input":{"ordenCobroId":"a7000000-0000-0000-0000-000000000003","modalidadPago":"CREDITO","descuentoBs":"0.00","cantidadCuotas":3,"fechaPrimeraCuota":"2026-11-01","intervaloDias":30}}
```

Operación:

```graphql
mutation ConfigurarPlan($input: ConfigurarPlanPagosInput!) {
  configurarPlanDePagos(input: $input) {
    id modalidadPago montoTotalBs saldoPendienteBs cantidadCuotas
    cuotas { id numeroCuota montoCuotaBs fechaVencimiento estado }
  }
}

```

### crearOrdenCobroManual

Variables:

```json
{"input":{"pacienteId":"a5000000-0000-0000-0000-000000000005","odontologoId":"a3000000-0000-0000-0000-000000000001","items":[{"servicioId":"a6000000-0000-0000-0000-000000000003","piezaDental":null,"cantidad":1}],"descuentoBs":"0.00","observaciones":"Orden creada desde prueba local"}}
```

Operación:

```graphql
mutation CrearOrden($input: CrearOrdenCobroInput!) {
  crearOrdenCobroManual(input: $input) {
    id estado montoTotalBs saldoPendienteBs cuotas { id numeroCuota montoCuotaBs estado }
  }
}

```

### registrarEgresoCaja

Variables:

```json
{"input":{"usuarioCajeroId":"a1000000-0000-0000-0000-000000000002","categoria":"INSUMOS","metodoPago":"EFECTIVO","montoBs":"85.50","descripcion":"Guantes y mascarillas (prueba local)"}}
```

Operación:

```graphql
mutation RegistrarEgreso($input: RegistrarEgresoInput!) {
  registrarEgresoCaja(input: $input) { id tipoMovimiento categoria metodoPago montoBs descripcion fechaMovimiento }
}

```

### obtenerFlujoCaja

Variables:

```json
{"fechaInicio":"2026-01-01T00:00:00-04:00","fechaFin":"2099-12-31T23:59:59-04:00"}
```

Operación:

```graphql
query FlujoCaja($fechaInicio: DateTime!, $fechaFin: DateTime!) {
  obtenerFlujoCaja(fechaInicio: $fechaInicio, fechaFin: $fechaFin) {
    totalIngresosBs totalEgresosBs saldoNetoBs
    movimientos { id tipoMovimiento categoria metodoPago montoBs descripcion fechaMovimiento recibo { numeroRecibo } }
  }
}

```

### ejecutarAgenteErp

Variables:

```json
{"instruccion":"Genera un reporte de ingresos por método de pago"}
```

Operación:

```graphql
mutation EjecutarAgenteErp($instruccion: String!) {
  ejecutarAgenteErp(instruccion: $instruccion) {
    interaccionId respuestaNaturalAgente toolsMcpInvocadas accionCrudEjecutada
    notificacionN8nEnviada
    reporteGenerado {
      reporteId consultaSqlEjecutada columnas filasJson tipoGraficoSugerido
      urlDescargaExcel urlDescargaPdf
    }
  }
}
```

### Edición y baja lógica

Una operación por cada `actualizar*`/`eliminar*` nueva. Un `actualizar*` reemplaza todos los campos del `Crear*Input` equivalente; `eliminar*` es baja lógica (`activo = false`; consultorio → `estado = INACTIVO`), idempotente y devuelve `true`. Reglas, roles y eventos: [frontend-contract-diff.md](frontend-contract-diff.md) y [catalog-outbox.md](catalog-outbox.md).

Cuidado con los datos sembrados: la carga es repetible con `ON CONFLICT DO NOTHING`, así que **no reactiva** lo dado de baja (no hay operación de reactivación salvo `actualizarUsuario` con `activo: true`). Para probar bajas sin perder datos de prueba, cree primero una entidad descartable con `crear*` y use su `id`. `eliminarEspecialidad` sobre `a2…002` responde `BAD_REQUEST` mientras existan servicios u odontólogos activos que la usen (el guardia de edición y baja lógica). `actualizarUsuario` solo funciona con `admin-scz` como actor (`X-Dev-User: admin-scz`) y nadie puede desactivarse a sí mismo.

#### actualizarPaciente

Variables:

```json
{"id":"a5000000-0000-0000-0000-000000000005","input":{"ciNit":"LOCAL-0005","nombres":"Paciente Sintético","apellidos":"Cinco Editado","fechaNacimiento":"1992-05-05","genero":"FEMENINO","telefonoWhatsapp":"+59170000005","email":null,"direccion":"Dato ficticio editado"}}
```

Operación:

```graphql
mutation ActualizarPaciente($id: UUID!, $input: ActualizarPacienteInput!) {
  actualizarPaciente(id: $id, input: $input) { id ciNit nombres apellidos telefonoWhatsapp email direccion activo }
}
```

#### eliminarPaciente

Variables:

```json
{"id":"a5000000-0000-0000-0000-000000000005"}
```

Operación:

```graphql
mutation EliminarPaciente($id: UUID!) {
  eliminarPaciente(id: $id)
}
```

#### actualizarOdontologo

Variables:

```json
{"id":"a3000000-0000-0000-0000-000000000002","input":{"ci":"LOCAL-OD-0002","matriculaProfesional":"LOCAL-MAT-0002","nombres":"Odontólogo","apellidos":"Prueba Camba","telefono":"+59170000102","email":"odontologo.prueba@local.invalid","porcentajeComision":"32.50","especialidadesIds":["a2000000-0000-0000-0000-000000000001","a2000000-0000-0000-0000-000000000002"]}}
```

Operación:

```graphql
mutation ActualizarOdontologo($id: UUID!, $input: ActualizarOdontologoInput!) {
  actualizarOdontologo(id: $id, input: $input) {
    id ci matriculaProfesional nombres apellidos porcentajeComision activo especialidades { codigo nombre }
  }
}
```

#### eliminarOdontologo

Variables:

```json
{"id":"a3000000-0000-0000-0000-000000000001"}
```

Operación:

```graphql
mutation EliminarOdontologo($id: UUID!) {
  eliminarOdontologo(id: $id)
}
```

#### actualizarEspecialidad

Variables:

```json
{"id":"a2000000-0000-0000-0000-000000000002","input":{"codigo":"LOC-CIR","nombre":"Cirugía Bucal (local)","descripcion":"Especialidad sintética para pruebas locales"}}
```

Operación:

```graphql
mutation ActualizarEspecialidad($id: UUID!, $input: ActualizarEspecialidadInput!) {
  actualizarEspecialidad(id: $id, input: $input) { id codigo nombre descripcion activo }
}
```

#### eliminarEspecialidad

Variables:

```json
{"id":"a2000000-0000-0000-0000-000000000002"}
```

Operación:

```graphql
mutation EliminarEspecialidad($id: UUID!) {
  eliminarEspecialidad(id: $id)
}
```

#### actualizarConsultorio

Variables:

```json
{"id":"a4000000-0000-0000-0000-000000000002","input":{"codigo":"LOC-C02","nombre":"Consultorio Local 2","ubicacion":"Primer piso (sintético)","estado":"MANTENIMIENTO"}}
```

Operación:

```graphql
mutation ActualizarConsultorio($id: UUID!, $input: ActualizarConsultorioInput!) {
  actualizarConsultorio(id: $id, input: $input) { id codigo nombre ubicacion estado }
}
```

#### eliminarConsultorio

Variables:

```json
{"id":"a4000000-0000-0000-0000-000000000002"}
```

Operación:

```graphql
mutation EliminarConsultorio($id: UUID!) {
  eliminarConsultorio(id: $id)
}
```

#### actualizarServicioOdontologico

Variables:

```json
{"id":"a6000000-0000-0000-0000-000000000003","input":{"especialidadId":"a2000000-0000-0000-0000-000000000001","codigo":"LOC-ODG-PRF","nombre":"Profilaxis Dental Local","descripcion":"Servicio sintético","precioBaseBs":"190.00"}}
```

Operación:

```graphql
mutation ActualizarServicio($id: UUID!, $input: ActualizarServicioInput!) {
  actualizarServicioOdontologico(id: $id, input: $input) {
    id codigo nombre precioBaseBs activo especialidad { codigo }
  }
}
```

#### eliminarServicioOdontologico

Variables:

```json
{"id":"a6000000-0000-0000-0000-000000000003"}
```

Operación:

```graphql
mutation EliminarServicio($id: UUID!) {
  eliminarServicioOdontologico(id: $id)
}
```

#### actualizarUsuario

Variables:

```json
{"id":"a1000000-0000-0000-0000-000000000003","input":{"rolCodigo":"RECEPCIONISTA","activo":true,"nombreCompleto":"Recepción Local","email":null}}
```

Operación:

```graphql
mutation ActualizarUsuario($id: UUID!, $input: ActualizarUsuarioInput!) {
  actualizarUsuario(id: $id, input: $input) { id username email nombreCompleto activo rol { codigo } }
}
```

## Agente y MCP

Los siguientes ejemplos completan `operations.graphql`. El CRUD crea un servicio real: use un código nuevo en cada prueba. Sin API key en `local`, las respuestas indican respaldo determinístico, no IA. Para exportar use las URLs de `reporteGenerado` con la autenticación ADMIN del propietario. El monitoreo autónomo nunca modifica órdenes ni caja; con n8n habilitado puede notificar cuotas vencidas.

```graphql

#@ obtenerEstadoServidorMcp
#@vars {}
query EstadoMcp {
  obtenerEstadoServidorMcp { activo protocolVersion toolsRegistradas { nombre categoria descripcion } }
}

#@ listarHistorialAgente
#@vars {}
query HistorialAgente {
  listarHistorialAgente(limite: 20) {
    interaccionId fechaEjecucion instruccionOriginal respuestaNaturalAgente toolsMcpInvocadas
  }
}

#@ monitoreoAutonomo
#@vars {}
mutation MonitoreoAutonomo {
  ejecutarAgenteErp(instruccion: "Monitorea mi negocio", modoAutonomoMonitoreo: true) {
    interaccionId respuestaNaturalAgente toolsMcpInvocadas accionCrudEjecutada notificacionN8nEnviada
  }
}

#@ crearServicioPorAgente
#@vars {}
mutation CrearServicioPorAgente {
  ejecutarAgenteErp(instruccion: "Crear servicio codigo=AGT-DEMO nombre=\"Limpieza demo\" precio=150") {
    interaccionId respuestaNaturalAgente toolsMcpInvocadas accionCrudEjecutada
  }
}

```
