# Especificación Técnica Detallada: `dental-erp-ms1` (Backend ERP)

## 1. Estructura de Paquetes (`com.dentalplatform.erp`)

Java 25 con Virtual Threads, Spring Boot 4.0.8 y Spring AI 2.0.1. Backend exclusivo, sin código frontend ni modelos locales: la IA local (embeddings) corre únicamente en el navegador del frontend.

Despliegue objetivo: AWS; recibos privados en almacenamiento compatible con S3 (SeaweedFS local, Amazon S3 en producción). El SDK MinIO es solo un cliente S3, no el servidor local. Detalles: [almacenamiento](docs/s3-receipts.md).

`dev` permite el bypass explícito de seguridad; `local` conserva roles con `X-Dev-User` de usuario activo o JWT (Bearer tiene prioridad), integra el frontend externo y usa Keycloak local como emisor por defecto. Keycloak en `http://localhost:8180/realms/dental-platform` es solo de desarrollo; no combinar estos perfiles con producción. Detalles: [Keycloak local](docs/keycloak-local.md) y [README](README.md).

```text
com.dentalplatform.erp
├── shared
│   ├── domain
│   │   ├── vo/Money.java
│   │   ├── event/DomainEvent.java
│   │   └── exception/BusinessRuleException.java
│   └── infrastructure
│       ├── config/ (RabbitMQConfig, GraphQlScalarConfig, SecurityConfig, VirtualThreadConfig)
│       └── idempotency/ (IdempotencyFilter, IdempotencyRecordJpaEntity)
├── configsecurity
│   ├── domain/ (model: EmpresaConfig, Usuario, Rol, Permiso, AuditLog | repository)
│   ├── application/usecase/
│   ├── infrastructure/persistence/
│   └── presentation/graphql/ConfigSecurityGraphQLController.java
├── mastercatalog
│   ├── domain/ (model: Paciente, Odontologo, Especialidad, Consultorio, ServicioOdontologico | repository)
│   ├── application/usecase/
│   ├── infrastructure/
│   │   ├── persistence/
│   │   └── messaging/RabbitMQCatalogEventPublisher.java
│   └── presentation/graphql/MasterCatalogGraphQLController.java
└── financebilling
    ├── domain/ (model: OrdenCobro, OrdenCobroItem, CuotaPago, TransaccionQr, MovimientoCaja, Recibo | repository)
    │   └── report/SqlReadOnlyGuardrail.java
    ├── application/
    │   ├── usecase/
    │   │   ├── ProcesarConsultaFinalizadaUseCase.java
    │   │   ├── ConfigurarPlanPagosUseCase.java
    │   │   ├── CobrarCuotaEfectivoUseCase.java
    │   │   ├── GenerarQrPagoFacilUseCase.java
    │   │   ├── ConfirmarPagoQrWebhookUseCase.java
    │   │   ├── RegistrarEgresoCajaUseCase.java
    │   │   └── ErpCopilotAgentUseCase.java
    │   ├── agent/ErpMcpToolsService.java
    │   ├── guardrail/PromptInjectionGuardrail.java
    │   └── port/ (PagoFacilGatewayPort, N8nNotificationPort, ReportExporterPort)
    ├── infrastructure/
    │   ├── persistence/
    │   ├── messaging/ConsultaFinalizadaRabbitListener.java
    │   ├── external/ (PagoFacilHttpAdapter, N8nWebhookAdapter, SpringAiSqlGeneratorAdapter)
    │   └── document/ (PdfReceiptExporter.java, ExcelReportExporter.java, PdfReportExporter.java)
    └── presentation/
        ├── graphql/FinanceBillingGraphQLController.java
        └── rest/ (PagoFacilWebhookController.java, ReportExportRestController.java)
```

---

### 1.1 Agente único y servidor MCP

