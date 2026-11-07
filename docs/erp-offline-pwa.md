# Activación de jornada offline

La activación online autenticada emite un documento firmado para desbloqueo **local** durante hasta ocho horas. No es un JWT de API ni autoriza llamadas al servidor. Este backend no implementa el almacenamiento, PIN ni la aplicación offline.

## Requisito de despliegue obligatorio: una identidad, un `usuarios.id`

La sincronización offline es exactamente-una-vez por `(usuarios.id, operationId)`. Por eso
**cada subject de identidad debe conservar siempre su mismo `usuarios.id`**. Re-provisionar
el mismo subject con un `usuarios.id` nuevo crea un actor distinto para el diario, de modo
que un reenvío de una operación ya confirmada **puede duplicar la acción de negocio**
(paciente, orden, cobro o egreso repetidos). Esto no lo impide el esquema: es una condición
operativa que debe respetarse al administrar identidades. Un tombstone ligado a
dispositivo/workspace no es viable con el contrato firmado actual, porque la intención
firmada no transporta esos identificadores.

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

Availability: the HTTP boundary passed owner public HTTP/JWT/embedded-PostgreSQL
checks (10/10, including replay of all four legacy mutations). This verifies the
boundary only; final independent security/concurrency verification remains pending.
For a selected new sync root, any `Idempotency-Key` (including blank) is rejected
with HTTP 400 and JSON code `OFFLINE_LEGACY_KEY_NOT_ALLOWED`; mixed business roots
are rejected with HTTP 400 and `OFFLINE_MIXED_OPERATION`. Do not send the legacy
header with new sync roots. `prepararSyncOffline` passed five owner HTTP/JWT/
PostgreSQL cases (all three roles, context, balance privacy and denials); the
boundary regression passed 10/10 again. Both canonical schema exports passed.
Its challenge is not execution authority. Confirmation passed 15 owner public
HTTP/JWT/PostgreSQL cases; preparation and boundary regression passed 15 more.
Signed `auth_time` JSON numbers are parsed exactly as integers/BigDecimal before
validation: binary-double rounding must never erase a rejected fractional value.
The status/journal read foundation passed 12 owner public HTTP/JWT/PostgreSQL
cases plus all 30 session/preparation/boundary regressions. This exercises
missing-operation status, current permissions, pinned expiry and unchanged
business data; stored-confirmation replay was added in the patient slice below.
The typed `sincronizarOperacionOffline` entry first commits immutable intent
metadata. Its initial reservation-only slice passed eight public HTTP/JWT/
PostgreSQL intent cases and 46 regressions (54 total), including same-actor retry
after a new login 48 hours later. `PREPARED` is metadata, never patient registration
or a payment. It checks the frozen canonical hash and never
overwrites a previous actor/operation identity on payload/type conflict.
Patient execution and its `CONFIRMED` receipt passed 19 owner focused HTTP/JWT/
PostgreSQL cases (11 patient and 8 injected-failure intent cases), then all 65 scoped
checks passed (61 HTTP/JWT/PostgreSQL plus four deterministic codec tests).
These verify demographic/ID mapping, current permissions, replay after a new login,
concurrent operation/CI uniqueness and rollback after outbox/confirmation writes.
This is not independent review or financial-handler proof.
The facade commits reservation first, then invokes the existing patient use case
and stores patient-ID mapping plus confirmation in the same business transaction.
Recovery records rejection/retry metadata only after that transaction rolls back.
Only a committed `CONFIRMED` result means registered; keep `PREPARED` pending.
Manual-order execution is now covered by 15 owner HTTP/JWT/PostgreSQL cases and
an 80/80 scoped run (76 HTTP plus four codec checks). Cash-payment execution passed
17 owner HTTP/JWT/PostgreSQL cases; expense execution passed 17 more. All four
handlers passed the final 114-check scoped seam (110 HTTP plus four codec checks),
with no failures/errors/skips. Final independent security/concurrency verification
and the applicable differential remain pending; owner checks are not approval. Preparation errors use GraphQL `extensions.code`:
`ACTOR_UNAVAILABLE`, `PERMISSION_REVOKED`, `SESSION_ACTOR_MISMATCH` or `INVALID_INPUT`.
Confirmation additionally classifies failures as `FRESH_LOGIN_REQUIRED`,
`SESSION_UNAVAILABLE`, `SESSION_ACTOR_MISMATCH`, `SESSION_ALREADY_BOUND` or
`SESSION_EXPIRED`. A real IDP signed `auth_time` claim and forced login remain an
unproven deployment prerequisite; `iat` is never a fallback.

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

