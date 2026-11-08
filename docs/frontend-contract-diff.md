# Contrato GraphQL: frontend `mf-erp` vs backend `ms-erp`

> **Migración agente/MCP:** `generarReporteInteligente` fue eliminada. Use `ejecutarAgenteErp`, `listarHistorialAgente` y `obtenerEstadoServidorMcp`; las descargas ahora son `/api/v1/reports/export/{reporteId}/{format}`. El snapshot del frontend se conserva y marca la consulta antigua como INVALID. Las demás operaciones no cambian.

Fuente de verdad: `docs/graphql/schema.graphqls` (SDL servido) y `docs/graphql/introspection.json`; ambos se verifican contra el esquema real en `SchemaExportTest` (no pueden desfasarse). Los ejemplos válidos están en `src/test/resources/contract/real-examples.graphql` (apéndice al final, validados por `FrontendExamplesValidationTest`). La instantánea de las operaciones del frontend está en `src/test/resources/contract/frontend-operations.graphql`.

**Regla:** fuera de la eliminación solicitada de `generarReporteInteligente` y la nueva ruta de exportación, ningún contrato existente del backend cambia de significado ni de nulabilidad. S9 (2026-10-23) añadió, solo de forma aditiva, 11 operaciones de edición/baja, 6 inputs `Actualizar*Input` y el campo `activo: Boolean!` en `Paciente`, `Odontologo`, `Especialidad` y `ServicioOdontologico` (sección "Edición y baja lógica (S9)"). Todo lo demás es "qué debe adaptar el frontend"; solo las 3 operaciones de caja/QR sin equivalente siguen requiriendo una decisión.

La lectura demográfica añade únicamente `Paciente.fechaNacimiento: Date` y `Paciente.genero: String`, nullable, sin cambiar inputs ni permisos. `actualizarPaciente` sigue reemplazando: omitir estos campos los borra; para conservarlos hay que leerlos y reenviarlos (ver [guía](patient-demographic-readback.md)). `docs/graphql/introspection-live.json` es una instantánea histórica, no el contrato canónico actual; no se regenera consultando un servidor compartido.

## Alta atómica de usuario con identidad