**`ErpCopilotAgentUseCase` es el único punto de entrada de IA** en la capa application de `financebilling`. Recibe instrucciones en lenguaje natural (texto o voz transcrita) o modo autónomo «Monitorea mi negocio». Invoca las herramientas `@Tool` de `ErpMcpToolsService`, también expuestas como servidor MCP real: Spring AI MCP Server sobre Streamable HTTP/WebMVC en `/mcp`, autenticado como el resto de la API. Es una excepción de protocolo, no una API REST de negocio.

| Grupo | Herramientas y contrato |
|---|---|
| Monitoreo y lectura DDD | Métricas financieras, flujo de caja y pacientes con cuotas vencidas mediante repositorios/casos de uso del dominio. |
| CRUD, 100% vía casos de uso DDD | `crearServicioOdontologicoTool`, `registrarEgresoCajaTool`, `reprogramarPlanPagosOAnularDescuentoTool` (actualiza cuotas/planes pendientes), `anularOrdenCobroPendienteTool` (baja lógica de orden pendiente a `ANULADO`). Sin SQL directo, con validaciones de dominio, `@Transactional` y `audit_logs`. |
| Reportes | `generarReporteDinamicoSqlTool` traduce lenguaje natural a SQL PostgreSQL con Spring AI. Solo ejecuta consultas aprobadas por `SqlReadOnlyGuardrail`; no hay flujo generativo independiente del agente/MCP. |
| Cobranza n8n | `dispararAlertaCobranzaN8nTool` envía por webhook recordatorios WhatsApp con datos de cuota o QR PagoFácil al detectar morosidad o por orden del administrador. |

**Autorización antes que confianza en el modelo:** cada herramienta ejecuta con la identidad del solicitante y los mismos roles que la operación GraphQL equivalente. El modo autónomo nunca ejecuta escrituras CRUD. Toda escritura pasa por casos de uso con `@Transactional` y `audit_logs`; el agente nunca ejecuta SQL salvo la herramienta de reportes de solo lectura.

`PromptInjectionGuardrail` se aplica antes de procesar cualquier instrucción: bloquea jailbreak, «ignora las instrucciones anteriores» e intentos de saltar reglas financieras. Es defensa en profundidad, no sustituye la autorización.

`SqlReadOnlyGuardrail` valida AST/regex estricto: únicamente `SELECT`; prohíbe `INSERT`, `UPDATE`, `DELETE`, `DROP`, `ALTER`, `TRUNCATE`, sentencias múltiples con `;` y comentarios maliciosos. Ejecutar mediante `@Transactional(readOnly = true)` en un datasource de reportes dedicado con credenciales exclusivas de lectura, nunca el datasource principal.

**Reporte y descarga:** caché temporal por `reporteId` UUID, columnas y filas JSON, gráfico sugerido `BARRAS|LINEAS|TORTA|TABLA` y URLs `GET /api/v1/reports/export/{reporteId}/{format}` (`format`: `xlsx|pdf`). `ExcelReportExporter.java` usa Apache POI y `PdfReportExporter.java` OpenPDF; ambos generan binarios reales con membrete de la clínica e importes en Bs. `ReportExportRestController.java` descarga el resultado almacenado, sin repetir la consulta.

**Sin clave de proveedor:** OpenAI o Gemini por su URL base compatible con OpenAI. Solo en perfiles `dev` y `local`, usar un planificador determinístico por reglas que permita probar CRUD, monitoreo y reportes/descargas; cada respuesta debe indicar que es un respaldo no IA. En producción sin clave, devolver error explícito de proveedor no configurado, nunca una respuesta inventada. El respaldo no ejecuta modelos locales ni omite autorizaciones o guardrails.

## 2. Esquema de Base de Datos PostgreSQL 18 (`V1__init_erp_schema.sql`)

