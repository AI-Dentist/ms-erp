# Activación de jornada offline

La activación online autenticada emite un documento firmado para desbloqueo **local** durante hasta ocho horas. No es un JWT de API ni autoriza llamadas al servidor. Este backend no implementa el almacenamiento, PIN ni la aplicación offline.

## Solicitud online

```graphql
mutation Activar($input: ActivarJornadaOfflineInput!) {
  activarJornadaOffline(input: $input) { credencial clavePublicaSpki }
}
```

```json
{"input":{"workspaceId":"11111111-1111-4111-8111-111111111111","deviceId":"22222222-2222-4222-8222-222222222222","durationSeconds":28800}}
```

`workspaceId` y `deviceId` son UUID obligatorios; `durationSeconds` es entero, por defecto 28800, entre 1 y 28800 inclusive. La respuesta `JornadaOffline!` contiene dos `String!`: `credencial` (JWS compacto) y `clavePublicaSpki` (base64 estándar de DER SubjectPublicKeyInfo RSA). No hay campos de actor, rol, PIN ni bearer en la entrada. Se requiere JWT validado con issuer configurado y subject vinculado a usuario ERP activo; el rol actual de BD debe coincidir con una autoridad del JWT. Capacidades: ADMIN `CASH`, `EXPENSES`, `MANUAL_ORDERS`, `PATIENTS`; CAJERO las tres primeras; RECEPCIONISTA las dos últimas.

| Capacidad firmada | Mutación existente (sólo tras autenticación online para sincronizar) |
|---|---|
| `CASH` | `cobrarCuotaEnEfectivo` |
| `EXPENSES` | `registrarEgresoCaja` |
| `MANUAL_ORDERS` | `crearOrdenCobroManual` |
| `PATIENTS` | `crearPaciente` |

## Formato y verificación

El header protegido decodificado tiene `{"alg":"RS256","typ":"erp-offline-grant+jws","kid":"<id configurado>"}`. El payload personalizado tiene exactamente:

```json
{"version":1,"grantId":"<UUID aleatorio>","grantIssuer":"<emisor configurado>","audience":"erp-offline-local-only","erpUserId":"<UUID ERP>","identityIssuer":"<issuer JWT validado>","identitySubject":"<subject JWT>","role":"ADMIN","capabilities":["CASH","EXPENSES","MANUAL_ORDERS","PATIENTS"],"workspaceId":"<UUID>","deviceId":"<UUID>","issuedAt":"<instante ISO-8601 UTC>","expiresAt":"<instante ISO-8601 UTC>"}
```

El documento consta de `base64url(header).base64url(payload).base64url(firma)`, sin padding; la entrada firmada es el ASCII de los dos primeros segmentos unidos por punto. Para WebCrypto, decodificar `clavePublicaSpki` como base64 estándar a bytes y usar `crypto.subtle.importKey('spki', bytes, {name:'RSASSA-PKCS1-v1_5', hash:'SHA-256'}, false, ['verify'])`; verificar la firma base64url decodificada contra esa entrada con `crypto.subtle.verify('RSASSA-PKCS1-v1_5', key, signature, signingInput)`. Rechazar formatos inesperados, algoritmos distintos de RS256, `typ` diferente o `kid` no fijado. Fijar la clave y `kid` recibidos por el canal online autenticado: **nunca confiar en una clave incluida en el propio documento no verificado**.

Tras verificar firma, comprobar versión 1, emisor configurado, audiencia exacta, identidad ERP/issuer/subject esperados, rol y capacidades esperadas, UUID de dispositivo y workspace, instantes válidos y ventana positiva no mayor a 8 horas. Ante expiración, revocación local o reloj incoherente bloquear el workspace conservando los datos. La revocación remota no es verificable sin conexión; el reloj local manipulable, la fuerza bruta contra PIN y XSS son riesgos que requieren defensas del cliente, no garantías de este backend.

## Operación y límites

