# Feature: ms-erp-core
Implement backend MS1 `dental-erp-ms1` from `SPECS.md` following the 5 phases of `AGENTS.md`.
Stack decision: Java 25, Spring Boot 4.0.8, Spring AI 2.0.1 (Jackson 3), Flyway, RabbitMQ, GraphQL. Delivery: feature-branch-chain on `feat/ms-erp-core`.

## Specs
- S1 (AGENTS §5 Fase 1): "Crear `pom.xml`, `docker-compose.yml` (PostgreSQL 18, RabbitMQ 4, MinIO) y las migraciones Flyway `V1__init_erp_schema.sql` y `V2__seed_bolivia_data.sql` (con datos realistas de Santa Cruz de la Sierra, Bolivia, en Bolivianos `Bs`)." V1 = SPECS.md §2 verbatim.
- S2 (AGENTS §5 Fase 2): "Implementar `Money`, filtro de `Idempotency-Key`, perfil de seguridad local (`dev`) y el módulo de Empresa, Usuarios, Roles, Permisos y Logs."
- S3 (AGENTS §5 Fase 3): "Implementar Pacientes, Odontólogos, Especialidades, Consultorios y Servicios Odontológicos, emitiendo sus eventos de sincronización por RabbitMQ." Event payloads = SPECS.md §3.1, exchange `dental.topic.exchange`.
- S4 (AGENTS §5 Fase 4): "Implementar el listener de `clinica.consulta.finalizada`, creación de órdenes de cobro, fraccionamiento en cuotas (Contado/Crédito), adaptador PagoFácil QR (con modo Mock configurable), flujo de caja, emisión de recibos en PDF y notificación webhook a n8n." Queue `erp.consulta.finalizada.queue` (SPECS.md §3.2).
- S5 (AGENTS §4.2): "Todo cobro de cuota (Efectivo o confirmación QR) debe actualizar el estado de la cuota, descontar el saldo de la orden de cobro, registrar el ingreso en `movimientos_caja` y emitir el `recibo` dentro de una misma transacción `@Transactional`."
- S6 (AGENTS §5 Fase 5): "Implementar el motor Text-to-SQL con Spring AI y la exportación dinámica a Excel (`.xlsx`) y PDF."
- S7 (AGENTS §4.3): "El caso de uso que convierte lenguaje natural a SQL debe validar mediante expresión regular y transacción ` readOnly = true ` que la consulta generada sea exclusivamente un `SELECT`, bloqueando cualquier intento de `DROP`, `DELETE`, `UPDATE`, `INSERT` o `ALTER`."
- S8 (AGENTS §4.1): "**Prohibido importar anotaciones de Spring o JPA/Hibernate dentro de `domain.model`.**" Packages per SPECS.md §1.
- S9 (AGENTS §2): GraphQL contract = SPECS.md §4 `schema.graphqls`; only REST: "`POST /api/v1/webhooks/pagofacil`" and "`GET /api/v1/reports/export/{format}`".
- S10 (AGENTS §6): "Compilar y verificar: `./mvnw clean compile`" / "Ejecutar pruebas: `./mvnw test`".

## Tasks
- [x] T1 — S1, S10 — inline — Phase 1 infra: mvnw, pom, docker-compose, application.yml, V1/V2 — commit: dabd5bb (assess: medium, deferred to slice)
- [x] T2 — S2, S8, S9 — inline (shared kernel, domain, application) + gentle-ai-worker (persistence, security adapters, GraphQL controller, integration tests) — Phase 2 shared kernel + configsecurity — commits: f4f2002 + 29de2ee (fix). Native review: high → lineage review-dd05b8062603753c found CRITICAL R3-idempotency-async-graphql (empty body stored on async dispatch) → corrected with RED/GREEN + differential; full-scope re-review hit lens_context_budget_exceeded → reduced-scope review-7a2a61df747b6c7c on 29de2ee APPROVED and acknowledged (burned). 50 tests green.
- [x] T3 — S3, S8, S9 — gentle-ai-worker — Phase 3 mastercatalog + RabbitMQ publisher (afterCommit publish, DLX declared) — commit: see feat(mastercatalog); 92 tests green. Note: publisher logs and drops on broker failure after commit (no outbox) — accepted for now.
- [x] T4 — S4, S5, S8, S9 — gentle-ai-worker + independent gentle-ai-verify — Phase 4a orders, payment plans, cash payment, cashflow, PDF receipt, saga listener — commit: next feat(financebilling). Worker and verifier: 114 tests pass, clean compile exit 0. Verifier also ran 5 isolated public-entry probes (concurrency, rollback, immutable duplicate state, PDF postcommit boundary, plan replacement). Broker wire delivery and production JWT not exercised.
- [x] T5 — S4, S5, S9 — gentle-ai-worker + gentle-ai-verify — Phase 4b LOCAL mock QR, authenticated mock webhook, n8n — commit: next feat(financebilling). Writer 128/128; independent verifier after user-authorized scratch import correction: 131/131 exit 0, including rejected callbacks preserving rows/counters, mixed cash/QR and distinct QR races. Compile exit 0. Real PagoFacil remains unsupported (mock=false fails closed); production contract/signature/acceptance proof required.
- [x] T6 — S6, S7, S9 — gentle-ai-worker + gentle-ai-verify — Dynamic Spring AI questions, regex SQL allowlist, SELECT-only reporting credentials, three PII-free views, owner/TTL cache, PDF/XLSX exports. Initial preset-only implementation corrected with behavioral RED. Writer and independent full suite: 192 tests exit 0; clean compile exit 0. Independent public probes: real 500-row cap, rejection preserves hashes, formula literals/numeric exports and effective-role checks; baseline company/catalog output unchanged. No live provider/JWT/broker verification. Commit: next feat(reports).
- [ ] T7 — S2 — inline — Follow-up from T2 review advisories (R3-error-not-released, R3-pending-key-stranded, R4-001, R4-002): release the idempotency key on async timeout/error (AsyncListener) and reclaim stale in-progress keys; strengthen async error test — commit: _