```sql
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Control de Idempotencia para evitar cobros o registros duplicados en redes inestables
CREATE TABLE idempotency_keys (
    idempotency_key VARCHAR(100) PRIMARY KEY,
    operation_name VARCHAR(100) NOT NULL,
    response_payload JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- =========================================================================
-- CONTEXTO 1: CONFIGURACIÓN, SEGURIDAD Y AUDITORÍA
-- =========================================================================

CREATE TABLE empresa_config (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    nombre_comercial VARCHAR(150) NOT NULL,
    razon_social VARCHAR(150) NOT NULL,
    nit VARCHAR(30) NOT NULL,
    direccion TEXT NOT NULL,
    telefono_contacto VARCHAR(30) NOT NULL,
    email_contacto VARCHAR(100),
    logo_url TEXT,
    moneda_simbolo VARCHAR(10) DEFAULT 'Bs',
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    codigo VARCHAR(50) UNIQUE NOT NULL,
    nombre VARCHAR(100) NOT NULL,
    descripcion TEXT
);

CREATE TABLE permisos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    codigo VARCHAR(80) UNIQUE NOT NULL,
    modulo VARCHAR(50) NOT NULL,
    descripcion TEXT
);

CREATE TABLE rol_permisos (
    rol_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permiso_id UUID NOT NULL REFERENCES permisos(id) ON DELETE CASCADE,
    PRIMARY KEY (rol_id, permiso_id)
);

CREATE TABLE usuarios (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    keycloak_id VARCHAR(100) UNIQUE,
    rol_id UUID NOT NULL REFERENCES roles(id),
    username VARCHAR(80) UNIQUE NOT NULL,
    password_hash VARCHAR(255),
    email VARCHAR(120) UNIQUE NOT NULL,
    nombre_completo VARCHAR(150) NOT NULL,
    activo BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    usuario_id UUID REFERENCES usuarios(id),
    accion VARCHAR(100) NOT NULL,
    entidad_afectada VARCHAR(80) NOT NULL,
    entidad_id UUID,
    detalle_json JSONB,
    ip_origen VARCHAR(45),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- =========================================================================
-- CONTEXTO 2: CATÁLOGOS MAESTROS
-- =========================================================================

CREATE TABLE consultorios (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    codigo VARCHAR(20) UNIQUE NOT NULL,
    nombre VARCHAR(100) NOT NULL,
    ubicacion VARCHAR(150),
    estado VARCHAR(30) DEFAULT 'DISPONIBLE',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE especialidades (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    codigo VARCHAR(30) UNIQUE NOT NULL,
    nombre VARCHAR(100) NOT NULL,
    descripcion TEXT,
    activo BOOLEAN DEFAULT TRUE
);

CREATE TABLE odontologos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    usuario_id UUID UNIQUE REFERENCES usuarios(id) ON DELETE SET NULL,
    ci VARCHAR(25) UNIQUE NOT NULL,
    matricula_profesional VARCHAR(50) UNIQUE NOT NULL,
    nombres VARCHAR(100) NOT NULL,
    apellidos VARCHAR(100) NOT NULL,
    telefono VARCHAR(30) NOT NULL,
    email VARCHAR(120),
    porcentaje_comision NUMERIC(5,2) DEFAULT 0.00,
    activo BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE odontologo_especialidades (
    odontologo_id UUID NOT NULL REFERENCES odontologos(id) ON DELETE CASCADE,
    especialidad_id UUID NOT NULL REFERENCES especialidades(id) ON DELETE CASCADE,
    PRIMARY KEY (odontologo_id, especialidad_id)
);

CREATE TABLE pacientes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ci_nit VARCHAR(30) UNIQUE NOT NULL,
    nombres VARCHAR(100) NOT NULL,
    apellidos VARCHAR(100) NOT NULL,
    fecha_nacimiento DATE,
    genero VARCHAR(20),
    telefono_whatsapp VARCHAR(30) NOT NULL,
    email VARCHAR(120),
    direccion TEXT,
    activo BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE servicios_odontologicos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    especialidad_id UUID REFERENCES especialidades(id),
    codigo VARCHAR(30) UNIQUE NOT NULL,
    nombre VARCHAR(150) NOT NULL,
    descripcion TEXT,
    precio_base_bs NUMERIC(12,2) NOT NULL CHECK (precio_base_bs >= 0),
    activo BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- =========================================================================
-- CONTEXTO 3: FINANZAS, PLANES DE PAGO, PAGOFÁCIL QR Y CAJA
-- =========================================================================

CREATE TABLE ordenes_cobro (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    paciente_id UUID NOT NULL REFERENCES pacientes(id),
    odontologo_id UUID REFERENCES odontologos(id),
    consulta_externa_id UUID UNIQUE,
    modalidad_pago VARCHAR(20) NOT NULL DEFAULT 'CONTADO',
    monto_subtotal_bs NUMERIC(12,2) NOT NULL,
    descuento_bs NUMERIC(12,2) DEFAULT 0.00,
    monto_total_bs NUMERIC(12,2) NOT NULL,
    saldo_pendiente_bs NUMERIC(12,2) NOT NULL,
    cantidad_cuotas INT NOT NULL DEFAULT 1 CHECK (cantidad_cuotas >= 1),
    estado VARCHAR(30) NOT NULL DEFAULT 'PENDIENTE',
    observaciones TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE orden_cobro_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    orden_cobro_id UUID NOT NULL REFERENCES ordenes_cobro(id) ON DELETE CASCADE,
    servicio_id UUID NOT NULL REFERENCES servicios_odontologicos(id),
    pieza_dental VARCHAR(10),
    cantidad INT NOT NULL DEFAULT 1,
    precio_unitario_bs NUMERIC(12,2) NOT NULL,
    subtotal_bs NUMERIC(12,2) NOT NULL
);

CREATE TABLE cuotas_pago (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    orden_cobro_id UUID NOT NULL REFERENCES ordenes_cobro(id) ON DELETE CASCADE,
    numero_cuota INT NOT NULL,
    monto_cuota_bs NUMERIC(12,2) NOT NULL,
    fecha_vencimiento DATE NOT NULL,
    estado VARCHAR(30) NOT NULL DEFAULT 'PENDIENTE',
    fecha_pago TIMESTAMP WITH TIME ZONE,
    UNIQUE (orden_cobro_id, numero_cuota)
);

CREATE TABLE transacciones_qr (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cuota_id UUID NOT NULL REFERENCES cuotas_pago(id),
    pagofacil_transaction_id VARCHAR(100) UNIQUE NOT NULL,
    qr_imagen_base64 TEXT NOT NULL,
    monto_bs NUMERIC(12,2) NOT NULL,
    moneda VARCHAR(10) DEFAULT 'BOB',
    estado VARCHAR(30) NOT NULL DEFAULT 'GENERADO',
    raw_webhook_payload JSONB,
    fecha_creacion TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    fecha_expiracion TIMESTAMP WITH TIME ZONE,
    fecha_confirmacion TIMESTAMP WITH TIME ZONE
);

CREATE TABLE movimientos_caja (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cuota_id UUID UNIQUE REFERENCES cuotas_pago(id),
    usuario_cajero_id UUID NOT NULL REFERENCES usuarios(id),
    tipo_movimiento VARCHAR(20) NOT NULL,
    categoria VARCHAR(50) NOT NULL,
    metodo_pago VARCHAR(30) NOT NULL,
    monto_bs NUMERIC(12,2) NOT NULL CHECK (monto_bs > 0),
    descripcion TEXT NOT NULL,
    fecha_movimiento TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE recibos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    movimiento_caja_id UUID UNIQUE NOT NULL REFERENCES movimientos_caja(id),
    numero_recibo VARCHAR(40) UNIQUE NOT NULL,
    ci_nit_cliente VARCHAR(30) NOT NULL,
    razon_social_cliente VARCHAR(150) NOT NULL,
    concepto TEXT NOT NULL,
    monto_total_bs NUMERIC(12,2) NOT NULL,
    pdf_url_s3 TEXT,
    enviado_whatsapp BOOLEAN DEFAULT FALSE,
    fecha_emision TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_pacientes_ci_nit ON pacientes(ci_nit);
CREATE INDEX idx_ordenes_paciente ON ordenes_cobro(paciente_id);
CREATE INDEX idx_ordenes_estado ON ordenes_cobro(estado);
CREATE INDEX idx_cuotas_vencimiento ON cuotas_pago(fecha_vencimiento, estado);
CREATE INDEX idx_movimientos_fecha_tipo ON movimientos_caja(fecha_movimiento, tipo_movimiento);
```