El operador inyecta mediante entorno o gestor de secretos de ejecución `erp.offline.signing.private-key-pkcs8-base64` (PKCS#8 RSA, mínimo 2048 bits), `erp.offline.signing.key-id` y `erp.offline.signing.grant-issuer`. No se distribuye ninguna clave ni se genera una por defecto. Configuración ausente o inválida rechaza la activación con error fijo redactado sin impedir el arranque ni las API anteriores. El issuer de identidad debe estar configurado en el resource server (o en `erp.security.local-dev-user.jwt-issuer-uri` para local). Proteja y rote claves con el proceso operativo; los clientes deben fijar explícitamente la clave y kid autorizados.

El PIN nunca se envía al backend. No persistir bearer, refresh token ni contraseña para uso offline. Nunca adjuntar el grant como `Bearer` ni usarlo para replay; sincronizar requiere nueva autenticación y permisos vigentes, pero esta entrega **no implementa ni corrige** esa comprobación en replay. El caché existente de POST con clave de idempotencia puede devolver el grant original con su vencimiento fijo, no una autorización fresca: solicitar una nueva activación sin reutilizar la clave de idempotencia. Las operaciones pendientes y su resolución segura pertenecen a una entrega posterior.


## T4 safe synchronization — frozen v1 contract, implementation pending

The following additive contract is published **before T4 source changes**. T3 at
`fce174b` remains accepted and unchanged. Availability will be recorded per slice;
these fields are not claimed to exist until their implementation/checks are delivered.

### Authentication/session protocol

All calls require the existing validated resource-server JWT. Neither the local
grant, PIN, a client boolean nor JWT `iat` is authentication/reauthentication proof.

1. `prepararSyncOffline` checks the live active canonical issuer/subject/user/role,
   then creates a non-secret challenge bound to that actor, workspace and device.
   It returns `reauthNotBefore` (next whole server second) and challenge expiry
   (24 hours). Clients may prepare while still online before going offline; without
   a prepared challenge they must request one and then explicitly log in again.
2. Force a new online login. `confirmarSyncOffline` requires the **signed access
   JWT** `auth_time` to be at/after that challenge boundary, not in the future and
   no more than 300 seconds old. Issuer/subject/ERP actor must match the challenge.
   Missing/nonintegral/old `auth_time`, merely newer `iat`, refresh-only or another
   actor fails closed. Real IDP claim availability/forced-login integration is a
   deployment prerequisite, not demonstrated by synthetic JWT fixtures.
3. Confirmation pins that exact `auth_time` and the proof token's `exp` to the
   sync session. Reconfirm/replay cannot extend it. Every execution/status/replay
   again checks JWT identity, pinned authentication time, session/token expiry,
   active canonical linkage and **current** DB role plus matching JWT authority.
   Refresh retaining `auth_time` cannot extend the stored deadline; another login
   needs another sync session. Sessions never authorize anything without JWT.
4. No JWT, refresh token, password, PIN or local grant is persisted in session or
   operation rows. Store identity IDs, proof times and context only.

The new HTTP boundary is after security and before the old order-0 idempotency
filter. For the **selected** new root fields below, it rejects `Idempotency-Key`
with HTTP 400/code `OFFLINE_LEGACY_KEY_NOT_ALLOWED`; operation UUIDs are authoritative.
Resolve aliases/fragments/operationName using the GraphQL AST, preserve request
bytes, and reject mixed business roots for new sync calls. Legacy selected
operations keep their existing behavior; do not edit `IdempotencyFilter`.
Authentication runs first, so this is not an anonymous cache-bypass endpoint.

### GraphQL surface

```graphql
# Additions to Mutation:
prepararSyncOffline(input: PrepararSyncOfflineInput!): OfflineSyncChallenge!
confirmarSyncOffline(sessionId: UUID!): OfflineSyncSession!
sincronizarOperacionOffline(input: OfflineOperationInput!): OfflineOperationReceipt!
# Addition to Query:
estadoOperacionOffline(sessionId: UUID!, operationId: UUID!): OfflineOperationReceipt!

input PrepararSyncOfflineInput {
  workspaceId: UUID!
  deviceId: UUID!
  expectedActorId: UUID!
}
type OfflineSyncChallenge {
  sessionId: UUID!
  actorId: UUID!
  workspaceId: UUID!
  deviceId: UUID!
  reauthNotBefore: DateTime!
  expiresAt: DateTime!
  cashflowBalanceBs: BigDecimal
}
type OfflineSyncSession {
  sessionId: UUID!
  actorId: UUID!
  workspaceId: UUID!
  deviceId: UUID!
  authenticatedAt: DateTime!
  expiresAt: DateTime!
}
enum OfflineOperationType { CASH_PAYMENT CASH_EXPENSE MANUAL_ORDER PATIENT_CREATE }
enum OfflineOperationState { PREPARED CONFIRMED CONFLICT BLOCKED NOT_FOUND }
input OfflineOperationInput {
  sessionId: UUID!
  operationId: UUID!
  type: OfflineOperationType!
  cash: OfflineCashInput
  expense: OfflineExpenseInput
  order: OfflineOrderInput
  patient: CrearPacienteInput
}
input OfflineReferenceInput {
  id: UUID
  operationId: UUID
  installmentNumber: Int
}
input OfflineCashInput {
  quota: OfflineReferenceInput!
  expectedOrderState: EstadoOrdenCobro!
  expectedQuotaState: EstadoCuota!
  expectedOrderBalanceBs: BigDecimal!
  expectedQuotaAmountBs: BigDecimal!
  ciNitCliente: String!
  razonSocialCliente: String!
  requireOpenCashRegister: Boolean = false
}
input OfflineExpenseInput {
  categoria: String!
  metodoPago: MetodoPago!
  montoBs: BigDecimal!
  descripcion: String!
  expectedCashflowBalanceBs: BigDecimal!
  requireOpenCashRegister: Boolean = false
}
input OfflineOrderInput {
  patient: OfflineReferenceInput!
  odontologoId: UUID
  items: [OfflineOrderItemInput!]!
  descuentoBs: BigDecimal = 0
  observaciones: String
  expectedTotalBs: BigDecimal!
}
input OfflineOrderItemInput {
  servicioId: UUID!
  piezaDental: String
  cantidad: Int!
  expectedUnitPriceBs: BigDecimal!
}
type OfflineOperationReceipt {
  operationId: UUID!
  type: OfflineOperationType
  state: OfflineOperationState!
  payloadHash: String
  reasonCode: String
  confirmedAt: DateTime
  result: OfflineConfirmedResult
}
type OfflineConfirmedResult {
  patientId: UUID
  orderId: UUID
  installments: [OfflineInstallmentId!]!
  movementId: UUID
  receiptId: UUID
}
type OfflineInstallmentId { number: Int!, id: UUID! }
```

Exactly one payload must match `type`. Do not accept a client cashier: cash/expense
derive it from the live canonical actor. Capabilities retain the existing role map:
ADMIN all four; CAJERO cash/expense/manual order; RECEPCIONISTA manual order/patient.
Status/replay of a prior operation still requires its operation permission now.
A missing operation returns `NOT_FOUND` without another actor's information.
`cashflowBalanceBs` is nullable for users without expense permission; it means
all-time accounting ingresos minus egresos, **not physical cash or an open caja**.
Expense compares its expected accounting balance under a lock conflicting with
all legacy movement inserts. Do not introduce an insufficient-funds rule absent
from the domain. QR operations/methods are outside this offline path.

### Frozen payload, receipts and references

Canonical version 1 hashes UTF-8 JSON `{"v":1,"type":...,"payload":...}` with SHA-256.
Use the selected typed payload only; recursively sort its fixed ASCII object keys,
include nullable fields, apply documented defaults, preserve strings/array order,
canonicalize UUIDs/dates/enums, and encode decimals as exact plain strings with
trailing zeros removed (zero `"0"`, no rounding). Session/token/request projection,
headers and auth timestamps are **not** part of the payload. Unknown fields fail
GraphQL validation. A new login/session must not change an intent's hash.

Unique durable key is canonical ERP actor UUID + operation UUID. Also pin original
issuer/subject, operation type, canonical version and hash. Different type/payload
or changed identity linkage conflicts; never overwrite/reassign or generate another
UUID automatically. No operation TTL deletion or expiry-based reexecution.

A separately committed `PREPARED` row may pin immutable intent metadata; it is
**not** a business receipt or proof of execution. A row lock/unique constraint
serializes attempts. Business effects, existing audit/outbox, canonical result/IDs
and transition to `CONFIRMED` commit in **one shared PostgreSQL transaction**.
Crash before that commit leaves no confirmed business effects; crash/lost ACK after
it returns the same durable result on retry/status. Technical rollback retains
prepared metadata for the same frozen retry; never infer success from HTTP 200 or
a GraphQL `errors` substring. Confirmed receipt bytes/IDs never get recomputed.

References contain exactly one of `id` or `operationId`. Patient references to an
operation require its confirmed PATIENT_CREATE result and omit installmentNumber.
Quota references to an operation require confirmed MANUAL_ORDER and a positive
installmentNumber (v1 manual orders are contado, number 1). Literal UUID references
omit installmentNumber. Always resolve under the same actor/linkage; do not guess
patients by CI or pick an arbitrary quota.

Client temporary labels may be `patient:<operationUUID>`, `order:<operationUUID>`
and `quota:<orderOperationUUID>:1`. The confirmed result maps them to actual IDs.
Freeze these operation references in the payload **before first send**; resolving
them does not rewrite the hashed intent. Parent absent/prepared -> `BLOCKED`
with `DEPENDENCY_PENDING`; parent conflicted -> `DEPENDENCY_CONFLICT`; unrelated
eligible operations can continue. No deletion/rollback of already confirmed parents.

### Conflicts, actual model and approved edit surfaces

Expected order/quota state/amount/balance and active references must be checked
after locks that also conflict with online updates. Lock service IDs deterministically
before price/total comparison; invoke existing transactional business use cases only
after validation, never reprice/reassign/backdate silently. Duplicate CI/NIT and
concurrent unique violations leave no patient/business writes. Prevalidate known
conflicts; never persist a success in a rollback-only transaction.

Stable reason codes include `STALE_PRICE`, `STALE_ORDER`, `STALE_QUOTA`,
`STALE_CASHFLOW_BALANCE`, `INACTIVE_REFERENCE`, `PATIENT_DUPLICATE`,
`DEPENDENCY_PENDING`, `DEPENDENCY_CONFLICT`, `PAYLOAD_MISMATCH`,
`OPERATION_TYPE_MISMATCH`, `ACTOR_LINK_CHANGED`, `PERMISSION_REVOKED`,
`INVALID_INPUT` and `TECHNICAL_RETRY_REQUIRED`. CONFIRMED alone means registered.
CONFLICT is retained for explicit review; do not automatically retry it with changed
values. Resolution creates a new intentional operation UUID, linked client-side to
the old pending item; server history is retained. BLOCKED may be reevaluated when
its unchanged dependency becomes confirmed. Failed authorization never returns a
cached confirmed payload or mutates business data. Metadata may record conflict,
but no financial/catalog business effect is allowed.

There is currently **no apertura/cierre de caja entity/rule** in the expense model:
`RegistrarEgresoCajaUseCase.java:29-36` validates cashier existence then records
movement/audit. A true `requireOpenCashRegister` is therefore an unevaluable client
requirement and must return conflict `CASH_REGISTER_STATE_NOT_MODELLED`, with no
business effect; false does not assert the caja is open. Existing receipt-number
sequences can have rollback gaps; no claim of gap-free hidden SQL counters is made.
PDF/notification run after financial commit and are not the durable confirmation.

No extra ERP source surface is currently required. Proposed paths stay inside the
approved list: new `offline/{application,domain,infrastructure,presentation}/**`,
new `*Offline*` adapters/use cases in the approved existing contexts, additive
source SDL, `V12__offline_sync.sql` (sessions), `V13__offline_operations.sql`
(ledger), new offline tests and both existing goldens.
JPA/JDBC transaction sharing, legacy-conflicting row/table locks and fault rollback
must be proven before claiming this design works. If reuse cannot satisfy them,
report the exact additional repository/updater path before editing it.
No edits to T3 implementation, old commands, legacy filter, dependencies or real
IDP files are proposed. Ensuring signed access-token `auth_time` from real login
is an IDP deployment integration prerequisite; absence fails closed, never to iat.

Implementation slices: session/boundary; durable patient replay; order refs/prices;
cash/expense atomic expectations; one final security/concurrency verification.
Each code slice carries public HTTP/JWT tests/docs and observed RED/GREEN; preserve
legacy controls and WIP. This contract publication alone is not runtime proof.
