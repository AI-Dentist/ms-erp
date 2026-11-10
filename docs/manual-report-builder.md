# Manual Report Builder — MS1 Reporting Hub

The manual report builder allows administrators to query tabular operational and financial reports across both MS1 (ERP core) and MS3 (scheduling and inventory) without LLM intervention or raw client-constructed SQL.

## Architecture and Design Principles

1. **Server-side Whitelist Only**: Client queries select strictly whitelisted entities and columns. Column keys map directly to fixed SQL expressions (for MS1) or upstream REST attributes (for MS3). No user input is ever concatenated into SQL; all filter criteria (dates, estado, limit) use parameterized JDBC bindings.
2. **Read-Only Business Semantics**: The only write operation performed by report generation is caching the generated report in the in-memory `ReportStore` (same store used by Copilot Agent reports), enabling instant export via existing `/api/v1/reports/export/{reporteId}/{format}` endpoints (`xlsx` and `pdf`). No business entities or catalog tables are ever modified.
3. **Role Authorization**: Both metadata listing and report generation require `ROLE_ADMIN`. Exports require `ROLE_ADMIN` and match the generating caller's identity.
4. **Caller Bearer Token Forwarding**: For MS3 entities (`citas`, `insumos`), MS1 calls the MS3 REST endpoint forwarding the caller's validated JWT Bearer token. No service credentials or authentication bypasses are used. If the caller lacks a valid JWT, the operation fails immediately.
5. **Fail-Closed Integration**: If MS3 is unreachable, unauthorized (401/403), returns an HTTP error, returns a malformed envelope, or references catalog identities (patients, dentists, consultorios) that cannot be resolved in MS1, MS1 fails closed with a GraphQL `SERVICE_UNAVAILABLE` error. No partial data is returned and nothing is stored in `ReportStore`.

## MS3 Payload Validation

Before caching, MS1 requires the response `data.entidad` to match the requested entity,
`total` to be a nonnegative integer representable as a Java `long`, and the row count
to be between `min(total, 500)` and `total`. Each row must be an object with the
S3 entity's required identifiers, text, numeric and boolean fields in their original types.
Missing required fields, nested objects in scalar fields and numeric strings are malformed.
Optional/nullable fields remain nullable, including inventory `unidad`, appointment
`consultorioId`, `tipoTratamiento`, `reservadaEn`, and unavailable ML risk values.
Stock quantities remain numeric, including decimals; no display-string coercion is used for validation.

Malformed responses return the existing exact error
`SERVICE_UNAVAILABLE: No se pudo obtener el reporte de MS3`, with classification
`SERVICE_UNAVAILABLE`, no report payload and no cache insertion. Regression tests fill
all 64 cache slots through GraphQL and compare the oldest report's exported workbook
cells before/after rejection: an unintended insertion would evict that report.

### Correction Evidence — S2/S4

Against candidate `10bee36`, the GraphQL regressions reproduced all three defects:
empty row accepted, wrong entity accepted, fractional total classified as `INTERNAL_ERROR`.

- RED: `./mvnw -q -o "-Dtest=ManualReportMs3GraphQLTest#malformedEntityRowsAndTotalNeverStoreReports" -Dsurefire.failIfNoSpecifiedTests=true test`
  — exit **1**, 3 tests, 3 failures, 0 errors/skips (`target/report-builder-correction-red.log`).
- GREEN: `./mvnw -q -o "-Dtest=ManualReportLocalGraphQLTest,ManualReportMs3GraphQLTest" -Dsurefire.failIfNoSpecifiedTests=true test`
  — exit **0**, **32 tests** (10 local + 22 MS3), 0 failures/errors/skips (`target/report-builder-correction-green.log`).
- Self-review: only MS3 payload validation, public GraphQL/export regressions and this document changed;
  no authentication, accounting, offline, shared API or dependency changes. `git diff --check` passed.
- Rollback boundary: this correction's adapter validation, accompanying tests and documentation only.
  Full-suite rerun and parent acceptance are not claimed; no push or deployment is part of this batch.

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