---

## 3. Contratos de Eventos Asíncronos (RabbitMQ - Exchange `dental.topic.exchange`)

### 3.1 Eventos Publicados por este MS1:
* **Routing Key:** `catalogo.paciente.creado`
```json
{
  "eventId": "uuid",
  "pacienteId": "uuid",
  "ciNit": "8945123 SC",
  "nombreCompleto": "Carlos Andrés Justiniano",
  "telefonoWhatsapp": "+59170012345",
  "fechaNacimiento": "1995-04-12"
}
```

* **Routing Key:** `catalogo.odontologo.creado`
```json
{
  "eventId": "uuid",
  "odontologoId": "uuid",
  "nombreCompleto": "Dra. Valeria Roca",
  "matriculaProfesional": "COL-OD-4512",
  "especialidades": ["Ortodoncia", "Estética Dental"]
}
```

* **Routing Key:** `catalogo.consultorio.creado`
```json
{
  "eventId": "uuid",
  "consultorioId": "uuid",
  "codigo": "CONS-01",
  "nombre": "Consultorio 1 - Planta Baja",
  "estado": "DISPONIBLE"
}
```

### Contrato de eventos: actualizaciones y outbox

Creación, actualización y baja lógica confirman agregado, `audit_logs` y evento en el outbox transaccional. El dispatcher publica con ACK sin retorno obligatorio y reintentos; entrega al menos una vez, sin orden global. Consumidores deduplican por `eventId`. Las claves `catalogo.*.creado` anteriores no cambian.

