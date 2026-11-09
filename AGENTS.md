# AGENTS.md — Directrices Operativas para el Agente de Código

## 1. Identidad del Proyecto
* **Nombre del Servicio:** `ms-erp` (Microservicio 1: Core de Gestión Empresarial, Configuración y Finanzas).
* **Alcance Exclusivo:** Este repositorio contiene **ÚNICAMENTE el Backend del Microservicio 1**. No generes código de frontend (HTML/JS/TS/Angular/React) en este repositorio.
* **Paquete Base Java:** `com.dentalplatform.erp`

## 2. Stack Tecnológico Obligatorio (Últimas Versiones Estables)
* **Lenguaje:** Java 25 (LTS) con Virtual Threads habilitados (`spring.threads.virtual.enabled=true`).
* **Framework Base:** Spring Boot 4.0.8.
* **Arquitectura Interna:** Domain-Driven Design (DDD) estricto dividido en 3 Bounded Contexts (`configsecurity`, `mastercatalog`, `financebilling`).
* **Capa de Exposición Principal:** Spring for GraphQL (`schema.graphqls`).
* **Excepciones REST Permitidas:** 
  1. `POST /api/v1/webhooks/pagofacil` (Recepción de confirmación de cobro QR Bolivia).
  2. `GET /api/v1/reports/export/{reporteId}/{format}` (`format`: `xlsx|pdf`; descarga de reportes ya generados).
* **Excepción de Protocolo:** `/mcp`, servidor MCP real mediante Spring AI MCP Server sobre Streamable HTTP/WebMVC, autenticado como el resto de la API; no es una API REST de negocio.
* **Base de Datos:** PostgreSQL 18 con migraciones automáticas vía **Flyway**.
* **Mensajería Asíncrona (Patrón Saga):** Spring AMQP con **RabbitMQ 4.x**.
* **Inteligencia Artificial:** Spring AI 2.0.1; único punto de entrada `ErpCopilotAgentUseCase` (capa application de `financebilling`) y herramientas `ErpMcpToolsService`, también expuestas por MCP. Cero IA local en el backend: la IA local (embeddings) se ejecuta exclusivamente en el navegador del frontend; el backend nunca ejecuta modelos locales.
* **Seguridad e Idempotencia:** Spring Security 6.4+ (OAuth2 Resource Server para JWT de Keycloak) + Filtro de Idempotencia mediante cabecera `Idempotency-Key`.
* **Exportación de Documentos:** Apache POI (Excel `.xlsx`) y OpenPDF (PDF), con membrete de la clínica e importes en Bolivianos (`Bs`).
* **Almacenamiento y Despliegue:** almacenamiento compatible con S3: SeaweedFS local y Amazon S3 privado en producción; destino de despliegue AWS. Consulte [docs/s3-receipts.md](docs/s3-receipts.md).
* **Perfiles de Desarrollo:** `dev` permite el bypass explícito de seguridad; `local` integra el frontend externo con `X-Dev-User` de usuario activo o JWT (Bearer tiene prioridad), conservando roles. Keycloak local en `http://localhost:8180/realms/dental-platform`, solo desarrollo; nunca combine estos perfiles con producción. Consulte [docs/keycloak-local.md](docs/keycloak-local.md).

## 3. Contexto del Ecosistema Distribuido (Para Integración por Eventos)
Este microservicio forma parte de una plataforma odontológica compuesta por 3 microservicios independientes que se comunican por **RabbitMQ** usando `UUID` compartidos:
1. **MS1 (`dental-erp-ms1` - ESTE SERVICIO):** Dueño de Configuración de Empresa, Usuarios/Roles, Pacientes (datos demográficos/CI/NIT), Odontólogos (datos base y especialidades), Consultorios físicos, Catálogo de Servicios/Arancel (Precios en Bs), Órdenes de Cobro, Planes de Pago (Crédito en cuotas), Pagos QR PagoFácil, Flujo de Caja (Ingresos/Egresos) y Recibos.
2. **MS2 (Clínica - Externo):** Maneja historias clínicas, alergias/hábitos y odontograma. Cuando el médico termina de atender, MS2 emite el evento `clinica.consulta.finalizada` hacia RabbitMQ. **Este MS1 debe escuchar ese evento** para crear automáticamente la `OrdenCobro` en estado `PENDIENTE`.
3. **MS3 (Agenda y Logística - Externo):** Maneja horarios de médicos, citas e inventario. **Este MS1 debe emitir eventos** (`catalogo.paciente.creado`, `catalogo.odontologo.creado`, `catalogo.consultorio.creado`) cuando se registren estas entidades para que MS2 y MS3 sincronicen sus `UUID`. Las actualizaciones y bajas lógicas emiten `catalogo.paciente.actualizado`, `catalogo.odontologo.actualizado` y `catalogo.consultorio.actualizado`. Agregado, auditoría y outbox se confirman en la misma transacción; el dispatcher publica con confirmación y reintentos. Entrega al menos una vez: consumidores deduplican por `eventId`, sin garantía de orden global. Consulte [docs/catalog-outbox.md](docs/catalog-outbox.md).

## 4. Reglas Arquitectónicas Inquebrantables (DDD)
1. **Separación de Capas:** Cada Bounded Context (`configsecurity`, `mastercatalog`, `financebilling`) debe dividirse en:
   * `domain`: Entidades de dominio puras, Aggregate Roots, Value Objects (`Money`, etc.), eventos de dominio e interfaces de repositorio. **Prohibido importar anotaciones de Spring o JPA/Hibernate dentro de `domain.model`.**
   * `application`: Casos de uso (`UseCase`), comandos/queries y puertos de salida (`Port`).
   * `infrastructure`: Entidades JPA (`*JpaEntity`), repositorios Spring Data, adaptadores RabbitMQ, cliente HTTP PagoFácil, cliente n8n, generadores PDF/Excel y adaptador Spring AI.
   * `presentation`: Controladores GraphQL (`@Controller` con `@QueryMapping` y `@MutationMapping`) y los dos controladores REST puntuales.
