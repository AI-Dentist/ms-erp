# AGENTS.md — Directrices Operativas para el Agente de Código

## 1. Identidad del Proyecto
* **Nombre del Servicio:** `ms-erp` (Microservicio 1: Core de Gestión Empresarial, Configuración y Finanzas).
* **Alcance Exclusivo:** Este repositorio contiene **ÚNICAMENTE el Backend del Microservicio 1**. No generes código de frontend (HTML/JS/TS/Angular/React) en este repositorio.
* **Paquete Base Java:** `com.dentalplatform.erp`

## 2. Stack Tecnológico Obligatorio (Últimas Versiones Estables)
* **Lenguaje:** Java 25 (LTS) con Virtual Threads habilitados (`spring.threads.virtual.enabled=true`).
* **Framework Base:** Spring Boot 3.5+ / 4.0.
* **Arquitectura Interna:** Domain-Driven Design (DDD) estricto dividido en 3 Bounded Contexts (`configsecurity`, `mastercatalog`, `financebilling`).
* **Capa de Exposición Principal:** Spring for GraphQL (`schema.graphqls`).
* **Excepciones REST Permitidas:** 
  1. `POST /api/v1/webhooks/pagofacil` (Recepción de confirmación de cobro QR Bolivia).
  2. `GET /api/v1/reports/export/{format}` (Descarga de binarios PDF y Excel `.xlsx` generados bajo demanda).
* **Base de Datos:** PostgreSQL 18 con migraciones automáticas vía **Flyway**.
* **Mensajería Asíncrona (Patrón Saga):** Spring AMQP con **RabbitMQ 4.x**.
* **Inteligencia Artificial (Reportes Generativos):** Spring AI 1.1+ (Text-to-SQL de solo lectura para generar reportes dinámicos desde lenguaje natural).
* **Seguridad e Idempotencia:** Spring Security 6.4+ (OAuth2 Resource Server para JWT de Keycloak) + Filtro de Idempotencia mediante cabecera `Idempotency-Key`.
* **Exportación de Documentos:** Apache POI (Excel `.xlsx`) y OpenPDF / JasperReports (Recibos y Reportes en PDF).

## 3. Contexto del Ecosistema Distribuido (Para Integración por Eventos)
Este microservicio forma parte de una plataforma odontológica compuesta por 3 microservicios independientes que se comunican por **RabbitMQ** usando `UUID` compartidos:
1. **MS1 (`dental-erp-ms1` - ESTE SERVICIO):** Dueño de Configuración de Empresa, Usuarios/Roles, Pacientes (datos demográficos/CI/NIT), Odontólogos (datos base y especialidades), Consultorios físicos, Catálogo de Servicios/Arancel (Precios en Bs), Órdenes de Cobro, Planes de Pago (Crédito en cuotas), Pagos QR PagoFácil, Flujo de Caja (Ingresos/Egresos) y Recibos.
2. **MS2 (Clínica - Externo):** Maneja historias clínicas, alergias/hábitos y odontograma. Cuando el médico termina de atender, MS2 emite el evento `clinica.consulta.finalizada` hacia RabbitMQ. **Este MS1 debe escuchar ese evento** para crear automáticamente la `OrdenCobro` en estado `PENDIENTE`.
3. **MS3 (Agenda y Logística - Externo):** Maneja horarios de médicos, citas e inventario. **Este MS1 debe emitir eventos** (`catalogo.paciente.creado`, `catalogo.odontologo.creado`, `catalogo.consultorio.creado`) cuando se registren estas entidades para que MS2 y MS3 sincronicen sus `UUID`.

## 4. Reglas Arquitectónicas Inquebrantables (DDD)
1. **Separación de Capas:** Cada Bounded Context (`configsecurity`, `mastercatalog`, `financebilling`) debe dividirse en:
   * `domain`: Entidades de dominio puras, Aggregate Roots, Value Objects (`Money`, etc.), eventos de dominio e interfaces de repositorio. **Prohibido importar anotaciones de Spring o JPA/Hibernate dentro de `domain.model`.**
   * `application`: Casos de uso (`UseCase`), comandos/queries y puertos de salida (`Port`).
   * `infrastructure`: Entidades JPA (`*JpaEntity`), repositorios Spring Data, adaptadores RabbitMQ, cliente HTTP PagoFácil, cliente n8n, generadores PDF/Excel y adaptador Spring AI.
   * `presentation`: Controladores GraphQL (`@Controller` con `@QueryMapping` y `@MutationMapping`) y los dos controladores REST puntuales.
2. **Transaccionalidad ACID:** Todo cobro de cuota (Efectivo o confirmación QR) debe actualizar el estado de la cuota, descontar el saldo de la orden de cobro, registrar el ingreso en `movimientos_caja` y emitir el `recibo` dentro de una misma transacción `@Transactional`.
3. **Seguridad en Reportes Generativos IA:** El caso de uso que convierte lenguaje natural a SQL debe validar mediante expresión regular y transacción ` readOnly = true ` que la consulta generada sea exclusivamente un `SELECT`, bloqueando cualquier intento de `DROP`, `DELETE`, `UPDATE`, `INSERT` o `ALTER`.

## 5. Plan de Ejecución por Fases (Orden Estricto para el Agente)
* **Fase 1 (Infraestructura y Base de Datos):** Crear `pom.xml`, `docker-compose.yml` (PostgreSQL 18, RabbitMQ 4, MinIO) y las migraciones Flyway `V1__init_erp_schema.sql` y `V2__seed_bolivia_data.sql` (con datos realistas de Santa Cruz de la Sierra, Bolivia, en Bolivianos `Bs`).
* **Fase 2 (Shared Kernel y Contexto 1 - Configuración/Seguridad):** Implementar `Money`, filtro de `Idempotency-Key`, perfil de seguridad local (`dev`) y el módulo de Empresa, Usuarios, Roles, Permisos y Logs.
* **Fase 3 (Contexto 2 - Catálogos Maestros y Publicación RabbitMQ):** Implementar Pacientes, Odontólogos, Especialidades, Consultorios y Servicios Odontológicos, emitiendo sus eventos de sincronización por RabbitMQ.
* **Fase 4 (Contexto 3 - Finanzas, Cuotas, QR PagoFácil, Caja y Saga):** Implementar el listener de `clinica.consulta.finalizada`, creación de órdenes de cobro, fraccionamiento en cuotas (Contado/Crédito), adaptador PagoFácil QR (con modo Mock configurable), flujo de caja, emisión de recibos en PDF y notificación webhook a n8n.
* **Fase 5 (Reportes Generativos IA y Exportación):** Implementar el motor Text-to-SQL con Spring AI y la exportación dinámica a Excel (`.xlsx`) y PDF.

## 6. Comandos de Verificación
* Compilar y verificar: `./mvnw clean compile`
* Ejecutar pruebas: `./mvnw test`
* Levantar entorno local: `docker compose up -d && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`