Version-one patient interoperability vector (minified UTF-8, no trailing newline):
```json
{"payload":{"apellidos":"Munoz","ciNit":"SYN-1","direccion":null,"email":null,"fechaNacimiento":null,"genero":null,"nombres":" Ana ","telefonoWhatsapp":"+59170012345"},"type":"PATIENT_CREATE","v":1}
```
SHA-256: `1b4c2532beb6b051ee3725959694e4c5c8e375591dc010b91ca3cb939333bb1f`.
The root keys are sorted too; array ordering and string whitespace remain intact.
The typed codec accepts exact decimal/string/integer representations, never binary
float rounding. It rejects unsupported input with `INVALID_INPUT`; decimal
precision or absolute scale above 1000 is rejected before exponent expansion.
These helper limits do not replace the business cases' monetary validation.

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

### Manual-order execution (owner verified)

The dedicated handler resolves only the caller's confirmed patient-operation
mapping, without rewriting the frozen reference/hash. Missing/pending or conflicted
parents block the child; another actor's mapping is not used. Active patient,
optional dentist and sorted service rows are held with PostgreSQL `FOR SHARE`
through the unchanged manual-order use case and durable confirmation. Price/total
expectations must match exactly; negative, sub-cent and `NUMERIC(12,2)` overflow
amounts are rejected before the existing Money rounding. Contado quota mappings
come from the actual persisted order, not generated client identifiers.

The first 15 public HTTP/JWT/PostgreSQL tests produced 15 assertion failures and
zero errors/skips against reservation-only behavior. The first implementation run
passed nine and failed six on a test-only JSON-number formatting assumption
(`241.0` versus `241.00`); the assertions now compare exact decimal values without
rounding or tolerance. The corrected scoped run passed all 80 checks, with no
failures/errors/skips. Its 15 manual-order cases prove current allowed roles,
persisted amounts/quota mappings and replay, stale/invalid money with no effects,
inactive references, blocked/failed/foreign parents and subsequent resolution,
contention with the unchanged online tariff updater, and post-confirmation rollback
before a successful identical retry. This is owner evidence, not independent review.
Cash-payment and expense execution are described below. Final independent
verification remains pending.

### Cash-payment execution (owner verified)

`CASH_PAYMENT` derives the cashier from the live canonical actor; no cashier field
is accepted from the client. It resolves only owned, identity-bound confirmed
manual-order quota mappings, retaining the original frozen reference/hash. It locks
the order before the quota, rechecks their relationship, and compares exact current
states, outstanding balance and quota amount before calling the existing cash case.
Order/quota updates, income, receipt, audit and actual order/movement/receipt ID
mapping commit with durable confirmation; recovery follows business rollback.

All 17 owner public HTTP/JWT/PostgreSQL cases passed: authorized roles and actor
capture, stored payment/receipt values, replay/status, stale expectations, invalid
money, unsupported open-register requirement, blocked/foreign/conflicted/malformed
parents, cashier-field rejection, concurrent offline sessions, legacy cash-order
contention and rollback after actual confirmation SQL followed by identical retry.
The initial shared financial RED had 22 assertion failures and two permission-denial
passes across 24 cases, with no errors/skips; later controls are regression coverage,
not separately observed RED. Owner evidence does not replace independent review.

### Expense execution (owner verified)

