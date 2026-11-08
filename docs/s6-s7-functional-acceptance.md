# Verify recovered receipt downloads and terminal catalog events

S6 is implemented in `32fb0785fd278e07871dd330d0d113d8042720af`; S7 in
`967e16c8341b498c1a0fe1841f7fd2c967f4708c`. The final bounded check runs the existing
receipt-ownership and terminal-catalog fixtures, **not** the global suite or n8n.

## Evidence and reproduction

Final targeted run: **10/10 PASS**, exit 0 in 167.376 seconds, no failures,
errors or skips (`target/s6-s7-final/functional-01`): receipt 4/4, catalog 6/6.
The path is relative to the primary workspace; source is the registered
`target/worktrees/ms1-integration-guide` worktree.

The existing isolated offline runner is restricted to these two classes:

```sh
python target/s6-s7-final/run-tests.py functional-01 ReceiptArtifactOwnershipTest,CatalogOutboxTerminalTest
```

That exact label identifies the recorded run, not a command to replay into its
existing evidence directory. For a separately authorized reproduction, use a fresh
label with the same class filter. The runner requires already cached Maven/JDK and
artifacts, supplies public empty Maven settings, strips provider environment
variables, and creates fresh HOME/TMP, build output and PostgreSQL fixtures. It
performs no installations or downloads. Test-only limits remain pool maximum 4,
minimum idle 0 and Spring context cache 8; production configuration is unchanged.

| Boundary | What actually executes | What is substituted |
|---|---|---|
| API | Spring GraphQL execution, schema binding and method authorization | In-process tester, not browser/HTTP JWT validation |
| Database | Real embedded PostgreSQL, Flyway and application transactions | Synthetic, disposable fixture data only |
| PDF/storage client | PDF generation and real MinIO Java SDK PUT/HEAD/presigning | Loopback HTTP S3 fixture; not SeaweedFS/AWS |
| PDF failure | Public payment followed by public cashflow/download query | Spy injects a link exception or `beforeCommit` exception after save; not a physical crash |
| Catalog delivery | Real dispatcher invoked by the fixture, SQL row locks, retry policy and terminal audit (scheduler disabled) | Mocked RabbitTemplate correlated ACK/NACK/return/timeout; no wire RabbitMQ |
| Audit visibility | Existing ADMIN `listarAuditLogs` query | Synthetic Spring security principal |

Fixture SQL automatically isolates due times; observations use public GraphQL and
the storage/broker boundary. There are no manual database edits. The broader
historical **655/655** and **74/74** runs also contain these substitutions; neither
is real-service E2E certification.

### Assertions exercised

- Receipt: accepted PDF has a committed owner before PUT; failed linkage leaves
  the payment committed once. After persistence recovers, the **same query** links
  the same object and returns a presigned download URL. These fixtures verify
  stored `%PDF` bytes and HEAD, but do **not** execute HTTP GET or S3 signature validation. Repeated reads preserve one owner,
  one accepted PUT, unchanged object count and one payment. Success and storage-403
  controls preserve their existing behavior.
- Catalog: outage, NACK, mandatory return and missing confirm exhaust the configured
  three attempts; ADMIN sees one `FALLIDO` audit. Making the fixture due again or
  completing a late ACK does not schedule another send. Successful retry preserves
  message identity/payload. Terminal-audit failure rolls back the decision.

## Real-service protocol — prepared, not executed

Use **only a separate disposable synthetic environment** with existing Java 25/JDK
`jdb`, PostgreSQL, RabbitMQ management and private S3-compatible storage. Do not
install tools, use production data, change IAM, modify product code or edit the DB.
Do not interrupt the coordinator's ERP `32fb078` / frontend `68af0d7` acceptance.
Use an artifact built from product commit `967e16c`; let normal Flyway apply V11.
Keep n8n and paid/AI providers disabled. Use existing development authentication;
never store tokens, credentials, complete signed URLs or authorization headers in
proof. A missing tool/environment is an unexecuted prerequisite, not a PASS.

Configure the isolated ERP with existing properties:
`erp.catalog-outbox.max-attempts=3` and
`erp.messaging.exchange=acceptance.s6-s7` and `erp.catalog-outbox.enabled=true`.
Keep the same database and receipt bucket
across the receipt restart. An existing authorized S3 inventory/access-log tool is
required to count keys/PUTs; do not install one or infer object cardinality from a
signed URL alone.

### S6: real uploaded object survives a failed link and becomes downloadable

1. Start only the disposable ERP with a loopback debugger, using its existing
   synthetic configuration and artifact. This is an operator procedure, not an
   instruction to expose a debug port on any shared or production service:

   ```sh
   java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005 -jar "$ERP_SYNTHETIC_JAR" --spring.profiles.active=local --erp.catalog-outbox.max-attempts=3 --erp.messaging.exchange=acceptance.s6-s7 --erp.catalog-outbox.enabled=true
   jdb -attach 127.0.0.1:5005
   ```

   In `jdb`, set the method-entry breakpoint without printing arguments:

   ```text
   stop in com.dentalplatform.erp.financebilling.application.usecase.ReciboPdfArtefactoService.vincular
   cont
   ```

