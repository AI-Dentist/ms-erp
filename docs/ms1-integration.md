# Integración para MS2/MS3

Guía del contrato actual de MS1 para los equipos de Clínica y Agenda/Logística.
Todos los ejemplos son ficticios; los UUID no identifican registros existentes.
Las mutaciones se muestran como documentación: no ejecutarlas contra entornos compartidos.

## Encontrar el contrato

- [Conectar y autenticar](#conectar-y-autenticar)
- [Pacientes: leer y escribir](#pacientes-leer-y-escribir)
- [Otros catálogos, identidad y finanzas](#otros-catálogos-identidad-y-finanzas)
- [RabbitMQ: sincronización y consulta finalizada](#rabbitmq-sincronización-y-consulta-finalizada)
- [MCP, REST e idempotencia](#mcp-rest-e-idempotencia)
- SDL publicado: [schema.graphqls](graphql/schema.graphqls) e [introspección](graphql/introspection.json).
  Fuente de ejecución: [SDL de la aplicación](../src/main/resources/graphql/schema.graphqls).
- Más detalle: [demografía](patient-demographic-readback.md), [outbox](catalog-outbox.md),
  [Keycloak local](keycloak-local.md), [datos locales](local-integration-data.md),
  [contrato comparado](frontend-contract-diff.md) y [recibos S3](s3-receipts.md).

## Responsabilidades y referencias

| Servicio dueño | Datos y operaciones |
|---|---|
| MS1 ERP | Demografía/CI/NIT del paciente; odontólogos y especialidades; consultorios físicos; servicios/aranceles en Bs; configuración, usuarios/roles; órdenes, cuotas, caja, QR y recibos |
| MS2 Clínica | Historia clínica, alergias/hábitos, odontograma y atención; emite `clinica.consulta.finalizada` |
| MS3 Agenda/Logística | Horarios, citas e inventario; consume referencias del catálogo |

Compartir los **UUID de MS1**, no generar otro identificador para la misma referencia.
MS2/MS3 pueden mantener proyecciones propias mediante eventos; **no leer ni escribir
la base de datos de MS1 directamente**. Usar GraphQL para consultar el estado vigente.
Ser un microservicio no concede permisos: cada consumidor HTTP/MCP necesita una
identidad autorizada y los roles adecuados; no asumir acceso anónimo ni un rol automático de servicio.

## Conectar y autenticar

- URI canónica del backend: `POST http://localhost:8081/graphql`;
  perfil `local`: `http://localhost:8080/graphql`. `ERP_PORT` cambia el puerto.
- No existe REST de pacientes (`/api/v1/pacientes` no es el contrato).
- Enviar `Content-Type: application/json`, `Authorization: Bearer <token>` y un cuerpo
  `{"query":"<documento GraphQL>","operationName":"<nombre>","variables":{}}`.
  Sustituir `variables` por el JSON asociado al ejemplo.
- MS1 valida JWT de Keycloak (firma, issuer y expiración); los roles vienen de
  `realm_access.roles`. `KEYCLOAK_ISSUER_URI` configura el issuer, **no una API de login de MS1**.
  Obtener el token mediante el flujo de identidad acordado con Keycloak.
- Sin token válido: 401. GraphQL puede responder HTTP 200 con `errors`, incluido
  `FORBIDDEN`: comprobar siempre `errors`, no solo el código HTTP.
- Solo desarrollo: `local` admite `X-Dev-User` de un usuario activo y conserva su rol;
  Bearer tiene prioridad. `dev` omite Keycloak y da todos los roles al anónimo:
  no usarlo para probar autorización ni exponerlo en producción. No combinar perfiles de desarrollo con producción.
- Estas son rutas **del backend**. Un Gateway debe configurar explícitamente el
  enrutamiento/reescritura a `/graphql`, `/mcp` y las excepciones REST pertinentes,
  conservando autenticación y cabeceras. No se presupone que el Gateway actual ya lo haga.

## Pacientes: leer y escribir

| Operación exacta | Roles admitidos | Resultado |
|---|---|---|
| `listarPacientes(busqueda: String)` | ADMIN, RECEPCIONISTA, CAJERO, ODONTOLOGO | Solo activos; búsqueda opcional |
| `obtenerPacientePorId(id: UUID!)` | Los mismos cuatro | Puede devolver inactivo; `null` si no existe |
| `crearPaciente(input: CrearPacienteInput!)` | ADMIN, RECEPCIONISTA | Paciente persistido con UUID |
| `actualizarPaciente(id: UUID!, input: ActualizarPacienteInput!)` | ADMIN, RECEPCIONISTA | Reemplaza campos, no PATCH |
| `eliminarPaciente(id: UUID!)` | ADMIN, RECEPCIONISTA | Baja lógica; devuelve `true`, nunca borra la fila |

Campos completos de `Paciente`: `id: UUID!`, `ciNit: String!`, `nombres: String!`,
`apellidos: String!`, `fechaNacimiento: Date`, `genero: String`,
`telefonoWhatsapp: String!`, `email: String`, `direccion: String`, `activo: Boolean!`.
`Date` usa `yyyy-MM-dd`; género es texto libre, no enum. Los cuatro campos sin `!`
son nullable: no inventar valores cuando falten.

### Listar y obtener el perfil completo

```graphql
query ListarPacientesMs1($busqueda: String) {
  listarPacientes(busqueda: $busqueda) { id nombres apellidos activo }
}
```

Variables:

```json
{"busqueda":"SYN-MS1-001"}
```

```graphql
query DetallePacienteMs1($id: UUID!) {
  obtenerPacientePorId(id: $id) {
    id ciNit nombres apellidos fechaNacimiento genero telefonoWhatsapp email direccion activo
  }
}
```

Variables:

```json
{"id":"33333333-3333-4333-8333-333333333333"}
```

### Crear, reemplazar y dar de baja

```graphql
mutation CrearPacienteMs1($input: CrearPacienteInput!) {
  crearPaciente(input: $input) { id nombres fechaNacimiento genero activo }
}
```

Variables:

```json
{"input":{"ciNit":"SYN-MS1-001","nombres":"Persona","apellidos":"Sintética","telefonoWhatsapp":"+59170000000","fechaNacimiento":"1992-02-29","genero":"Autodescrito","email":null,"direccion":null}}
```

Usar el UUID devuelto, no el ilustrativo, en las operaciones siguientes.

```graphql
mutation ActualizarPacienteMs1($id: UUID!, $input: ActualizarPacienteInput!) {
  actualizarPaciente(id: $id, input: $input) { id nombres fechaNacimiento genero email direccion }
}
```

Variables:

```json
{"id":"33333333-3333-4333-8333-333333333333","input":{"ciNit":"SYN-MS1-001","nombres":"Persona editada","apellidos":"Sintética","telefonoWhatsapp":"+59170000000","fechaNacimiento":"1992-02-29","genero":"Autodescrito","email":null,"direccion":null}}
```

**Leer y reenviar para conservar datos:** omitir `fechaNacimiento`, `genero`, `email`
o `direccion` al actualizar los deja en `null`, igual que enviarlos explícitamente
como `null`. No enviar `id` ni `activo` dentro del input.

```graphql
mutation EliminarPacienteMs1($id: UUID!) {
  eliminarPaciente(id: $id)
}
```

Variables:

```json
{"id":"33333333-3333-4333-8333-333333333333"}
```

La baja puede rechazarse por órdenes abiertas. Repetirla sobre un paciente ya
inactivo no produce otra baja ni otro evento. El detalle conserva su UUID y muestra
`activo: false`; el listado deja de incluirlo.

## Otros catálogos, identidad y finanzas

En esta tabla, **TODOS** significa ADMIN/CAJERO/RECEPCIONISTA/ODONTOLOGO;
**PLAN** significa ADMIN/CAJERO/RECEPCIONISTA; **CAJA** significa ADMIN/CAJERO.
Son roles de la identidad solicitante, no permisos concedidos por consumir eventos.

| Grupo | Nombres exactos | Roles |
|---|---|---|
| Lectura de catálogo | `listarOdontologos`, `listarEspecialidades`, `listarConsultorios`, `listarServiciosOdontologicos` | TODOS |
| Odontólogos | `crearOdontologo`, `actualizarOdontologo`, `eliminarOdontologo` | ADMIN |
| Especialidades | `crearEspecialidad`, `actualizarEspecialidad`, `eliminarEspecialidad` | ADMIN |
| Consultorios | `crearConsultorio`, `actualizarConsultorio`, `eliminarConsultorio` | ADMIN |
| Servicios/aranceles | `crearServicioOdontologico`, `actualizarServicioOdontologico`, `eliminarServicioOdontologico` | ADMIN |
| Configuración y roles | `obtenerConfiguracionEmpresa`, `listarRoles` | TODOS |
| Administración | `listarUsuarios`, `listarAuditLogs`, `crearUsuario`, `actualizarUsuario`, `actualizarConfiguracionEmpresa` | ADMIN |
| Lectura financiera | `listarOrdenesCobro`, `obtenerOrdenCobroDetalle`, `listarCuotasPendientesPorPaciente`, `obtenerFlujoCaja` | PLAN |
| Órdenes y planes | `crearOrdenCobroManual`, `configurarPlanDePagos` | PLAN |
| Cobros y egresos | `cobrarCuotaEnEfectivo`, `generarQrPagoFacil`, `registrarEgresoCaja` | CAJA |
| Simulación local | `simularConfirmacionQrDev` | CAJA + propiedad habilitada + perfil dev/local, nunca prod/production |
| Agente | `ejecutarAgenteErp` | PLAN; cada herramienta restringe además sus roles |
| Historial / estado MCP | `listarHistorialAgente` / `obtenerEstadoServidorMcp` | TODOS / ADMIN |

Especialidades y servicios listan solo activos. Odontólogos: activos por defecto;
`soloActivos: false` incluye inactivos. Consultorios lista todos: baja = `estado: INACTIVO`,
no tienen `activo`. Los inputs completos y sus excepciones de reemplazo están en el SDL:
por ejemplo, `actualizarUsuario` conserva nombre/email omitidos y no cambia username/password.

```graphql
query CatalogosMs1($soloActivos: Boolean!) {
  listarOdontologos(soloActivos: $soloActivos) {
    id nombres apellidos activo especialidades { id nombre }
  }
  listarEspecialidades { id codigo nombre activo }
  listarConsultorios { id codigo nombre estado }
  listarServiciosOdontologicos { id codigo nombre precioBaseBs especialidad { id } }
}
```

Variables:

```json
{"soloActivos":false}
```

```graphql
query FinanzasPacienteMs1($pacienteId: UUID!) {
  listarOrdenesCobro(pacienteId: $pacienteId) {
    id estado montoTotalBs saldoPendienteBs consultaExternaId
  }
  listarCuotasPendientesPorPaciente(pacienteId: $pacienteId) {
    id numeroCuota montoCuotaBs fechaVencimiento estado
  }
}
```

Variables:

```json
{"pacienteId":"33333333-3333-4333-8333-333333333333"}
```

Importes en Bolivianos (`Bs`). El cobro actualiza cuota, saldo, ingreso y recibo en
una transacción. `Recibo.pdfUrlS3` exige CAJA; un fallo posterior de PDF/storage no
justifica repetir el cobro. Historial: ADMIN ve todo, los demás solo lo propio.

## RabbitMQ: sincronización y consulta finalizada

Exchange topic durable `dental.topic.exchange` (`erp.messaging.exchange`). Conexión:
`ERP_RABBIT_HOST`, `ERP_RABBIT_PORT`, `ERP_RABBIT_USER`, `ERP_RABBIT_PASSWORD`.
Cada equipo consumidor debe disponer de su cola/bindings autorizados; no consumir
la cola de otro equipo esperando difusión a ambos.

### Salida MS1: seis eventos planos

El cuerpo JSON contiene **exactamente** estos campos de los records, sin envelope
`payload`, sin `routingKey` en el cuerpo y sin timestamp/version inventados.
UUID se serializa como string; `fechaNacimiento` es fecha ISO o null;
`especialidades` es array de nombres (strings), **no UUID**; `activo` es boolean.

| Routing key | Mapa completo de campos del cuerpo |
|---|---|
| `catalogo.paciente.creado` | `eventId`, `pacienteId`, `ciNit`, `nombreCompleto`, `telefonoWhatsapp`, `fechaNacimiento` |
| `catalogo.paciente.actualizado` | `eventId`, `pacienteId`, `ciNit`, `nombreCompleto`, `telefonoWhatsapp`, `fechaNacimiento`, `activo` |
| `catalogo.odontologo.creado` | `eventId`, `odontologoId`, `nombreCompleto`, `matriculaProfesional`, `especialidades` |
| `catalogo.odontologo.actualizado` | `eventId`, `odontologoId`, `nombreCompleto`, `matriculaProfesional`, `especialidades`, `activo` |
| `catalogo.consultorio.creado` | `eventId`, `consultorioId`, `codigo`, `nombre`, `estado` |
| `catalogo.consultorio.actualizado` | `eventId`, `consultorioId`, `codigo`, `nombre`, `estado` |

Fuentes: [records de eventos](../src/main/java/com/dentalplatform/erp/mastercatalog/domain/event).
El evento de paciente **no incluye `genero`, `email`, `direccion`**, ni nombres/apellidos
separados: releer `obtenerPacientePorId` para el perfil completo. El creado tampoco
incluye `activo`. Baja lógica: `actualizado` con `activo: false` para paciente/odontólogo,
o `estado: "INACTIVO"` para consultorio (otros estados: `DISPONIBLE`, `MANTENIMIENTO`).
No hay eventos de usuarios, servicios ni especialidades.

Agregado + auditoría + `catalog_event_outbox` (V6) se confirman atómicamente.
El dispatcher publica JSON persistente, con `eventId` como message ID y referencia
de confirmación del publicador; solo marca enviado tras ACK sin retorno obligatorio. NACK, timeout o ausencia de ruta
reintentan con backoff y **el mismo `eventId`**. Propiedades `erp.catalog-outbox.*`
y límites: [outbox](catalog-outbox.md).

Entrega **al menos una vez**: deduplicar durablemente por `eventId` junto al efecto
del consumidor y hacer upsert por UUID de entidad. No hay orden global garantizado;
los eventos no permiten ordenar dos actualizaciones por timestamp. Releer MS1 si se
necesita resolver el estado final; no interpretar el orden de llegada como versión.

### Entrada MS2 → MS1: `clinica.consulta.finalizada`

Publicar en el mismo exchange, routing key `clinica.consulta.finalizada`.
MS1 escucha `erp.consulta.finalizada.queue`; configura `erp.messaging.consulta-finalizada-queue`
y `erp.messaging.consulta-finalizada-routing-key`. Fallos rechazados van al DLX
`dental.dlx.exchange` y DLQ `erp.consulta.finalizada.dlq` (propiedades
`erp.messaging.dead-letter-exchange` y `erp.messaging.consulta-finalizada-dlq`), no a requeue infinito.

DTO real: [ConsultaFinalizadaPayload](../src/main/java/com/dentalplatform/erp/financebilling/infrastructure/messaging/ConsultaFinalizadaPayload.java).
Campos planos: `eventId`, `consultaId`, `citaId`, `pacienteId`, `odontologoId`,
`serviciosRealizados`, `observacionesCobro`. Cada servicio contiene `servicioId`,
`piezaDental`, `cantidad`, `precioUnitarioBs`. Ejemplo mínimo ficticio con un servicio:

```json
{"eventId":"11111111-1111-4111-8111-111111111111","consultaId":"22222222-2222-4222-8222-222222222222","citaId":"66666666-6666-4666-8666-666666666666","pacienteId":"33333333-3333-4333-8333-333333333333","odontologoId":"44444444-4444-4444-8444-444444444444","serviciosRealizados":[{"servicioId":"55555555-5555-4555-8555-555555555555","piezaDental":null,"cantidad":1,"precioUnitarioBs":150.00}],"observacionesCobro":null}
```

No envolverlo en `payload` ni sustituir `serviciosRealizados` por `servicios`.
Las referencias deben existir y superar las validaciones de MS1; la lista no puede
estar vacía. El receptor deduplica por **`consultaId`, no por `eventId`**: una consulta
ya procesada no genera otra orden aunque cambie el eventId. Crea orden `PENDIENTE`,
con `consultaExternaId = consultaId`; no cobra automáticamente. `eventId` y `citaId`
no se transfieren al comando financiero como claves de deduplicación.

## MCP, REST e idempotencia

`/mcp` es un servidor MCP real Spring AI, Streamable HTTP/WebMVC, no REST de negocio.
Exige autenticación; las nueve herramientas operan con la identidad del solicitante:

| Grupo | Herramientas exactas | Roles |
|---|---|---|
| Monitoreo | `obtenerResumenFinancieroTool`, `listarPacientesConCuotasVencidasTool` | CAJA |
| Lectura caja | `obtenerFlujoCajaTool` | PLAN |
| CRUD catálogo | `crearServicioOdontologicoTool` | ADMIN |
| CRUD caja / orden | `registrarEgresoCajaTool`, `anularOrdenCobroPendienteTool` | CAJA |
| Plan / descuento | `reprogramarPlanPagosOAnularDescuentoTool` | PLAN |
| Reportes SELECT protegidos | `generarReporteDinamicoSqlTool` | ADMIN |
| Cobranza n8n | `dispararAlertaCobranzaN8nTool` | CAJA |

El agente usa estas mismas herramientas. Modo autónomo: monitoreo y posible aviso de
cobranza, **nunca CRUD autónomo**. Las escrituras pasan por casos de uso transaccionales
y auditoría; guardrails no sustituyen roles. Sin proveedor IA solo dev/local ofrece
respaldo determinístico identificado en cada respuesta; producción falla explícitamente.

Solo dos rutas REST de negocio:

- `GET /api/v1/reports/export/{reporteId}/{format}`, `format = xlsx|pdf`: ADMIN y
  propietario del reporte generado, caché temporal; otro propietario/expirado → 404.
  Usar las URLs devueltas por el reporte, con autenticación.
- `POST /api/v1/webhooks/pagofacil`: **protocolo mock local, no proveedor de producción**.
  Autentica `X-Local-Mock-Secret`, no exige Bearer. Cuerpo: `transactionId`, `amountBs`,
  `confirmedAt`; deben coincidir con la transacción/vigencia almacenadas. Reintentos
  deduplican por transactionId y devuelven el mismo recibo. Proveedor real no implementado;
  detalles y estados en [README](../README.md#qr-protocolo-local-no-pagofácil-real).

`/actuator/health` (y `/actuator/info`) son operación del servicio, no APIs de negocio.

Para POST en `/graphql` o `/api/*` usar **`Idempotency-Key`**, no `X-Idempotency-Key`:
opcional, 1–100 caracteres
tras trim. Reintentar con la **misma clave y exactamente el mismo cuerpo/ruta**;
cambiar espacios del JSON también cambia su huella. El replay exitoso lleva
`Idempotency-Replayed: true`. JWT vincula issuer + subject + autoridades efectivas;
`X-Dev-User` local vincula usuario/rol. Cambio de contenido/identidad/roles → 422;
solicitud aún en curso → 409. El callback PagoFácil está excluido de este filtro.

Respuestas completadas caducan a las 24 h por defecto; claves pendientes **no caducan**.
Errores HTTP/GraphQL liberan la clave según el filtro, pero no prueban ausencia de commit.
Ante timeout/resultado ambiguo, consultar y reconciliar el estado antes de reintentar:
no cambiar de clave para eludir un 409. No hay garantía exactamente una vez tras crash
ni endpoint automático de reconciliación. Más detalle: [README](../README.md#idempotencia-y-resultados-ambiguos).