2. **Transaccionalidad ACID:** Todo cobro de cuota (Efectivo o confirmación QR) debe actualizar el estado de la cuota, descontar el saldo de la orden de cobro, registrar el ingreso en `movimientos_caja` y emitir el `recibo` dentro de una misma transacción `@Transactional`.
3. **Seguridad del Agente y Reportes:** `PromptInjectionGuardrail` actúa antes de procesar cualquier instrucción y bloquea jailbreak, «ignora las instrucciones anteriores» e intentos de saltar reglas financieras. Es defensa en profundidad: la frontera real es la autorización. Cada herramienta ejecuta con la identidad del solicitante y los mismos roles que la operación GraphQL equivalente. El modo autónomo nunca ejecuta escrituras CRUD; toda escritura pasa por casos de uso con `@Transactional` y `audit_logs`. El agente nunca ejecuta SQL salvo mediante `generarReporteDinamicoSqlTool`: `SqlReadOnlyGuardrail` valida AST/regex estricto, exclusivamente `SELECT`, y bloquea `INSERT`, `UPDATE`, `DELETE`, `DROP`, `ALTER`, `TRUNCATE`, sentencias múltiples con `;` y comentarios maliciosos. La ejecución usa `@Transactional(readOnly = true)` sobre un datasource de reportes dedicado y de solo lectura.
4. **Co-Piloto & Monitor de Negocio ERP:** `ErpCopilotAgentUseCase` recibe lenguaje natural (texto o voz transcrita) o modo autónomo «Monitorea mi negocio». Invoca las herramientas de `ErpMcpToolsService`:

   | Grupo | Herramientas y alcance |
   |---|---|
   | Monitoreo/lectura DDD | Métricas financieras, flujo de caja y pacientes con cuotas vencidas mediante repositorios/casos de uso del dominio. |
   | CRUD, 100% vía casos de uso | `crearServicioOdontologicoTool`, `registrarEgresoCajaTool`, `reprogramarPlanPagosOAnularDescuentoTool` (cuotas/planes pendientes), `anularOrdenCobroPendienteTool` (baja lógica a `ANULADO`); nunca SQL directo. |
   | Reportes | `generarReporteDinamicoSqlTool`: Spring AI Text-to-SQL protegido; caché temporal por `reporteId` UUID, columnas/filas JSON, gráfico `BARRAS|LINEAS|TORTA|TABLA` y URLs de descarga Excel/PDF. |
   | Cobranza | `dispararAlertaCobranzaN8nTool`: webhook n8n para recordatorios WhatsApp con datos de cuota o QR PagoFácil al detectar morosidad o por orden del administrador. |

5. **Proveedor y Respaldo:** `AI_PROVIDER=openai` (predeterminado) o `gemini` mediante su URL base compatible con OpenAI, con las claves y modelos de sus respectivas variables de entorno. Sin clave del proveedor seleccionado, incluso en `prod`, el agente usa el planificador determinístico existente, identificado en **cada respuesta** como respaldo no IA; nunca una respuesta de IA inventada. El respaldo conserva autorizaciones y guardrails. Consulte [docs/ai-providers.md](docs/ai-providers.md).

## 5. Plan de Ejecución por Fases (Orden Estricto para el Agente)
* **Fase 1 (Infraestructura y Base de Datos):** Crear `pom.xml`, `docker-compose.yml` (PostgreSQL 18, RabbitMQ 4, SeaweedFS compatible con S3) y las migraciones Flyway `V1__init_erp_schema.sql` y `V2__seed_bolivia_data.sql` (con datos realistas de Santa Cruz de la Sierra, Bolivia, en Bolivianos `Bs`).
* **Fase 2 (Shared Kernel y Contexto 1 - Configuración/Seguridad):** Implementar `Money`, filtro de `Idempotency-Key`, perfiles de desarrollo `dev` y `local`, Keycloak local y el módulo de Empresa, Usuarios, Roles, Permisos y Logs.
* **Fase 3 (Contexto 2 - Catálogos Maestros y Publicación RabbitMQ):** Implementar Pacientes, Odontólogos, Especialidades, Consultorios y Servicios Odontológicos, con outbox transaccional para eventos de creación, actualización y baja lógica por RabbitMQ. Mantener las 11 operaciones `actualizar*`/`eliminar*` de usuarios y catálogos descritas en SPECS.md.
* **Fase 4 (Contexto 3 - Finanzas, Cuotas, QR PagoFácil, Caja y Saga):** Implementar el listener de `clinica.consulta.finalizada`, creación de órdenes de cobro, fraccionamiento en cuotas (Contado/Crédito), adaptador PagoFácil QR (con modo Mock configurable), flujo de caja, emisión de recibos en PDF y notificación webhook a n8n.
* **Fase 5 (Agente ERP, MCP y Reportes Unificados):** Consolidar la IA en `ErpCopilotAgentUseCase` y `ErpMcpToolsService` con servidor MCP real, guardrails, autorización del solicitante, monitoreo sin CRUD autónomo, reportes Text-to-SQL de solo lectura, exportación Excel/PDF y alertas n8n. Eliminar el flujo separado `generarReporteInteligente`; contrato en SPECS.md.

## 6. Comandos de Verificación
* Compilar y verificar: `./mvnw clean compile`
* Ejecutar pruebas: `./mvnw test`
* Levantar entorno local: `docker compose up -d && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`