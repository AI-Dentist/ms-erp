# Manual Report Builder — MS1 Reporting Hub

The manual report builder allows administrators to query tabular operational and financial reports across both MS1 (ERP core) and MS3 (scheduling and inventory) without LLM intervention or raw client-constructed SQL.

## Architecture and Design Principles

1. **Server-side Whitelist Only**: Client queries select strictly whitelisted entities and columns. Column keys map directly to fixed SQL expressions (for MS1) or upstream REST attributes (for MS3). No user input is ever concatenated into SQL; all filter criteria (dates, estado, limit) use parameterized JDBC bindings.
2. **Read-Only Business Semantics**: The only write operation performed by report generation is caching the generated report in the in-memory `ReportStore` (same store used by Copilot Agent reports), enabling instant export via existing `/api/v1/reports/export/{reporteId}/{format}` endpoints (`xlsx` and `pdf`). No business entities or catalog tables are ever modified.
3. **Role Authorization**: Both metadata listing and report generation require `ROLE_ADMIN`. Exports require `ROLE_ADMIN` and match the generating caller's identity.
4. **Caller Bearer Token Forwarding**: For MS3 entities (`citas`, `insumos`), MS1 calls the MS3 REST endpoint forwarding the caller's validated JWT Bearer token. No service credentials or authentication bypasses are used. If the caller lacks a valid JWT, the operation fails immediately.
5. **Fail-Closed Integration**: If MS3 is unreachable, unauthorized (401/403), returns an HTTP error, returns a malformed envelope, or references catalog identities (patients, dentists, consultorios) that cannot be resolved in MS1, MS1 fails closed with a GraphQL `SERVICE_UNAVAILABLE` error. No partial data is returned and nothing is stored in `ReportStore`.

## Supported Entities

| Entity Key | Display Name | Origin | Description & Key Columns | Date Filter | Status Filter |
|---|---|---|---|---|---|
| `citas` | Reservas / Citas | MS3 | Appointments enriched with MS1 patient, dentist, and consultorio names. Includes no-show risk score. | Yes | RESERVADA, EN_ATENCION, COMPLETADA, CANCELADA, REPROGRAMADA, NO_ASISTIO |
| `pacientes` | Pacientes | MS1 | Demographic and registration details. Foreign technical IDs are excluded. | Yes | ACTIVO, INACTIVO |
| `cuotas` | Planes de Pago y Cuotas | MS1 | Installment schedules, payment dates, due dates, order balances in Bs. | Yes | PENDIENTE, PAGADA, VENCIDA, ANULADA |
| `pagos` | Pagos y Recibos | MS1 | Caja chica income movements with receipts and cashier. Identifies nominal QR demo amounts. | Yes | None |
| `egresos` | Egresos de Caja Chica | MS1 | Expense movements from petty cash with categories and cashier. | Yes | None |
| `servicios` | Catálogo de Servicios (Arancel) | MS1 | Active tariff prices and dental specialties. Current state tariff (no date filter). | No | ACTIVO, INACTIVO |
| `insumos` | Inventario / Stock de Insumos | MS3 | Clinical stock levels, minimums, shortage flags, and periodic consumption. | Yes | None |

## GraphQL Schema Contract

```graphql
# Queries
listarEntidadesReporte: [EntidadReporte!]!

# Mutations
generarReporteManual(input: ReporteManualInput!): ReporteGenerativoPayload!

type EntidadReporte {
  clave: String!
  nombre: String!
  origen: String!
  columnas: [ColumnaReporte!]!
  filtrosEstado: [String!]!
  filtroFecha: Boolean!
}

type ColumnaReporte {
  clave: String!
  etiqueta: String!
  tipo: String! # TEXTO | NUMERO | MONEDA_BS | FECHA | FECHA_HORA | BOOLEANO
}

input ReporteManualInput {
  entidad: String!
  columnas: [String!]!
  fechaInicio: DateTime
  fechaFin: DateTime
  estado: String
}
```

The mutation returns `ReporteGenerativoPayload` with the additive field:
- `filasTruncadas: Boolean`: set to `true` when results hit the 500-row maximum cap and further rows exist upstream/locally.

## Exports and Downloads

All manual reports generated through `generarReporteManual` are stored in `ReportStore` and can be downloaded immediately:
- `GET /api/v1/reports/export/{reporteId}/xlsx`
- `GET /api/v1/reports/export/{reporteId}/pdf`
- Both formats preserve Boliviano formatting (`Bs`), clinic letterhead, typed numerical/date columns, and owner security validations.