| Nueva routing key | Estado completo publicado |
|---|---|
| `catalogo.paciente.actualizado` | Mismo payload de creación más `activo`; baja lógica: `activo = false`. |
| `catalogo.odontologo.actualizado` | Mismo payload de creación más `activo`; baja lógica: `activo = false`. |
| `catalogo.consultorio.actualizado` | Mismo payload de creación con `estado`; baja lógica: `INACTIVO`. |

Cada evento tiene un `eventId` nuevo. Rechazos, rollback y bajas repetidas de entidades inactivas no encolan eventos. Usuarios, especialidades y servicios no publican eventos. Detalles y límites: [docs/catalog-outbox.md](docs/catalog-outbox.md).

### 3.2 Eventos Escuchados por este MS1 (Patrón Saga):
* **Queue:** `erp.consulta.finalizada.queue` (vinculada a Routing Key `clinica.consulta.finalizada`)
```json
{
  "eventId": "uuid",
  "consultaId": "uuid",
  "citaId": "uuid",
  "pacienteId": "uuid",
  "odontologoId": "uuid",
  "serviciosRealizados": [
    {
      "servicioId": "uuid",
      "piezaDental": "36",
      "cantidad": 1,
      "precioUnitarioBs": 250.00
    }
  ],
  "observacionesCobro": "Aplicar plan de pagos si solicita crédito"
}
```

---

## 4. Contrato Completo GraphQL (`src/main/resources/graphql/schema.graphqls`)

**Cambio incompatible para el frontend:** se eliminan `generarReporteInteligente`, `ReporteGenerativoResponse` y la ruta antigua `GET /api/v1/reports/export/{format}?reporteId=...`. Los reemplazan `ejecutarAgenteErp`, su `ReporteGenerativoPayload` y `GET /api/v1/reports/export/{reporteId}/{format}` (`xlsx|pdf`). Se conserva el resto del esquema servido, incluidas las 11 operaciones de actualización/baja lógica: seis `actualizar*` y cinco `eliminar*` de usuarios y catálogos. Las bajas no eliminan filas; `eliminar*` es idempotente y devuelve `true`. `actualizarUsuario` no cambia username ni contraseña.