`CASH_EXPENSE` uses the live canonical cashier and the existing expense case.
It rejects nonpositive, sub-cent or per-record `NUMERIC(12,2)` overflow amounts
before Money rounding, and retains the existing category/description validation.
The selected payment method is recorded, not executed through a payment provider.
It compares the frozen **all-time** accounting balance under PostgreSQL
`SHARE ROW EXCLUSIVE` movement-table locking, conflicting with legacy cash and
expense inserts. It acquires no order lock afterward. The signed aggregate balance
may be negative or exceed a single movement's storage bound: no insufficient-funds
or open-register rule is invented. Movement, audit, returned ID and confirmation
commit together; technical rollback keeps the same frozen intent retryable.

All 17 owner HTTP/JWT/PostgreSQL cases passed, including actual cashier command
capture/stored movement fields, status/replay after balance changes, stale/invalid
inputs without effects, negative/large aggregate balance, competing offline sessions,
legacy expense and cash-insert contention without deadlock, and rollback after
confirmation SQL followed by one identical retry. Financial focus passed 34/34;
the final scoped seam passed 114/114 in 231.7 seconds. The suite excludes historical
T3/T6/n8n/global checks and real providers/containers. Final independent verification
and the applicable differential are still pending; no native approval is claimed.

### Denial, immutable intent, cash parity and deterministic expense clock

A denial is recorded on the existing reservation instead of deleting it: the row moves to
`BLOCKED` with the denial code, while `identity_issuer`, `identity_subject`,
`operation_type`, `canonical_version` and `payload_hash` stay frozen. A later replay with
the same `operationId` but altered intent therefore still answers `PAYLOAD_MISMATCH`
(or `OPERATION_TYPE_MISMATCH`) and never executes; a retry of the identical frozen
intent continues normally. A PostgreSQL trigger rejects any `UPDATE` of those frozen
columns (or of the key), so the guarantee does not depend on application code alone.
`confirm(...)` refuses a null result, so `CONFIRMED` always carries a real result.

The journal key is `(actor_id, operation_id)`. Exactly-once replay therefore depends on the
deployment requirement above: one identity subject must keep one `usuarios.id`; re-provisioning
it to a new `usuarios.id` can duplicate a confirmed business action.

`CASH_PAYMENT` no longer compares an installment amount against the current order
balance: the unchanged cash use case pays any pending installment, so the offline path
must not add that restriction. Expense validation reuses the injected application
`Clock` instead of reading the system clock directly. Neither change alters the
GraphQL contract or legacy commands. Owner checks after these fixes: denial case plus
the ten scoped classes (see the commit message for the observed counts); the two
advisory changes have no separately observable failure path, which is stated instead
of claimed as a red observation.

### Scoped verification set and its per-class counts

`python target/t6-catalog/run-tests.py <label> seam` selects exactly these thirteen classes
(the runner holds the list as `SEAM_SCOPE`, so the scope cannot drift with a naming pattern):

| Class | Cases |
|---|---|
| OfflineFailureClassifierTest | 8 |
| OfflineSyncDenialHttpTest | 8 |
| OfflineOperationSchemaTest | 2 |
| OfflineOperationIntentHttpTest | 8 |
| OfflinePatientSyncHttpTest | 14 |
| OfflineCashExpenseHttpTest | 18 |
| OfflineCashPaymentHttpTest | 17 |
| OfflineManualOrderHttpTest | 15 |
| OfflineOperationStatusHttpTest | 12 |
| OfflinePayloadCodecTest | 4 |
| OfflineSyncSessionHttpTest | 15 |
| OfflineSyncPreparationHttpTest | 5 |
| OfflineSyncBoundaryLegacyHttpTest | 10 |

The two integration classes `OfflineReplayHttpIntegrationTest` and
`OfflineActivationHttpIntegrationTest` are excluded: they belong to the earlier accepted slices,
not to this sync seam. Their exclusion is why the seam reports a smaller number than the whole
offline test tree.

### Duplicate classification: authoritative path and known heuristic

A duplicate CI/NIT is classified in a fixed order. The authoritative signal is the domain rule
code (`PATIENT_CI_NIT_DUPLICATE`) or, for a race at the unique index, the PostgreSQL constraint
name read from the driver's typed server error. When neither is readable - a shaded, relocated
or wrapped driver - the classifier falls back to scanning the exception messages for the
constraint identifier. That fallback is a heuristic: it can misread a duplicated VALUE that
happens to be shaped like the constraint name, in which case a non-duplicate could be reported
as `PATIENT_DUPLICATE`. A real duplicate still classifies correctly on either path, and a
unique violation that exposes nothing is a permanent `INVALID_INPUT` with a structured warning,
never a silent retry.