Delivery: feature-branch-chain; original forecast ~6000 authored lines exceeded. Local slice boundaries: T1 dabd5bb; T2 f4f2002 + fix 29de2ee; T3 1ea6599; T4 next feat(financebilling). No PRs pushed/opened. Phase slices are much larger than 400 lines; future delivery needs smaller review slices.

## Log
- L1 (user): "Revisa el archivo SPECS.md y empieza a implementar"
- L7 (user): "Si, continúa sin parar" — authorized one corrected scratch verification run; 131 tests pass independently. Remaining limits: no live provider, broker wire, production JWT, measured n8n saturation or full baseline runtime differential.
- L8 (user): "¿Qué pide el requerimiento?, si o si debes basarte en eso" — follow Spring AI Text-to-SQL requirement; local vs external is not specified and must not become an invented product gate. Implement dynamic questions against allowed schema with configurable provider; no live external calls in tests.
- L2: Only JDK 17/21 present; user chose "Instalo JDK 25 yo"; then "Listo" — jdk-25 installed (25.0.5).
- L3: Docker not installed → tests are unit tests with mocks (no Testcontainers); runtime against Postgres/RabbitMQ not verifiable locally.
- L4: Delivery choice: "Cadena sobre rama feature (Recomendado)" → `chain_strategy=feature-branch-chain`.
- L5: Spring AI 2.0.1 requires Jackson 3 / Boot 4 → chose Boot 4.0.8 (within "3.5+ / 4.0").
- L6 (conventions, binding for all tasks):
  - Build: export `JAVA_HOME="C:\Program Files\Java\jdk-25"` and prepend `/c/Program Files/Java/jdk-25/bin` to PATH before `./mvnw` (shell may still default to JDK 21). Results: `target/surefire-reports/*.xml`.
  - Boot 4 / libs: Jackson 3 (`tools.jackson.databind.json.JsonMapper` bean is autoconfigured), Hibernate 7, Spring Security 7, Spring AMQP 4 (`JacksonJsonMessageConverter`), OpenPDF 3 packages `org.openpdf.text.*`, graphql-java 25 (custom scalars in `shared/infrastructure/config/GraphQlScalarConfig`).
  - Tests: Docker absent. Real PostgreSQL 18 via zonky `EmbeddedPostgresSupport` (test support). Unit tests with Mockito; integration via `@SpringBootTest` + embedded PG + `ExecutionGraphQlServiceTester`; RabbitMQ listeners `auto-startup=false` and outgoing ports mocked with `@MockitoBean`.
  - Domain (`*/domain/model`): plain Java, no Spring/JPA; UUIDs generated in domain (`UUID.randomUUID()`); validation throws `BusinessRuleException` (Spanish messages); not found → `ResourceNotFoundException`. Amounts use `shared.domain.vo.Money` in the domain, `BigDecimal` at JPA/GraphQL edges. Time via injected `java.time.Clock` (zone America/La_Paz), `OffsetDateTime` for timestamptz.
  - Application: one `@Service` per use case (`ejecutar(...)`), commands as records in `application/command`, ports in `application/port`; `@Transactional` here. Audit through `configsecurity.application.usecase.RegistrarAuditoriaUseCase.registrar(accion, entidad, entidadId, Map)`.
  - Infrastructure persistence: `*JpaEntity` + Spring Data `*JpaRepository` + adapter `@Component` implementing the domain repository; entities insert `created_at` explicitly; JSONB via `@JdbcTypeCode(SqlTypes.JSON)`; `ddl-auto: validate`.
  - Presentation: `@Controller` with `@QueryMapping`/`@MutationMapping`, `@PreAuthorize("hasAnyRole(...)")`; dev mode (`erp.security.dev-mode=true`) runs as anonymous principal `admin` with roles ADMIN,CAJERO,RECEPCIONISTA,ODONTOLOGO.
  - Generated artifacts (code, comments, identifiers) in English except domain names that mirror the Spanish schema/GraphQL contract; user-facing error messages in Spanish.