```graphql
scalar UUID
scalar BigDecimal
scalar Date
scalar DateTime

type Query {
    obtenerConfiguracionEmpresa: EmpresaConfig!
    listarUsuarios: [Usuario!]!
    listarRoles: [Rol!]!
    listarAuditLogs(limite: Int = 50): [AuditLog!]!

    listarPacientes(busqueda: String): [Paciente!]!
    obtenerPacientePorId(id: UUID!): Paciente
    listarOdontologos(soloActivos: Boolean = true): [Odontologo!]!
    listarEspecialidades: [Especialidad!]!
    listarConsultorios: [Consultorio!]!
    listarServiciosOdontologicos(especialidadId: UUID): [ServicioOdontologico!]!

    listarOrdenesCobro(estado: EstadoOrdenCobro, pacienteId: UUID): [OrdenCobro!]!
    obtenerOrdenCobroDetalle(id: UUID!): OrdenCobro
    listarCuotasPendientesPorPaciente(pacienteId: UUID!): [CuotaPago!]!
    obtenerFlujoCaja(fechaInicio: DateTime!, fechaFin: DateTime!): ResumenFlujoCaja!
}

type Mutation {
    actualizarConfiguracionEmpresa(input: EmpresaConfigInput!): EmpresaConfig!
    crearUsuario(input: CrearUsuarioInput!): Usuario!
    crearPaciente(input: CrearPacienteInput!): Paciente!
    crearEspecialidad(codigo: String!, nombre: String!, descripcion: String): Especialidad!
    crearOdontologo(input: CrearOdontologoInput!): Odontologo!
    crearConsultorio(input: CrearConsultorioInput!): Consultorio!
    crearServicioOdontologico(input: CrearServicioInput!): ServicioOdontologico!

    # Updates replace every field of the matching Crear*Input. eliminar* is logical deactivation (never a DELETE),
    # idempotent, and returns true. Consultorio has no activo column: its deactivation is estado = INACTIVO.
    actualizarPaciente(id: UUID!, input: ActualizarPacienteInput!): Paciente!
    actualizarOdontologo(id: UUID!, input: ActualizarOdontologoInput!): Odontologo!
    actualizarEspecialidad(id: UUID!, input: ActualizarEspecialidadInput!): Especialidad!
    actualizarConsultorio(id: UUID!, input: ActualizarConsultorioInput!): Consultorio!
    actualizarServicioOdontologico(id: UUID!, input: ActualizarServicioInput!): ServicioOdontologico!
    actualizarUsuario(id: UUID!, input: ActualizarUsuarioInput!): Usuario!
    eliminarPaciente(id: UUID!): Boolean!
    eliminarOdontologo(id: UUID!): Boolean!
    eliminarEspecialidad(id: UUID!): Boolean!
    eliminarConsultorio(id: UUID!): Boolean!
    eliminarServicioOdontologico(id: UUID!): Boolean!

    crearOrdenCobroManual(input: CrearOrdenCobroInput!): OrdenCobro!
    configurarPlanDePagos(input: ConfigurarPlanPagosInput!): OrdenCobro!

    cobrarCuotaEnEfectivo(input: CobrarEfectivoInput!): Recibo!
    generarQrPagoFacil(cuotaId: UUID!, ciNitCliente: String!, razonSocialCliente: String!): TransaccionQr!
    simularConfirmacionQrDev(pagofacilTransactionId: String!): Recibo!

    registrarEgresoCaja(input: RegistrarEgresoInput!): MovimientoCaja!
}

enum ModalidadPago { CONTADO CREDITO }
enum EstadoOrdenCobro { PENDIENTE PARCIAL PAGADO ANULADO }
enum EstadoCuota { PENDIENTE PAGADA VENCIDA ANULADA }
enum TipoMovimientoCaja { INGRESO EGRESO }
enum MetodoPago { EFECTIVO QR_PAGOFACIL TRANSFERENCIA }

type EmpresaConfig {
    id: UUID!
    nombreComercial: String!
    razonSocial: String!
    nit: String!
    direccion: String!
    telefonoContacto: String!
    emailContacto: String
    monedaSimbolo: String!
}

type Rol {
    id: UUID!
    codigo: String!
    nombre: String!
    permisos: [String!]!
}

type Usuario {
    id: UUID!
    username: String!
    email: String!
    nombreCompleto: String!
    rol: Rol!
    activo: Boolean!
}

type AuditLog {
    id: UUID!
    accion: String!
    entidadAfectada: String!
    entidadId: UUID
    detalleJson: String
    createdAt: DateTime!
}

type Paciente {
    id: UUID!
    ciNit: String!
    nombres: String!
    apellidos: String!
    telefonoWhatsapp: String!
    email: String
    direccion: String
    activo: Boolean!
}

type Especialidad {
    id: UUID!
    codigo: String!
    nombre: String!
    descripcion: String
    activo: Boolean!
}

type Odontologo {
    id: UUID!
    ci: String!
    matriculaProfesional: String!
    nombres: String!
    apellidos: String!
    telefono: String!
    email: String
    porcentajeComision: BigDecimal!
    especialidades: [Especialidad!]!
    activo: Boolean!
}

type Consultorio {
    id: UUID!
    codigo: String!
    nombre: String!
    ubicacion: String
    estado: String!
}

type ServicioOdontologico {
    id: UUID!
    codigo: String!
    nombre: String!
    descripcion: String
    precioBaseBs: BigDecimal!
    especialidad: Especialidad
    activo: Boolean!
}

type OrdenCobro {
    id: UUID!
    paciente: Paciente!
    odontologo: Odontologo
    consultaExternaId: UUID
    modalidadPago: ModalidadPago!
    montoSubtotalBs: BigDecimal!
    descuentoBs: BigDecimal!
    montoTotalBs: BigDecimal!
    saldoPendienteBs: BigDecimal!
    cantidadCuotas: Int!
    estado: EstadoOrdenCobro!
    items: [OrdenCobroItem!]!
    cuotas: [CuotaPago!]!
    createdAt: DateTime!
}

type OrdenCobroItem {
    id: UUID!
    servicio: ServicioOdontologico!
    piezaDental: String
    cantidad: Int!
    precioUnitarioBs: BigDecimal!
    subtotalBs: BigDecimal!
}

type CuotaPago {
    id: UUID!
    numeroCuota: Int!
    montoCuotaBs: BigDecimal!
    fechaVencimiento: Date!
    estado: EstadoCuota!
    fechaPago: DateTime
}

type TransaccionQr {
    id: UUID!
    cuotaId: UUID!
    pagofacilTransactionId: String!
    qrImagenBase64: String!
    montoBs: BigDecimal!
    estado: String!
    fechaExpiracion: DateTime
}

type MovimientoCaja {
    id: UUID!
    tipoMovimiento: TipoMovimientoCaja!
    categoria: String!
    metodoPago: MetodoPago!
    montoBs: BigDecimal!
    descripcion: String!
    fechaMovimiento: DateTime!
    recibo: Recibo
}

type Recibo {
    id: UUID!
    numeroRecibo: String!
    ciNitCliente: String!
    razonSocialCliente: String!
    concepto: String!
    montoTotalBs: BigDecimal!
    pdfUrlS3: String
    enviadoWhatsapp: Boolean!
    fechaEmision: DateTime!
}

type ResumenFlujoCaja {
    totalIngresosBs: BigDecimal!
    totalEgresosBs: BigDecimal!
    saldoNetoBs: BigDecimal!
    movimientos: [MovimientoCaja!]!
}

input EmpresaConfigInput {
    nombreComercial: String!
    razonSocial: String!
    nit: String!
    direccion: String!
    telefonoContacto: String!
    emailContacto: String
}

input CrearUsuarioInput {
    rolCodigo: String!
    username: String!
    email: String!
    nombreCompleto: String!
    password: String!
}

input CrearPacienteInput {
    ciNit: String!
    nombres: String!
    apellidos: String!
    fechaNacimiento: Date
    genero: String
    telefonoWhatsapp: String!
    email: String
    direccion: String
}

input CrearOdontologoInput {
    ci: String!
    matriculaProfesional: String!
    nombres: String!
    apellidos: String!
    telefono: String!
    email: String
    porcentajeComision: BigDecimal!
    especialidadesIds: [UUID!]!
}

input CrearConsultorioInput {
    codigo: String!
    nombre: String!
    ubicacion: String
}

input CrearServicioInput {
    especialidadId: UUID
    codigo: String!
    nombre: String!
    descripcion: String
    precioBaseBs: BigDecimal!
}

input ActualizarPacienteInput {
    ciNit: String!
    nombres: String!
    apellidos: String!
    fechaNacimiento: Date
    genero: String
    telefonoWhatsapp: String!
    email: String
    direccion: String
}

input ActualizarOdontologoInput {
    ci: String!
    matriculaProfesional: String!
    nombres: String!
    apellidos: String!
    telefono: String!
    email: String
    porcentajeComision: BigDecimal!
    especialidadesIds: [UUID!]!
}

input ActualizarEspecialidadInput {
    codigo: String!
    nombre: String!
    descripcion: String
}

"estado, when present, must be DISPONIBLE, MANTENIMIENTO or INACTIVO; omitted keeps the current one"
input ActualizarConsultorioInput {
    codigo: String!
    nombre: String!
    ubicacion: String
    estado: String
}

input ActualizarServicioInput {
    especialidadId: UUID
    codigo: String!
    nombre: String!
    descripcion: String
    precioBaseBs: BigDecimal!
}

"nombreCompleto and email, when omitted, keep their current value; username and password never change"
input ActualizarUsuarioInput {
    rolCodigo: String!
    activo: Boolean!
    nombreCompleto: String
    email: String
}

input OrdenCobroItemInput {
    servicioId: UUID!
    piezaDental: String
    cantidad: Int!
}

input CrearOrdenCobroInput {
    pacienteId: UUID!
    odontologoId: UUID
    items: [OrdenCobroItemInput!]!
    descuentoBs: BigDecimal = 0
    observaciones: String
}

input ConfigurarPlanPagosInput {
    ordenCobroId: UUID!
    modalidadPago: ModalidadPago!
    descuentoBs: BigDecimal = 0
    cantidadCuotas: Int!
    fechaPrimeraCuota: Date!
    intervaloDias: Int = 30
}

input CobrarEfectivoInput {
    cuotaId: UUID!
    usuarioCajeroId: UUID!
    ciNitCliente: String!
    razonSocialCliente: String!
}

input RegistrarEgresoInput {
    usuarioCajeroId: UUID!
    categoria: String!
    metodoPago: MetodoPago!
    montoBs: BigDecimal!
    descripcion: String!
}

extend type Query {
    obtenerEstadoServidorMcp: McpServerStatus!
    listarHistorialAgente(limite: Int = 20): [AgenteInteraccionResponse!]!
}

extend type Mutation {
    ejecutarAgenteErp(instruccion: String!, modoAutonomoMonitoreo: Boolean = false): AgenteInteraccionResponse!
}

type McpServerStatus {
    activo: Boolean!
    protocolVersion: String!
    toolsRegistradas: [McpToolMetadata!]!
}

type McpToolMetadata {
    nombre: String!
    categoria: String!
    descripcion: String!
}

type AgenteInteraccionResponse {
    interaccionId: UUID!
    fechaEjecucion: DateTime!
    instruccionOriginal: String!
    respuestaNaturalAgente: String!
    toolsMcpInvocadas: [String!]!
    accionCrudEjecutada: String
    notificacionN8nEnviada: Boolean!
    reporteGenerado: ReporteGenerativoPayload
}

type ReporteGenerativoPayload {
    reporteId: UUID!
    consultaSqlEjecutada: String!
    columnas: [String!]!
    filasJson: String!
    tipoGraficoSugerido: String!
    urlDescargaExcel: String!
    urlDescargaPdf: String!
}
```