### Status after a conflicting reservation

A replay that keeps the same `operationId` but changes the payload, the type or the identity
link is rejected as `CONFLICT` with `PAYLOAD_MISMATCH`, `OPERATION_TYPE_MISMATCH` or
`ACTOR_LINK_CHANGED`, and the rejection is recorded durably on the existing intent as
`BLOCKED` with that reason. `estadoOperacionOffline` therefore answers `BLOCKED` plus the
reason code after a conflicting replay. The earlier behaviour, where a conflicting replay
left the row `PREPARED` with no reason until something else happened, is no longer current.

Degraded case, stated instead of promised away: recording the reason is a best-effort write in
its own transaction. If that write cannot run (unavailable identity, a ledger failure, or an
identity mismatch where nothing is written at all), the intent keeps its previous state and the
status may still answer `PREPARED` with a null reason. The conflict itself is always returned to
the caller that triggered it; only the durable record can be missing, and the server logs the
failure rather than pretending the write succeeded.

`BLOCKED` is **not terminal**: the frozen intent may still be executed with its original
payload, which is why the same state now means both "a denial was recorded" and "a conflict
was recorded". `CONFIRMED` and `CONFLICT` are terminal: neither can be rewritten by a later
denial, and a `CONFIRMED` receipt stays immutable.

### Client contract clarifications

**Open cash register.** `requireOpenCashRegister` defaults to `false`, and omitting it
does **not** assert that a caja is open: the model has no apertura/cierre rule to check,
so the field is only a client request. Sending `true` still conflicts with
`CASH_REGISTER_STATE_NOT_MODELLED` and writes nothing; sending `false` or omitting it
records the movement without any positive claim about register state.

**Discounts.** A `descuentoBs` supplied by the client on `OfflineOrderInput` is not a new
offline capability: it is the pre-existing parameter of the legacy
`crearOrdenCobroManual` mutation, reused unchanged by the dedicated handler. No
offline-only discount rule, cap or authorization was added, and the frozen
`expectedTotalBs` must still match the computed total exactly. Treating discounts on
manual orders as a distinct business decision remains an open product question outside
this contract; nothing in the offline path grants a client an additional discount
right.

**Accounting balance.** `cashflowBalanceBs` returned by `prepararSyncOffline` means
all-time accounting ingresos minus egresos, in Bolivianos. Immediately before sending a
`CASH_EXPENSE`, the client must copy that exact value into `expectedCashflowBalanceBs` as
a plain decimal string in `Bs`. Because synchronization always happens online, a client
holding an older value must call `prepararSyncOffline` again to obtain the current
balance before sending. A different value conflicts with `STALE_CASHFLOW_BALANCE` and
writes nothing. The figure is not physical cash, not a per-period total and not tied to
an open register.

**Order balance.** `ordenes_cobro.saldo_pendiente_bs` is always the sum of the pending
installments; that identity is maintained through plan configuration, every payment
(cash or QR) and plan annulment. The offline `CASH_PAYMENT` payload therefore keeps
expecting the exact `expectedOrderBalanceBs` and `expectedQuotaAmountBs`, but it no longer
also requires the installment amount to equal the order balance, matching the unchanged
online cash use case.

**Transaction boundary.** `sincronizarOperacionOffline` runs with `Propagation.NEVER`,
so it never joins an enclosing transaction and no session, intent or financial lock can
leak across its two sequential transactions. The evaluation kept `execute` on its
existing `REQUIRED` propagation: with the facade refusing any outer transaction, the
execution transaction is always created and owned by `execute` itself, so the business
effects, the canonical result mapping and the `CONFIRMED` transition keep committing
together. A denial raised while reserving is recorded on the committed `PREPARED` intent
as `BLOCKED` with that denial code, and a denial after `CONFIRMED` is a no-op that keeps
the stored result.