`crearUsuarioConIdentidad` añade una entrada ADMIN para crear usuario y enlazar el
subject Keycloak existente, con `odontologoId` opcional para un odontólogo existente.
Todo el ERP se confirma o revierte en una sola transacción; no crea cuentas Keycloak.
El replay idéntico devuelve `Usuario!` con el mismo `id` y sin otra auditoría; perfil, contraseña o asociación distintos
fallan sin sobrescribir. Los callers de aprovisionamiento deben migrar a esta entrada:
las operaciones legacy independientes no hacen rollback de un request anterior.
Ver [contrato y errores](ms1-integration.md#alta-atómica-de-usuario-e-identidad-admin).

```graphql
mutation CrearUsuarioConIdentidad($input: CrearUsuarioConIdentidadInput!) {
  crearUsuarioConIdentidad(input: $input) {
    id username email nombreCompleto activo rol { codigo }
  }
}
```

```json
{"input":{"usuario":{"rolCodigo":"CAJERO","username":"cajero-atomico","email":"cajero-atomico@example.test","nombreCompleto":"Cajero sintético","password":"Synthetic2026!"},"keycloakSubject":"synthetic:atomic-cashier"}}
```

## Resumen

**37 operaciones del frontend validadas: 2 válidas, 35 inválidas** contra el esquema servido.

| Estado | Operaciones |
| --- | --- |
| OK (válidas tal cual) | 2: `listarEspecialidades`, `listarPacientes` |
| Existe con otra forma → el frontend se adapta | 31: 20 de campos/args/inputs/enums/tipos + las 11 operaciones de S9, que ya existen pero cuyos documentos del frontend siguen usando `ID!`, inputs con otro nombre o campos distintos |
| NO EXISTE en backend | 4 (incluye la consulta de reportes eliminada) |

### Operaciones que el backend NO ofrece

| Operación del frontend | Recomendación |
| --- | --- |
| `obtenerOrdenCobro` | **Adaptar frontend**: usar `obtenerOrdenCobroDetalle(id: UUID!)` (mismo dato, otro nombre). |
| `listarLogsAuditoria` | **Adaptar frontend**: usar `listarAuditLogs(limite)`. |
| `obtenerResumenCaja`, `listarMovimientosCaja` | **Adaptar frontend**: una sola llamada `obtenerFlujoCaja(fechaInicio: DateTime!, fechaFin: DateTime!)` devuelve `totalIngresosBs/totalEgresosBs/saldoNetoBs` y `movimientos[]`. |
| `consultarEstadoQrPagoFacil` | **Adaptar frontend (provisional)**: no hay consulta por transacción. Hacer polling de `obtenerOrdenCobroDetalle { cuotas { estado fechaPago } }`; la cuota pasa a `PAGADA` al confirmarse. Para obtener el recibo, `obtenerFlujoCaja { movimientos { recibo {...} } }`. **Decisión pendiente (usuario):** si se quiere un polling directo y barato, hace falta una operación NUEVA (`consultarEstadoQr(pagofacilTransactionId): TransaccionQr!`). |
| `actualizarPaciente`, `actualizarOdontologo`, `actualizarEspecialidad`, `actualizarConsultorio`, `actualizarServicioOdontologico`, `actualizarUsuario` | **YA EXISTEN (S9).** Adaptar el documento: `$id: UUID!`, input `Actualizar<Entidad>Input` (ver sección S9) y los campos de respuesta reales. |
| `eliminarPaciente`, `eliminarOdontologo`, `eliminarEspecialidad`, `eliminarConsultorio`, `eliminarServicioOdontologico` | **YA EXISTEN (S9)** como **baja lógica** (`activo = false`; consultorio → `estado = INACTIVO`), nunca borran filas. Solo hay que cambiar `$id: ID!` por `$id: UUID!`; devuelven `Boolean!`. |

(Las 3 que "NO EXISTEN" son: `consultarEstadoQrPagoFacil`, `obtenerResumenCaja` y `listarMovimientosCaja`. `obtenerOrdenCobro` y `listarLogsAuditoria` son renombres y cuentan en las 31 "adaptar", junto con los 6 `actualizar*` y 5 `eliminar*` de S9.)

## Reglas transversales

| Tema | Frontend | Backend real | Qué cambiar en el frontend |
| --- | --- | --- | --- |
| Escalar de ids | `ID!` | `UUID!` (el esquema **no define `ID`**: `Unknown type 'ID'`) | Declarar variables como `UUID!` / `UUID`. En JSON siguen siendo strings. |
| Montos | `number` | `BigDecimal`: se serializa como **número JSON** (ej. `150.50`); como entrada acepta número o string | OK. Nombres terminan en `Bs` (`montoTotalBs`, `precioBaseBs`…). |
| Fechas de calendario | `string` | `Date` = `yyyy-MM-dd` (`fechaVencimiento`, `fechaPrimeraCuota`, `fechaNacimiento`) | Enviar/leer `2026-11-15`. |
| Instantes | `string` | `DateTime` = ISO-8601 **con offset** (`2026-10-22T10:15:30-04:00`); entrada sin offset se rechaza | Enviar offset siempre (`toISOString()` con `Z` es válido). |
| Rangos de caja | `String!` | `DateTime!` | Cambiar tipo de variables y enviar instantes con offset. |
| Nulabilidad | campos opcionales | casi todo `!`; opcionales reales: `emailContacto`, `Paciente.email/direccion/fechaNacimiento/genero`, `Odontologo.email`, `Consultorio.ubicacion`, `descripcion`, `OrdenCobro.odontologo/consultaExternaId`, `CuotaPago.fechaPago`, `Recibo.pdfUrlS3`, `TransaccionQr.fechaExpiracion`, `MovimientoCaja.recibo` | Que el frontend tolere más campos no nulos de los que modela no rompe nada. Lo que el frontend marca opcional y el backend exige (`telefonoWhatsapp`, `telefono`) hay que volverlo obligatorio en formularios. |
| Campos extra del backend | — | Campos `!` que el frontend no pide (`reporteId`, `preguntaOriginal`, `enviadoWhatsapp`…) | Sin problema: GraphQL solo devuelve lo pedido. |
| Roles (servidor) | — | `ANY_ROLE` = ADMIN/CAJERO/RECEPCIONISTA/ODONTOLOGO; lecturas de catálogos y empresa. `ADMIN`: usuarios, auditoría, reportes, actualizar empresa, crear especialidad/odontólogo/consultorio/servicio. `ADMIN`/`RECEPCIONISTA`: `crearPaciente`, `actualizarPaciente`, `eliminarPaciente`. `ADMIN` (S9): `actualizarOdontologo/Especialidad/Consultorio/ServicioOdontologico/Usuario` y `eliminarOdontologo/Especialidad/Consultorio/ServicioOdontologico`. `ADMIN`/`CAJERO`/`RECEPCIONISTA`: lecturas y planes de órdenes de cobro, `crearOrdenCobroManual`, `configurarPlanDePagos`, `obtenerFlujoCaja`. `ADMIN`/`CAJERO`: cobro en efectivo, egreso, QR, simulación, `Recibo.pdfUrlS3`. | Ocultar/deshabilitar acciones según rol; el servidor responde `FORBIDDEN` si no corresponde. |
| `Idempotency-Key` | UUID por mutación | Filtro en `POST` con la cabecera; sin cabecera la petición pasa sin protección | Enviarla siempre en mutaciones financieras. |

## Catálogos

| Uso en frontend | Contrato real | Estado | Qué cambia el frontend |
| --- | --- | --- | --- |
| `Paciente.fechaNacimiento`, `Paciente.genero` | `fechaNacimiento: Date`, `genero: String`, ambos nullable; devuelven los valores persistidos en creación, actualización, listado y detalle | OK (aditivo) | Fecha `yyyy-MM-dd`, género libre (no enum), sin valores por defecto; conservar `null`. Ver [guía de lectura y reenvío](patient-demographic-readback.md). |
| `PacienteInput` | `CrearPacienteInput`: `ciNit!, nombres!, apellidos!, fechaNacimiento, genero, telefonoWhatsapp!, email, direccion` | renombrar + nulabilidad | Usar el nombre real; `telefonoWhatsapp` es obligatorio; hay `direccion`, `fechaNacimiento` y `genero`, también disponibles en la respuesta. Ver ejemplo `CrearPaciente`. |
| `crearPaciente(input: PacienteInput!)`, `$id: ID!` | `crearPaciente(input: CrearPacienteInput!)`; `actualizarPaciente(id: UUID!, input: ActualizarPacienteInput!)`; `eliminarPaciente(id: UUID!): Boolean!` | tipo distinto | Cambiar el tipo del input y `ID!`→`UUID!`. Ver ejemplos `ActualizarPaciente`/`EliminarPaciente`. |
| `listarPacientes(busqueda)` | igual, devuelve `[Paciente!]!` | OK | — Ver `ListarPacientes`. |
| `Odontologo.matricula` | `matriculaProfesional` | renombrar | Cambiar nombre. |
| `Odontologo.especialidad { id nombre }` | `especialidades: [Especialidad!]!` (lista) | tipo distinto | Pasar a lista; el modelo del frontend admite una sola. |
| `Odontologo.activo` | `activo: Boolean!` (S9); `listarOdontologos(soloActivos: Boolean = true)` filtra | OK desde S9 | Pedirlo tal cual; para ver inactivos pasar `soloActivos: false`. |
| `OdontologoInput {nombres, apellidos, matricula, telefono?, especialidadId?, activo}` | `CrearOdontologoInput`: `ci!, matriculaProfesional!, nombres!, apellidos!, telefono!, email, porcentajeComision: BigDecimal!, especialidadesIds: [UUID!]!` | renombrar + campos nuevos obligatorios | Agregar `ci`, `porcentajeComision`, lista `especialidadesIds`; quitar `activo`. Ver `CrearOdontologo`. |
| `Especialidad {id nombre descripcion}` | además `codigo: String!` | campo extra | OK al leer; pedir `codigo` si se muestra. |
| `crearEspecialidad(input: EspecialidadInput!)` | `crearEspecialidad(codigo: String!, nombre: String!, descripcion: String)` — **argumentos sueltos, no hay input** | argumento distinto | Enviar tres argumentos; `codigo` es obligatorio. Ver `CrearEspecialidad`. |
| `Consultorio.activo` | `estado: String!` con valores `DISPONIBLE`, `MANTENIMIENTO`, `INACTIVO` (S9; `INACTIVO` = baja lógica) | renombrar + tipo distinto | Mapear `activo = estado !== 'INACTIVO'` (lectura; un consultorio en `MANTENIMIENTO` sigue activo). Además hay `codigo!`. |
| `ConsultorioInput {nombre, ubicacion, activo}` | `CrearConsultorioInput {codigo!, nombre!, ubicacion}` | renombrar | `codigo` obligatorio; sin `activo`. Ver `CrearConsultorio`. |
| `ServicioOdontologico.precioBs` | `precioBaseBs: BigDecimal!` | renombrar | Cambiar nombre. |
| `ServicioOdontologico.activo` | `activo: Boolean!` (S9) | OK desde S9 | Pedirlo tal cual. |
| `ServicioOdontologicoInput` | `CrearServicioInput {especialidadId, codigo!, nombre!, descripcion, precioBaseBs!}` | renombrar | Ver `CrearServicioOdontologico`. `listarServiciosOdontologicos(especialidadId: UUID)` admite filtro. |
| `actualizar*`/`eliminar*` de paciente, odontólogo, especialidad, consultorio, servicio | existen desde S9 | renombrar inputs / `ID!`→`UUID!` | Ver sección "Edición y baja lógica (S9)". |

## Empresa, usuarios y auditoría

| Uso en frontend | Contrato real | Estado | Qué cambia el frontend |
| --- | --- | --- | --- |
| `obtenerConfiguracionEmpresa { nit razonSocial direccion telefono email ciudad }` | `{ id nombreComercial razonSocial nit direccion telefonoContacto emailContacto monedaSimbolo }`, tipo `EmpresaConfig!` (no nulo) | renombrar | `telefono`→`telefonoContacto`, `email`→`emailContacto`; **`ciudad` no existe** (la ciudad va dentro de `direccion`). Nuevo `nombreComercial`. |
| `ConfiguracionEmpresaInput` | `EmpresaConfigInput {nombreComercial!, razonSocial!, nit!, direccion!, telefonoContacto!, emailContacto}` | renombrar + nulabilidad | `direccion` y `telefonoContacto` son obligatorios. Solo ADMIN. Ver `ActualizarConfiguracionEmpresa`. |
| `listarUsuarios { rol }` (string) | `rol: Rol!` (objeto: `id codigo nombre permisos`) | tipo distinto | Pedir `rol { codigo nombre }` y usar `rol.codigo`. Solo ADMIN. Hay `email!`. |
| `actualizarUsuario(id, input {rol, activo})` | `actualizarUsuario(id: UUID!, input: ActualizarUsuarioInput!)` con `{rolCodigo: String!, activo: Boolean!, nombreCompleto, email}` (S9, solo ADMIN) | renombrar | `rol`→`rolCodigo`; `activo` es obligatorio; respuesta con `rol { codigo }` (no escalar). Ver sección S9. |
| `listarLogsAuditoria(limite)` | `listarAuditLogs(limite: Int = 50)` | renombrar | Solo ADMIN. |
| `LogAuditoria {id fecha usuario accion entidad detalle}` | `AuditLog {id accion entidadAfectada entidadId detalleJson createdAt}` | renombrar | `fecha`→`createdAt` (`DateTime`), `entidad`→`entidadAfectada`, `detalle`→`detalleJson` (string JSON); **no hay `usuario`**. |

## Órdenes de cobro y cuotas

| Uso en frontend | Contrato real | Estado | Qué cambia el frontend |
| --- | --- | --- | --- |
| `obtenerOrdenCobro(id: ID!)` | `obtenerOrdenCobroDetalle(id: UUID!): OrdenCobro` (nullable) | renombrar | Ver `ObtenerOrdenCobroDetalle`. |
| `listarOrdenesCobro(estado)` | `listarOrdenesCobro(estado: EstadoOrdenCobro, pacienteId: UUID)` | OK (args) | Ver `ListarOrdenesCobro`. |
| `OrdenCobro.codigo`, `origen`, `concepto` | no existen (hay `id`, `consultaExternaId` si viene de MS2, y `items[]` con servicio) | NO EXISTE | Mostrar `id`/`items`; el "concepto" se arma con `items.servicio.nombre`. |
| `montoTotal`, `descuento`, `montoNeto` | `montoSubtotalBs`, `descuentoBs`, `montoTotalBs` (= neto), `saldoPendienteBs` | renombrar + semántica distinta | `montoTotal` (frontend, bruto) ≈ `montoSubtotalBs`; `montoNeto` ≈ `montoTotalBs`. |
| `tipoPago` (`TipoPago`) | `modalidadPago: ModalidadPago!` (`CONTADO`, `CREDITO`) | renombrar | Nombre del campo y del enum. |
| `fechaCreacion` | `createdAt: DateTime!` | renombrar | — |
| `Cuota { numero monto }` | `CuotaPago { numeroCuota montoCuotaBs fechaVencimiento estado fechaPago }` | renombrar | — |
| `EstadoOrdenCobro` `PAGADA`/`ANULADA` | `PENDIENTE PARCIAL PAGADO ANULADO` | enum distinto | `PAGADA`→`PAGADO`, `ANULADA`→`ANULADO`. Enviar `PAGADA` falla con error de enum. |
| `EstadoCuota` `PENDIENTE PAGADA VENCIDA` | `PENDIENTE PAGADA VENCIDA ANULADA` | enum (extra valor) | Agregar `ANULADA`. |
| `TipoPago` | `ModalidadPago` | renombrar | — |
| `MetodoPago` `EFECTIVO QR_PAGOFACIL` | `EFECTIVO QR_PAGOFACIL TRANSFERENCIA` | enum (extra valor) | Agregar `TRANSFERENCIA`. |
| `crearOrdenCobroManual(input: CrearOrdenCobroManualInput {pacienteId, concepto, servicioIds[], montoTotal})` | `CrearOrdenCobroInput {pacienteId!, odontologoId, items: [{servicioId!, piezaDental, cantidad!}]!, descuentoBs = 0, observaciones}` | tipo distinto + semántica | El monto lo calcula el backend desde el arancel (no se envía `montoTotal`); no hay `concepto`; cada servicio es un ítem con `cantidad`. Ver `CrearOrdenCobroManual`. |
| `configurarPlanDePagos(input: ConfigurarPlanDePagosInput {ordenCobroId, tipoPago, numeroCuotas, descuento, fechaPrimerVencimiento})` | `ConfigurarPlanPagosInput {ordenCobroId!, modalidadPago!, descuentoBs = 0, cantidadCuotas!, fechaPrimeraCuota: Date!, intervaloDias = 30}` | renombrar (el tipo se llama `ConfigurarPlanPagosInput`, sin "De") | Ver `ConfigurarPlanDePagos`. |
| `listarCuotasPendientesPorPaciente` | existe en el backend, el frontend no lo usa | extra | Útil para la pantalla de cobros. |

## Caja, cobros, QR y recibos

| Uso en frontend | Contrato real | Estado | Qué cambia el frontend |
| --- | --- | --- | --- |
| `cobrarCuotaEnEfectivo(input {cuotaId, ciNit, razonSocial, montoRecibido})` | `CobrarEfectivoInput {cuotaId!, usuarioCajeroId!, ciNitCliente!, razonSocialCliente!}` | renombrar + campo obligatorio nuevo + campo inexistente | `ciNit`→`ciNitCliente`, `razonSocial`→`razonSocialCliente`; **`montoRecibido` no existe** (cobra el monto de la cuota completa; calcular el vuelto en el cliente); **`usuarioCajeroId` (UUID del usuario) es obligatorio**: es el `id` de `Usuario` (p. ej. de `listarUsuarios`, que es solo ADMIN); hoy el frontend no lo tiene. Ver `CobrarCuotaEnEfectivo`. |
| `Recibo { monto metodoPago ciNit razonSocial urlDescargaPdf }` | `Recibo { id numeroRecibo ciNitCliente razonSocialCliente concepto montoTotalBs pdfUrlS3 enviadoWhatsapp fechaEmision }` | renombrar | `monto`→`montoTotalBs`, `ciNit`→`ciNitCliente`, `razonSocial`→`razonSocialCliente`, `urlDescargaPdf`→`pdfUrlS3`; **`metodoPago` no existe en `Recibo`** (está en `MovimientoCaja.metodoPago`). |
| `generarQrPagoFacil(cuotaId: ID!)` | `generarQrPagoFacil(cuotaId: UUID!, ciNitCliente: String!, razonSocialCliente: String!)` | argumento distinto | Enviar NIT/razón social. Ver `GenerarQrPagoFacil`. |
| `QrPagoFacil {monto estado …}` | `TransaccionQr {id cuotaId pagofacilTransactionId qrImagenBase64 montoBs estado fechaExpiracion}` | renombrar + enum distinto | Tipo `TransaccionQr`; `monto`→`montoBs`. `estado` es **String libre**, valores reales `GENERADO` / `CONFIRMADO` (no `PENDIENTE/PAGADO/EXPIRADO`). |
| `consultarEstadoQrPagoFacil` | no existe | NO EXISTE | Ver tabla de operaciones faltantes. |
| `simularConfirmacionQrDev` devuelve `QrPagoFacil` con `recibo` | devuelve **`Recibo!`** | tipo distinto | Pedir campos de `Recibo` directamente. Solo en perfil `dev` con simulación habilitada y pasarela mock (si no: error de regla de negocio). Roles ADMIN/CAJERO. Ver `SimularConfirmacionQrDev`. |
| `registrarEgresoCaja(input {concepto, categoria: CategoriaEgreso, monto, fecha, comprobante})` | `RegistrarEgresoInput {usuarioCajeroId!, categoria: String!, metodoPago: MetodoPago!, montoBs!, descripcion!}` | renombrar + obligatorios nuevos | `concepto`→`descripcion`, `monto`→`montoBs`; sin `fecha` (la pone el servidor) ni `comprobante`; `metodoPago` y `usuarioCajeroId` obligatorios. `categoria` es **String libre** (máx. 50 caracteres), no enum: el enum del frontend es solo UI. Ver `RegistrarEgresoCaja`. |
| `MovimientoCaja { fecha tipo concepto monto reciboId }` | `{ id tipoMovimiento categoria metodoPago montoBs descripcion fechaMovimiento recibo { id numeroRecibo … } }` | renombrar | `fecha`→`fechaMovimiento`, `tipo`→`tipoMovimiento`, `concepto`→`descripcion`, `monto`→`montoBs`, `reciboId`→`recibo.id`; `metodoPago` es no nulo. |
| `obtenerResumenCaja`, `listarMovimientosCaja` | `obtenerFlujoCaja(fechaInicio: DateTime!, fechaFin: DateTime!)` → `ResumenFlujoCaja {totalIngresosBs totalEgresosBs saldoNetoBs movimientos}` | NO EXISTE (alternativa real) | Ver `ObtenerFlujoCaja`. |
| `GET /api/v1/recibos/{id}/pdf` | **No existe.** No hay ruta REST de recibos. El PDF se obtiene con el campo GraphQL `Recibo.pdfUrlS3`, que devuelve una **URL prefirmada** de almacenamiento S3 (ver `docs/s3-receipts.md`). Solo la resuelven ADMIN y CAJERO (autenticados); puede ser `null` si aún no hay PDF | ruta distinta | Pedir `pdfUrlS3` en `Recibo` y abrir esa URL con `window.open` (no requiere `Authorization`; la firma va en la URL y caduca). |
| `urlDescargaPdf` / `urlDescargaExcel` del reporte | `urlDescargaPdf: String!` = `/api/v1/reports/export/<uuid>/pdf`; `urlDescargaExcel` = `/api/v1/reports/export/<uuid>/xlsx` (rutas **relativas**) | semántica | Anteponer la base (`http://localhost:8080`). `GET` exige `Authorization: Bearer` (o `X-Dev-User` en local) con rol **ADMIN**; el reporte pertenece al usuario que lo generó (otro usuario → 404) y los formatos válidos son solo `xlsx` y `pdf` (otro → 400). Una descarga por `<a href>` no envía cabeceras: descargar con `HttpClient` (`responseType: 'blob'`). |

## Reportes

| Uso en frontend | Contrato real | Estado | Qué cambia el frontend |
| --- | --- | --- | --- |
| `generarReporteInteligente(pregunta)` | Eliminada; usar `ejecutarAgenteErp(instruccion)` y `reporteGenerado` | INCOMPATIBLE | Migrar a la mutación del agente; descargar las URLs nuevas con autenticación ADMIN del propietario. |
| `TipoGrafico` `BARRAS/LINEAS/TORTA` | `tipoGraficoSugerido: String!`; sugerencia por forma de resultados: `BARRAS`, `LINEAS`, `TORTA` o `TABLA` | tipo distinto | No tratarlo como enum cerrado: valor por defecto `TABLA`. |
| `consultaSqlEjecutada?`, `urlDescarga*?` opcionales | no nulos (`String!`) | OK | — |
| `filasJson` | `String!` con un arreglo JSON de filas | OK | `JSON.parse`. |
| Pregunta "pacientes con cuotas pendientes" | Las vistas de reportes no contienen datos de pacientes | semántica distinta | Para pacientes con cuotas, usar `listarOrdenesCobro`/`listarCuotasPendientesPorPaciente`. Sin proveedor, `dev`/`local` usan un respaldo determinístico etiquetado; en producción falla explícitamente. El respaldo rechaza filtros que no puede representar en vez de descartarlos. |

## Edición y baja lógica (S9)

Operaciones nuevas, todas aditivas. Un `actualizar*` reemplaza **todos** los campos del `Crear*Input` equivalente (un campo opcional omitido se borra); `eliminar*` es **baja lógica**, nunca un `DELETE`.

| Operación | Roles | Reglas y errores (`BAD_REQUEST` salvo que se indique; ante error no cambia nada: ni fila, ni auditoría, ni outbox) |
| --- | --- | --- |
| `actualizarPaciente` | ADMIN, RECEPCIONISTA | `ciNit` único excluyendo al propio paciente; mismas validaciones que `crearPaciente`. Publica `catalogo.paciente.actualizado`. |
| `eliminarPaciente` | ADMIN, RECEPCIONISTA (igual que `crearPaciente`) | Rechaza si tiene órdenes de cobro `PENDIENTE` o `PARCIAL`. |
| `actualizarOdontologo` | ADMIN | `ci` y `matriculaProfesional` únicos excluyendo al propio; `especialidadesIds` **reemplaza** el conjunto (id inexistente → error). Publica `catalogo.odontologo.actualizado`. |
| `eliminarOdontologo` | ADMIN | `activo = false`. |
| `actualizarEspecialidad` | ADMIN | `codigo` único excluyendo a la propia. No publica evento. |
| `eliminarEspecialidad` | ADMIN | Rechaza si hay servicios activos u odontólogos activos que la usen. |
| `actualizarConsultorio` | ADMIN | `codigo` único excluyendo al propio; `estado` opcional (omitido = sin cambio), valores `DISPONIBLE`, `MANTENIMIENTO`, `INACTIVO`. Publica `catalogo.consultorio.actualizado`. |
| `eliminarConsultorio` | ADMIN | No existe columna `activo`: la baja lógica es `estado = INACTIVO`. |
| `actualizarServicioOdontologico` | ADMIN | `codigo` único excluyendo al propio; cambiar `precioBaseBs` **no** modifica `orden_cobro_items` ya creados (conservan su `precioUnitarioBs`). No publica evento. |
| `eliminarServicioOdontologico` | ADMIN | `activo = false`. |
| `actualizarUsuario` | ADMIN | `rolCodigo` desconocido y `email` duplicado (excluyendo al propio) → error. Nadie puede desactivarse a sí mismo ni quitarse su propio rol `ADMIN`; siempre debe quedar al menos un `ADMIN` activo (también con solicitudes concurrentes: bloqueo de filas). No publica evento. |

- Todo `eliminar*` devuelve `true`, es **idempotente** (repetirlo sobre algo ya inactivo devuelve `true` sin nueva auditoría ni evento) y un id desconocido da `NOT_FOUND`.
- Auditoría de cada éxito: `PACIENTE_ACTUALIZADO`/`PACIENTE_DESACTIVADO`, `ODONTOLOGO_*`, `ESPECIALIDAD_ACTUALIZADA`/`ESPECIALIDAD_DESACTIVADA`, `CONSULTORIO_ACTUALIZADO`/`CONSULTORIO_DESACTIVADO`, `SERVICIO_ODONTOLOGICO_ACTUALIZADO`/`SERVICIO_ODONTOLOGICO_DESACTIVADO`, `USUARIO_ACTUALIZADO`/`USUARIO_DESACTIVADO`.
- **Listados sin cambios:** `listarPacientes`, `listarEspecialidades` y `listarServiciosOdontologicos` siguen devolviendo solo activos; `listarOdontologos` solo activos salvo `soloActivos: false`; `listarConsultorios` devuelve todos (los dados de baja salen con `estado: "INACTIVO"`); `obtenerPacientePorId` resuelve también pacientes inactivos (`activo: false`). No hay operación de reactivación (excepto `actualizarUsuario` con `activo: true`).
- Eventos nuevos y sus payloads: ver [catalog-outbox.md](catalog-outbox.md).

**Qué debe cambiar todavía el frontend** (sus 11 documentos siguen marcados INVALID en la evidencia de abajo, y es lo esperado):

1. Declarar `$id: UUID!` en lugar de `ID!` (los `eliminar*` solo necesitan eso para ser válidos).
2. Usar los inputs reales: `ActualizarPacienteInput`, `ActualizarOdontologoInput`, `ActualizarEspecialidadInput`, `ActualizarConsultorioInput`, `ActualizarServicioInput` y `ActualizarUsuarioInput` (campos en los ejemplos del Apéndice B, p. ej. `ci`, `porcentajeComision`, `especialidadesIds`, `codigo`, `rolCodigo`).
3. Pedir los campos de respuesta reales (`matriculaProfesional`, `especialidades { nombre }`, `precioBaseBs`, `rol { codigo }`; `Consultorio` expone `estado`, no `activo`; `Paciente` ahora expone `fechaNacimiento: Date` y `genero: String`, ambos nullable).

## Evidencia por operación (frontend vs esquema servido)

El detalle de errores (por operación) lo imprime `FrontendExamplesValidationTest.frontendOperationsMatchTheValidityStatedInTheDoc`. Resumen por tipo de error del validador: `FieldUndefined` (campo inexistente), `UnknownType` (`ID`, `PacienteInput`, `OdontologoInput`, …), `MissingFieldArgument` (`codigo`, `nombre`, `ciNitCliente`, `razonSocialCliente`), `UnknownArgument` (`input` en `crearEspecialidad`), `SubselectionRequired` (`rol`).

## Apéndice: ejemplos válidos del contrato real

Fuente: `src/test/resources/contract/real-examples.graphql`. Los UUID son marcadores: usar los de la carga de datos sintéticos o de una respuesta previa. Cada bloque está validado (documento y variables) contra el esquema servido.

### ListarPacientes

```graphql
query ListarPacientes($busqueda: String) {
  listarPacientes(busqueda: $busqueda) { id ciNit nombres apellidos fechaNacimiento genero telefonoWhatsapp email direccion }
}
```

Variables:

```json
{"busqueda": "Rojas"}
```

### ObtenerPacientePorId

```graphql
query ObtenerPacientePorId($id: UUID!) {
  obtenerPacientePorId(id: $id) { id ciNit nombres apellidos fechaNacimiento genero telefonoWhatsapp email direccion activo }
}
```

Variables:

```json
{"id": "33333333-3333-4333-8333-333333333333"}
```

### ListarOdontologos

```graphql
query ListarOdontologos($soloActivos: Boolean) {
  listarOdontologos(soloActivos: $soloActivos) {
    id ci matriculaProfesional nombres apellidos telefono email porcentajeComision especialidades { id codigo nombre }
  }
}
```

Variables:

```json
{"soloActivos": true}
```

### ListarEspecialidades

```graphql
query ListarEspecialidades {
  listarEspecialidades { id codigo nombre descripcion }
}
```

### ListarConsultorios

```graphql
query ListarConsultorios {
  listarConsultorios { id codigo nombre ubicacion estado }
}
```

### ListarServiciosOdontologicos

```graphql
query ListarServiciosOdontologicos($especialidadId: UUID) {
  listarServiciosOdontologicos(especialidadId: $especialidadId) {
    id codigo nombre descripcion precioBaseBs especialidad { id nombre }
  }
}
```

Variables:

```json
{"especialidadId": null}
```

### ObtenerConfiguracionEmpresa

```graphql
query ObtenerConfiguracionEmpresa {
  obtenerConfiguracionEmpresa { id nombreComercial razonSocial nit direccion telefonoContacto emailContacto monedaSimbolo }
}
```

### ListarUsuarios

```graphql
query ListarUsuarios {
  listarUsuarios { id username email nombreCompleto activo rol { codigo nombre } }
}
```

### ListarAuditLogs

```graphql
query ListarAuditLogs($limite: Int) {
  listarAuditLogs(limite: $limite) { id accion entidadAfectada entidadId detalleJson createdAt }
}
```

Variables:

```json
{"limite": 50}
```

### ListarOrdenesCobro

```graphql
query ListarOrdenesCobro($estado: EstadoOrdenCobro, $pacienteId: UUID) {
  listarOrdenesCobro(estado: $estado, pacienteId: $pacienteId) {
    id modalidadPago montoSubtotalBs descuentoBs montoTotalBs saldoPendienteBs cantidadCuotas estado createdAt
    paciente { id ciNit nombres apellidos }
    cuotas { id numeroCuota montoCuotaBs fechaVencimiento estado fechaPago }
  }
}
```

Variables:

```json
{"estado": "PENDIENTE", "pacienteId": null}
```

### ObtenerOrdenCobroDetalle

```graphql
query ObtenerOrdenCobroDetalle($id: UUID!) {
  obtenerOrdenCobroDetalle(id: $id) {
    id modalidadPago montoTotalBs saldoPendienteBs estado createdAt
    paciente { id ciNit nombres apellidos }
    items { id piezaDental cantidad precioUnitarioBs subtotalBs servicio { id codigo nombre } }
    cuotas { id numeroCuota montoCuotaBs fechaVencimiento estado fechaPago }
  }
}
```

Variables:

```json
{"id": "11111111-1111-4111-8111-111111111111"}
```

### ObtenerFlujoCaja

```graphql
query ObtenerFlujoCaja($fechaInicio: DateTime!, $fechaFin: DateTime!) {
  obtenerFlujoCaja(fechaInicio: $fechaInicio, fechaFin: $fechaFin) {
    totalIngresosBs totalEgresosBs saldoNetoBs
    movimientos {
      id tipoMovimiento categoria metodoPago montoBs descripcion fechaMovimiento
      recibo { id numeroRecibo }
    }
  }
}
```

Variables:

```json
{"fechaInicio": "2026-10-01T00:00:00-04:00", "fechaFin": "2026-10-31T23:59:59-04:00"}
```

### EjecutarAgenteErp

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

Variables:

```json
{"instruccion": "Genera un reporte de ingresos por método de pago"}
```

### CrearPaciente

```graphql
mutation CrearPaciente($input: CrearPacienteInput!) {
  crearPaciente(input: $input) { id ciNit nombres apellidos fechaNacimiento genero telefonoWhatsapp email direccion }
}
```

Variables:

```json
{"input": {"ciNit": "9876543", "nombres": "Lucia", "apellidos": "Rojas Mamani", "fechaNacimiento": "1990-05-17", "genero": "F", "telefonoWhatsapp": "+59171234567", "email": "lucia@example.bo", "direccion": "Av. Banzer 100"}}
```

### CrearEspecialidad

```graphql
mutation CrearEspecialidad($codigo: String!, $nombre: String!, $descripcion: String) {
  crearEspecialidad(codigo: $codigo, nombre: $nombre, descripcion: $descripcion) { id codigo nombre descripcion }
}
```

Variables:

```json
{"codigo": "ENDO", "nombre": "Endodoncia", "descripcion": "Tratamiento de conductos"}
```

### CrearOdontologo

```graphql
mutation CrearOdontologo($input: CrearOdontologoInput!) {
  crearOdontologo(input: $input) { id ci matriculaProfesional nombres apellidos especialidades { id nombre } }
}
```

Variables:

```json
{"input": {"ci": "5551234", "matriculaProfesional": "MP-1234", "nombres": "Carlos", "apellidos": "Vaca Justiniano", "telefono": "+59170011223", "email": null, "porcentajeComision": 30.00, "especialidadesIds": ["22222222-2222-4222-8222-222222222222"]}}
```

### CrearConsultorio

```graphql
mutation CrearConsultorio($input: CrearConsultorioInput!) {
  crearConsultorio(input: $input) { id codigo nombre ubicacion estado }
}
```

Variables:

```json
{"input": {"codigo": "CONS-05", "nombre": "Consultorio 5", "ubicacion": "Piso 2"}}
```

### CrearServicioOdontologico

```graphql
mutation CrearServicioOdontologico($input: CrearServicioInput!) {
  crearServicioOdontologico(input: $input) { id codigo nombre descripcion precioBaseBs especialidad { id nombre } }
}
```

Variables:

```json
{"input": {"especialidadId": null, "codigo": "LIMP-01", "nombre": "Limpieza dental", "descripcion": "Profilaxis", "precioBaseBs": 150.50}}
```

### ActualizarConfiguracionEmpresa

```graphql
mutation ActualizarConfiguracionEmpresa($input: EmpresaConfigInput!) {
  actualizarConfiguracionEmpresa(input: $input) { id nombreComercial razonSocial nit direccion telefonoContacto emailContacto }
}
```

Variables:

```json
{"input": {"nombreComercial": "Sonrisa Dental", "razonSocial": "Sonrisa S.R.L.", "nit": "1023456029", "direccion": "Av. San Martin 155, Santa Cruz de la Sierra", "telefonoContacto": "+591 3 3412233", "emailContacto": "info@sonrisa.bo"}}
```

### CrearUsuario

```graphql
mutation CrearUsuario($input: CrearUsuarioInput!) {
  crearUsuario(input: $input) { id username email nombreCompleto activo rol { codigo } }
}
```

Variables:

```json
{"input": {"rolCodigo": "CAJERO", "username": "cajero2", "email": "cajero2@sonrisa.bo", "nombreCompleto": "Cajero Dos", "password": "Secreta2026!"}}
```

### CrearOrdenCobroManual

```graphql
mutation CrearOrdenCobroManual($input: CrearOrdenCobroInput!) {
  crearOrdenCobroManual(input: $input) { id estado montoSubtotalBs descuentoBs montoTotalBs saldoPendienteBs items { servicio { id nombre } cantidad subtotalBs } }
}
```

Variables:

```json
{"input": {"pacienteId": "33333333-3333-4333-8333-333333333333", "odontologoId": null, "items": [{"servicioId": "44444444-4444-4444-8444-444444444444", "piezaDental": "18", "cantidad": 1}], "descuentoBs": 0, "observaciones": "Orden manual"}}
```

### ConfigurarPlanDePagos

```graphql
mutation ConfigurarPlanDePagos($input: ConfigurarPlanPagosInput!) {
  configurarPlanDePagos(input: $input) {
    id modalidadPago estado montoTotalBs cantidadCuotas
    cuotas { id numeroCuota montoCuotaBs fechaVencimiento estado }
  }
}
```

Variables:

```json
{"input": {"ordenCobroId": "11111111-1111-4111-8111-111111111111", "modalidadPago": "CREDITO", "descuentoBs": 10.00, "cantidadCuotas": 3, "fechaPrimeraCuota": "2026-11-15", "intervaloDias": 30}}
```

### CobrarCuotaEnEfectivo

```graphql
mutation CobrarCuotaEnEfectivo($input: CobrarEfectivoInput!) {
  cobrarCuotaEnEfectivo(input: $input) { id numeroRecibo ciNitCliente razonSocialCliente concepto montoTotalBs pdfUrlS3 fechaEmision }
}
```

Variables:

```json
{"input": {"cuotaId": "55555555-5555-4555-8555-555555555555", "usuarioCajeroId": "66666666-6666-4666-8666-666666666666", "ciNitCliente": "9876543", "razonSocialCliente": "Rojas Mamani"}}
```

### GenerarQrPagoFacil

```graphql
mutation GenerarQrPagoFacil($cuotaId: UUID!, $ciNitCliente: String!, $razonSocialCliente: String!) {
  generarQrPagoFacil(cuotaId: $cuotaId, ciNitCliente: $ciNitCliente, razonSocialCliente: $razonSocialCliente) {
    id cuotaId pagofacilTransactionId qrImagenBase64 montoBs estado fechaExpiracion
  }
}
```

Variables:

```json
{"cuotaId": "55555555-5555-4555-8555-555555555555", "ciNitCliente": "9876543", "razonSocialCliente": "Rojas Mamani"}
```

### SimularConfirmacionQrDev

```graphql
mutation SimularConfirmacionQrDev($pagofacilTransactionId: String!) {
  simularConfirmacionQrDev(pagofacilTransactionId: $pagofacilTransactionId) {
    id numeroRecibo montoTotalBs pdfUrlS3 fechaEmision
  }
}
```

Variables:

```json
{"pagofacilTransactionId": "PF-MOCK-0001"}
```

### RegistrarEgresoCaja

```graphql
mutation RegistrarEgresoCaja($input: RegistrarEgresoInput!) {
  registrarEgresoCaja(input: $input) { id tipoMovimiento categoria metodoPago montoBs descripcion fechaMovimiento }
}
```

Variables:

```json
{"input": {"usuarioCajeroId": "66666666-6666-4666-8666-666666666666", "categoria": "INSUMOS", "metodoPago": "EFECTIVO", "montoBs": 85.50, "descripcion": "Compra de guantes"}}
```

### ListarCuotasPendientesPorPaciente

```graphql
query ListarCuotasPendientesPorPaciente($pacienteId: UUID!) {
  listarCuotasPendientesPorPaciente(pacienteId: $pacienteId) { id numeroCuota montoCuotaBs fechaVencimiento estado }
}
```

Variables:

```json
{"pacienteId": "33333333-3333-4333-8333-333333333333"}
```

## Apéndice B: ejemplos de edición y baja lógica (S9)

Ejemplos válidos de las 11 operaciones nuevas. Los ids son marcadores: use los reales de `listar*`.

### ActualizarPaciente

```graphql
mutation ActualizarPaciente($id: UUID!, $input: ActualizarPacienteInput!) {
  actualizarPaciente(id: $id, input: $input) { id ciNit nombres apellidos fechaNacimiento genero telefonoWhatsapp email direccion activo }
}
```

Variables:

```json
{"id": "33333333-3333-4333-8333-333333333333", "input": {"ciNit": "9876543", "nombres": "Ana María", "apellidos": "Rojas Mamani", "fechaNacimiento": "1990-01-31", "genero": "FEMENINO", "telefonoWhatsapp": "+59171111111", "email": null, "direccion": "Calle 5 #10"}}
```

### EliminarPaciente

```graphql
mutation EliminarPaciente($id: UUID!) {
  eliminarPaciente(id: $id)
}
```

Variables:

```json
{"id": "33333333-3333-4333-8333-333333333333"}
```

### ActualizarOdontologo

```graphql
mutation ActualizarOdontologo($id: UUID!, $input: ActualizarOdontologoInput!) {
  actualizarOdontologo(id: $id, input: $input) {
    id ci matriculaProfesional nombres apellidos telefono email porcentajeComision activo especialidades { id nombre }
  }
}
```

Variables:

```json
{"id": "77777777-7777-4777-8777-777777777777", "input": {"ci": "5551234", "matriculaProfesional": "MP-1234", "nombres": "Carlos", "apellidos": "Vaca Justiniano", "telefono": "+59170011223", "email": null, "porcentajeComision": 32.50, "especialidadesIds": ["22222222-2222-4222-8222-222222222222"]}}
```

### EliminarOdontologo

```graphql
mutation EliminarOdontologo($id: UUID!) {
  eliminarOdontologo(id: $id)
}
```

Variables:

```json
{"id": "77777777-7777-4777-8777-777777777777"}
```

### ActualizarEspecialidad

```graphql
mutation ActualizarEspecialidad($id: UUID!, $input: ActualizarEspecialidadInput!) {
  actualizarEspecialidad(id: $id, input: $input) { id codigo nombre descripcion activo }
}
```

Variables:

```json
{"id": "22222222-2222-4222-8222-222222222222", "input": {"codigo": "PERIO", "nombre": "Periodoncia", "descripcion": "Salud de las encías"}}
```

### EliminarEspecialidad

```graphql
mutation EliminarEspecialidad($id: UUID!) {
  eliminarEspecialidad(id: $id)
}
```

Variables:

```json
{"id": "22222222-2222-4222-8222-222222222222"}
```

### ActualizarConsultorio

```graphql
mutation ActualizarConsultorio($id: UUID!, $input: ActualizarConsultorioInput!) {
  actualizarConsultorio(id: $id, input: $input) { id codigo nombre ubicacion estado }
}
```

Variables:

```json
{"id": "88888888-8888-4888-8888-888888888888", "input": {"codigo": "CONS-05", "nombre": "Consultorio 5", "ubicacion": "Piso 2", "estado": "MANTENIMIENTO"}}
```

### EliminarConsultorio

```graphql
mutation EliminarConsultorio($id: UUID!) {
  eliminarConsultorio(id: $id)
}
```

Variables:

```json
{"id": "88888888-8888-4888-8888-888888888888"}
```

### ActualizarServicioOdontologico

```graphql
mutation ActualizarServicioOdontologico($id: UUID!, $input: ActualizarServicioInput!) {
  actualizarServicioOdontologico(id: $id, input: $input) {
    id codigo nombre descripcion precioBaseBs activo especialidad { id nombre }
  }
}
```

Variables:

```json
{"id": "44444444-4444-4444-8444-444444444444", "input": {"especialidadId": null, "codigo": "LIMP-01", "nombre": "Limpieza dental", "descripcion": "Profilaxis", "precioBaseBs": 175.00}}
```

### EliminarServicioOdontologico

```graphql
mutation EliminarServicioOdontologico($id: UUID!) {
  eliminarServicioOdontologico(id: $id)
}
```

Variables:

```json
{"id": "44444444-4444-4444-8444-444444444444"}
```

### ActualizarUsuario

```graphql
mutation ActualizarUsuario($id: UUID!, $input: ActualizarUsuarioInput!) {
  actualizarUsuario(id: $id, input: $input) { id username email nombreCompleto activo rol { codigo } }
}
```

Variables:

```json
{"id": "66666666-6666-4666-8666-666666666666", "input": {"rolCodigo": "RECEPCIONISTA", "activo": true, "nombreCompleto": "Cajero Dos", "email": null}}
```