2. As ADMIN/CAJERO, create a new synthetic order through
   `crearOrdenCobroManual`, using existing synthetic patient/service UUIDs. Record
   the returned order and installment UUIDs. Pay that installment once with
   `cobrarCuotaEnEfectivo`; select `id numeroRecibo` only.
3. When the breakpoint fires, the financial payment and reservation have committed
   and PUT has returned, but the receipt link has not executed. Terminate **only
   this disposable ERP JVM**, not PostgreSQL, storage or RabbitMQ. A disconnected
   payment request is expected here; it is not evidence that payment rolled back.
4. Restart the same artifact/configuration without the breakpoint. Before asking
   for a download, use public audit/cashflow queries without `pdfUrlS3` to identify
   the receipt and its `RECIBO_PDF_RESERVADO` reference. Through the existing S3
   inventory, record exactly one matching `<receipt-UUID>.pdf` key and its ETag or
   version. Do not expose credentials or a signed URL.
5. Query the same receipt's `pdfUrlS3` twice through cashflow. Both requests must
   produce a usable signed URL for the same key. Download it successfully (HTTP
   200, PDF), but record only status/path, not the bearer URL. Confirm one
   `RECIBO_PDF_REPARADO` audit, unchanged key count/version and **no additional PUT**
   in storage access evidence. No second payment or PDF upload is a repair step.
6. Public order detail must show `PAGADO`, zero balance and the same paid installment;
   cashflow must contain one corresponding movement/receipt. A duplicate cash
   payment must be rejected without changing those counts or the storage key.

The same read-only inventory queries are used before and after; only the requested
`pdfUrlS3` field triggers the authorized artifact repair:

```graphql
query ReceiptEvidence($from: DateTime!, $to: DateTime!) {
  obtenerFlujoCaja(fechaInicio: $from, fechaFin: $to) {
    movimientos { id metodoPago montoBs recibo { id numeroRecibo } }
  }
  listarAuditLogs(limite: 500) { accion entidadAfectada entidadId detalleJson }
}
```

For the retry, add `pdfUrlS3` to the receipt selection and match the same receipt UUID
in the client. The audit list is latest-500, so use a quiet isolated fixture and a
bounded date window. No SQL or guessed storage references are required.

### S7: real mandatory return reaches a terminal state visible to ADMIN

1. In the isolated RabbitMQ management UI, verify the private exchange
   `acceptance.s6-s7` exists. Leave it **without a binding matching**
   `catalogo.consultorio.creado`. Other queues/exchanges must not route this key.
   A healthy broker with mandatory returns avoids ambiguous late outage deliveries.
2. Snapshot ADMIN audit IDs, then call `crearConsultorio` once with a unique
   synthetic code. Creation must succeed and return `DISPONIBLE`. Do not alter
   outbox rows or reset counters.
3. Within a bounded observation window (60 seconds for the stated three-attempt,
   default-backoff configuration), find one new
   `CATALOGO_EVENTO_PUBLICACION_FALLIDA` audit. Require
   `estado=FALLIDO`, `attempts=3`, `motivo=PUBLICACION_NO_CONFIRMADA` and routing key
   `catalogo.consultorio.creado`; no payload/contact data. Capture the event UUID.
   Use isolated exchange publisher/return statistics to corroborate three attempts.
4. Declare a disposable capture queue and bind it to that exchange/key through the
   existing management UI. Observe at least two configured polls: the terminal
   event must not reappear, publisher statistics must not increase for it, and its
   terminal audit must remain single. No manual rearm or database update is allowed.
5. Create another synthetic office. Its different event UUID must arrive on the
   capture queue with the returned office UUID and unchanged catalog contract.
   This healthy-delivery control must not produce a terminal audit.

Use ADMIN `listarAuditLogs` as above. A non-ADMIN rejection is expected, not a reason
to relax roles. This protocol does not claim exactly-once delivery; uncertain wire
acceptance after a lost database commit can still duplicate the same event UUID.

## Scope and retained history

`AgentAutonomousN8nTest.failedDeliveryReleasesTheClaimSoTheReminderCanBeRetriedAndNeverPretendsDelivery`
hit the GraphQlTester five-second timeout in the historical global run: **665
passed, one error**, not global GREEN. The user explicitly excluded n8n from this
ERP-classic delivery. Preserve that result as **excluded from this task**, never
rewrite it as PASS. No global suite, n8n timeout or exhausted verifier is rerun.
Real broker/storage/JWT/crash execution remains unverified until the operator runs
and records the protocol above; preparing it is not evidence of execution.
