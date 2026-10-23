# dental-erp-ms1

Backend ERP odontológico: configuración, catálogos, cobros y reportes financieros dinámicos. Java 25, Spring Boot 4, Spring AI 2, PostgreSQL 18 y RabbitMQ 4. Contratos: [SPECS.md](SPECS.md); arquitectura: [AGENTS.md](AGENTS.md).

## Inicio local

Requiere JDK 25, Docker con Compose y acceso a las dependencias Maven. En Git Bash para Windows:

```bash
export JAVA_HOME='C:\Program Files\Java\jdk-25'
export PATH="/c/Program Files/Java/jdk-25/bin:$PATH"
./mvnw clean compile
./mvnw test
docker compose up -d
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Compose inicia PostgreSQL, RabbitMQ y SeaweedFS (emulador S3 local; crea el bucket privado `erp-receipts` con `seaweedfs-init`) con credenciales **solo locales**. Flyway aplica las migraciones al arrancar. GraphQL: `http://localhost:8081/graphql`; GraphiQL, únicamente en `dev`: `/graphiql`.

**No exponga `dev` a redes no confiables:** omite Keycloak y cada petición anónima actúa como `admin` con todos los roles. Este bypass requiere la configuración explícita existente `erp.security.dev-mode=true` (el perfil `dev` la activa); no la habilite en despliegues protegidos. Por defecto, o con `false`, se exige JWT. No reutilice credenciales de Compose en producción.

Fuera de `dev`, configure `KEYCLOAK_ISSUER_URI` con el issuer exacto del realm. El servidor consulta su discovery/JWKS y valida firma, issuer y expiración; necesita conectividad al issuer. Los roles proceden de `realm_access.roles`, el nombre principal de `preferred_username` y la identidad de Keycloak de `sub`. El contrato actual no impone un audience adicional. Envíe `Authorization: Bearer <token>`; token ausente/inválido devuelve 401. Rol insuficiente devuelve 403 en REST; GraphQL mantiene HTTP 200 con error `FORBIDDEN`. Compose no aprovisiona Keycloak ni un realm.

| Configuración | Variables |
|---|---|
| Base principal | `ERP_DB_URL`, `ERP_DB_USER`, `ERP_DB_PASSWORD` |
| RabbitMQ | `ERP_RABBIT_HOST`, `ERP_RABBIT_PORT`, `ERP_RABBIT_USER`, `ERP_RABBIT_PASSWORD` |
| Puerto HTTP | `ERP_PORT` (8081 por defecto) |
| IA | `OPENAI_API_KEY`, `ERP_AI_MODEL` (`gpt-4o-mini` por defecto) |
| Endpoint IA opcional | `SPRING_AI_OPENAI_BASE_URL`: URL base de API del proveedor compatible con OpenAI |
| Base de reportes | `ERP_REPORT_DB_USER`, `ERP_REPORT_DB_PASSWORD`; opcional `ERP_REPORT_DB_URL` |

Inyecte secretos mediante el mecanismo seguro del entorno; no los incluya en el repositorio ni en el historial de comandos.

## Integración local con el frontend (perfil local)

Perfil de **solo desarrollo** para el microfrontend Angular (`http://localhost:4201`). Nunca se usa en producción.

```bash
docker compose up -d
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

- GraphQL: `http://localhost:8080/graphql` (GraphiQL habilitado). Puerto: `ERP_PORT` (por defecto 8080).
- Por defecto apunta a los servicios del `docker-compose.yml` (PostgreSQL `localhost:5432`, RabbitMQ `localhost:5672`, SeaweedFS S3 `http://localhost:8333`, bucket `erp-receipts`) con credenciales dev. Variables opcionales: `ERP_DB_URL|USER|PASSWORD`, `ERP_RABBIT_HOST|PORT|USER|PASSWORD`, `ERP_RECEIPTS_MINIO_*`, `PAGOFACIL_WEBHOOK_SECRET`, `ERP_CORS_ALLOWED_ORIGINS`. Ninguna es obligatoria.
- PagoFácil en modo mock, `simularConfirmacionQrDev` habilitado y n8n deshabilitado. La simulación exige la propiedad y el perfil `dev` o `local`, y se rechaza si hay un perfil `prod`.
- Los PDF de recibos se guardan en S3 y `Recibo.pdfUrlS3` devuelve una URL prefirmada descargable desde el navegador.
- Flyway carga también `classpath:db/local` (datos sintéticos); solo este perfil lo hace.
- **`X-Dev-User: <username>`**: debe ser un usuario `activo` de `usuarios` (p. ej. `admin` del seed). Solo se concede el rol de ese usuario (`ROLE_<codigo>`), de modo que `@PreAuthorize` sigue aplicando (un CAJERO no puede ejecutar mutaciones de ADMIN). Cabecera ausente, vacía, repetida, desconocida o inactiva → `401`. Si llega `Authorization: Bearer`, la cabecera se ignora y el JWT se valida contra `ERP_LOCAL_JWT_ISSUER_URI` (por defecto el Keycloak local `http://localhost:8180/realms/dental-platform`; vacío = Bearer deshabilitado). No existe el administrador anónimo del perfil `dev`.
- **Keycloak local (solo desarrollo):** `docker compose up -d keycloak` (imagen fija 26.8.1, realm `dental-platform` importado de `docker/keycloak/`, loopback `:8180`). Usuarios `admin-scz`/`caja-scz`/`recepcion-scz` con contraseña de desarrollo `LocalDev2026!`, cliente público PKCE `mf-erp` para `http://localhost:4201` y cliente de pruebas `ms1-local-tests` (*password grant*, nunca en producción). El emisor se resuelve de forma perezosa: MS1 arranca con Keycloak caído y los Bearer reciben `401` hasta que responde. Detalle, claims y `curl` en [docs/keycloak-local.md](docs/keycloak-local.md); `scripts/local-smoke.sh` repite las comprobaciones de autorización con Bearer si el emisor responde.
- CORS: solo el origen `http://localhost:4201` (lista `erp.cors.allowed-origins`, vacía fuera de `local`, nunca `*`), métodos GET/POST/OPTIONS, cabeceras `Authorization, Content-Type, Accept, Idempotency-Key, X-Dev-User`; expone `Idempotency-Replayed` y `Content-Disposition`.
- Seguridad: la aplicación **no arranca** si `erp.security.local-dev-user.enabled=true` se combina con un perfil distinto de `local`, con `erp.security.dev-mode=true` o si `local` convive con `prod`/`production`. En la cadena por defecto `X-Dev-User` se ignora por completo.

## Reportes dinámicos y exportación

1. Tras aplicar V5, aprovisione un **nuevo login no propietario, exclusivo de lectura**, siguiendo [scripts/reporting-role.sql](scripts/reporting-role.sql). En una sesión administrativa `psql` protegida, suministre `report_user`, `report_password` y `app_user`, y ejecute explícitamente `\i scripts/reporting-role.sql`. Nunca se ejecuta automáticamente ni modifica contraseñas existentes.
2. Configure las credenciales `ERP_REPORT_DB_*`. El usuario debe ser distinto al principal y solo tener SELECT sobre las tres vistas aprobadas. Las URLs JDBC no pueden contener parámetros `user`/`password`. Sin credenciales seguras fallan los reportes, no el servicio completo.
3. Configure el proveedor Spring AI existente: OpenAI por defecto; endpoint/modelo configurables sin imponer alojamiento local o externo. Sin clave, GraphQL devuelve `Reportes IA no disponibles: configure el proveedor`; no hay respuestas ficticias.

La pregunta original (1–500 caracteres) se envía como mensaje de usuario no confiable junto al esquema permitido. **No incluya datos personales en preguntas.** La aplicación no adjunta filas, historiales ni registros de pacientes, y no registra preguntas ni payloads del proveedor. No habilite trazas de payloads/prompts en infraestructura externa.

Ejemplo ADMIN, mediante `POST /graphql`:

```graphql
query {
  generarReporteInteligente(
    pregunta: "Total de ingresos entre 2026-10-01 y 2026-10-31, categoría COBRO_CUOTA"
  ) {
    reporteId preguntaOriginal explicacionNatural consultaSqlEjecutada
    columnas filasJson tipoGraficoSugerido urlDescargaExcel urlDescargaPdf
  }
}
```

El modelo debe conservar fechas, categorías y métricas, por ejemplo:

```sql
SELECT SUM(amount_bs) FROM report_income WHERE day >= '2026-10-01' AND day <= '2026-10-31' AND category = 'COBRO_CUOTA'
```

El guard no confía en el prompt: solo permite un SELECT sobre `report_income`, `report_orders` o `report_services`, columnas aprobadas, agregados SUM/COUNT/AVG/MIN/MAX y filtros simples AND, GROUP BY, ORDER BY y LIMIT 1–500. No admite joins, CTE, subconsultas, funciones arbitrarias, comentarios, punto y coma ni modificaciones. SQL no soportado o respuesta `UNSUPPORTED` produce `Consulta de reporte no permitida`; nunca se reescribe para quitar restricciones. Las columnas y gramática detalladas están en el script de aprovisionamiento. Revise `consultaSqlEjecutada`: la interpretación de lenguaje natural depende del modelo; el guard valida seguridad, no equivalencia semántica.

La ejecución usa pool separado (2 conexiones), transacciones read-only, rollback, timeout de consulta de 3 s y máximo de 500 filas. El resultado queda en memoria: 64 reportes totales, TTL absoluto de 10 minutos, accesibles únicamente por su principal propietario ADMIN.

Descargue usando las URLs devueltas, sin ejecutar otra consulta:

- `GET /api/v1/reports/export/xlsx?reporteId=<UUID>`
- `GET /api/v1/reports/export/pdf?reporteId=<UUID>`

Los adjuntos usan MIME correspondiente, `Cache-Control: no-store` y `X-Content-Type-Options: nosniff`. Formato inválido: 400; ID ausente/inválido/desconocido, expirado o de otro propietario: 404 `Reporte no disponible`; rol insuficiente: 403. XLSX conserva importes numéricos y texto como celdas de texto, nunca fórmulas. No se guardan archivos.

## QR: protocolo LOCAL, no PagoFácil real

`dev` habilita simulación local. Configure `PAGOFACIL_WEBHOOK_SECRET` con un secreto propio no vacío; `change-me` se rechaza. El callback es:

```http
POST /api/v1/webhooks/pagofacil
Content-Type: application/json
X-Local-Mock-Secret: <secreto configurado>

{"transactionId":"MOCK-<UUID devuelto al generar QR>","amountBs":100.00,"confirmedAt":"2026-10-22T10:00:00-04:00"}
```

Use el ID e importe realmente almacenados y una fecha dentro de la vigencia del QR (tolerancia de reloj: 60 s). Reintentos del mismo ID confirmado retornan el mismo recibo; no dependen de `Idempotency-Key`. Secreto incorrecto: 401; datos inválidos: 400; ID desconocido: 404; expirado: 410. **El proveedor real no está implementado**: fuera de `dev`, `PAGOFACIL_MOCK_ENABLED=false` falla cerrado; el callback autenticado devuelve 503. Este formato/secreto no representa el protocolo ni firma oficial de PagoFácil.

## Idempotencia y resultados ambiguos

En POST con `Idempotency-Key` (1–100 caracteres), una respuesta exitosa se conserva y se reproduce para la misma ruta y cuerpo. Con JWT, también debe coincidir **issuer + subject + autoridades efectivas**: otro principal o un cambio de roles devuelve 422, nunca datos almacenados del usuario anterior. Un cambio solo de `preferred_username` no cambia la identidad. Otra solicitud coincidente en curso devuelve 409 y otro contenido devuelve 422. El comportamiento anónimo de `dev` no cambia. Las claves JWT anteriores a esta vinculación también pueden devolver 422; reconcilie su resultado antes de reintentar, sin eliminar automáticamente claims pendientes. El callback PagoFácil queda excluido: su deduplicación usa el ID de transacción y siempre requiere autenticación.

Solo las respuestas completadas caducan (24 horas por defecto). **Las claves en curso no caducan ni se recuperan automáticamente**, aunque venza ese plazo: un timeout o error servlet no demuestra que la mutación haya dejado de ejecutarse. La finalización servlet sin respuesta capturada también conserva la clave; la finalización posterior a una respuesta exitosa no elimina su replay. El listener se registra de nuevo en cada ciclo asíncrono.

Se mantiene el contrato existente: una respuesta fallida (incluidos errores GraphQL) o un fallo que se desenrolla por el filtro libera la clave. Esto no demuestra por sí solo que una mutación no haya hecho commit. Ante un resultado ambiguo, compruebe el estado de negocio y reconcilie manualmente antes de decidir un reintento; no use otra clave para eludir un 409. No hay un endpoint ni comando automático de reconciliación. El filtro no garantiza ejecución exactamente una vez tras un crash o commit de resultado desconocido.

## Recibos, eventos y n8n

- **PDF privados:** el modo local explícito guarda `file:` bajo el directorio temporal del sistema (no es una URL web). Para S3 configure `ERP_RECEIPTS_MODE=s3`: en local SeaweedFS con claves estáticas; en AWS `ERP_RECEIPTS_MINIO_PROVIDER=aws`, región obligatoria y rol IAM (sin claves estáticas), según [docs/s3-receipts.md](docs/s3-receipts.md). MinIO server ya no se distribuye como imagen pública. GraphQL entrega enlaces presignados solo a ADMIN/CAJERO; no se añade REST. Un fallo de PDF/storage no revierte el pago y no tiene recuperación durable automática: no repita el cobro para reparar un PDF.
- **Catálogos:** V6 registra los eventos en el outbox junto al agregado/auditoría. El dispatcher exige confirmación ACK sin retorno obligatorio y reintenta con backoff conservando `eventId`. Entrega al menos una vez, no exactamente una vez: consumidores deben deduplicar. Configuración y operación: [docs/catalog-outbox.md](docs/catalog-outbox.md).
- **n8n optativo:** `N8N_ENABLED=false` no realiza HTTP. Para activarlo configure `N8N_WEBHOOK_URL` en un destino de confianza. Envía solo evento, UUID y número de recibo, después del commit; no datos del paciente. Los timeouts `erp.n8n.connect-timeout-ms`/`read-timeout-ms` son 1000/2000 ms por defecto (acotados a 100–5000 ms). Un error/timeout se registra sin revertir cuota, saldo, movimiento ni recibo; no hay reintento durable. No habilite trazas de payloads.

Se mantienen GraphQL y las dos únicas rutas REST de negocio: callback local PagoFácil y exportación de reportes.

## Verificación y límites de la evidencia

`./mvnw test` no requiere Docker, Keycloak ni servicios externos: usa PostgreSQL 18 embebido, puertos IA/Rabbit simulados y listeners HTTP loopback reales para JWT/JWKS, SDK S3 y n8n. Requiere dependencias Maven/binaries ya descargados para ejecutarse sin red. No instala un modelo IA local ni llama al proveedor real PagoFácil.

| Frontera | Evidencia local | Pendiente de aceptación live |
|---|---|---|
| JWT | RSA efímera, discovery/JWKS HTTP, bearer contra cadena no-dev: firma/issuer/expiración 401; permisos REST/GraphQL; principal firmado; replay aislado por identidad/roles; rechazos sin cambios de filas/secuencias | Realm y despliegue Keycloak reales, rotación operacional |
| n8n | HTTP 500 y stall real; timeout configurado 300 ms, pago retorna antes de 4 s y conserva commit; deshabilitado sin HTTP | Servicio n8n desplegado, saturación sostenida |
| S3 | SDK real contra stub HTTP, firma, cuerpo, timeout, referencia persistida y autorización GraphQL; modo AWS: cadena de credenciales, región/endpoint y presign con proveedor simulado | SeaweedFS real (perfil live); AWS real, TLS/IAM del despliegue |
| RabbitMQ | PostgreSQL/outbox real; ACK/NACK/retorno/timeout/retry simulados | Wire real, caída/reanudación y saga por listener |
| Reportes | SQL, permisos, rechazos sin cambios, propiedad/TTL, PDF/XLSX y bloqueo PostgreSQL | Proveedor IA y script de operador vía `psql` |

Ejecución local T11: `./mvnw test` pasó **231/231** (0 fallos/errores/skips, incluye T9/T10/T11); `./mvnw clean compile` compiló **183 fuentes** con exit 0. El enfocado JWT/n8n/idempotencia pasó 25/25 y compiló también `LiveIntegrationsIT` con el perfil activo. `clean` elimina los XML previos de `target`; estos totales proceden de la salida observada, no de una ejecución live.

### Integraciones live optativas (no ejecutadas aquí)

Requiere Docker Engine accesible, JDK 25 y acceso a las imágenes/dependencias (o caché previa). No necesita Compose ni credenciales/puertos ambientales:

```bash
bash scripts/verify-integrations.sh
# Equivalente directo, sin preflight CLI:
./mvnw -Plive-integrations verify
```

El perfil ejecuta la suite ordinaria y Failsafe `LiveIntegrationsIT`: contenedores desechables RabbitMQ 4.1.4 y SeaweedFS 4.48 (S3) con puertos aleatorios y credenciales efímeras. Usa los puertos/dispatcher de la aplicación para upload, GET autenticado, GET presignado, denegación anónima, confirmación outbox, caída/reinicio del broker con retry y entrega saga. Solo controla sus propios contenedores; cierre al finalizar y Ryuk para recuperación. Arranque máximo 120 s por contenedor, comandos broker 30 s, esperas 20 s y fork 600 s. No utilice este harness contra servicios compartidos.

**Última ejecución live (Docker Desktop, SeaweedFS 4.48 + RabbitMQ 4.1.4):** Failsafe `LiveIntegrationsIT` 2/2 (upload S3 real, GET autenticado/presignado, denegación anónima 403; outbox con caída/reinicio del broker y saga). La ejecución de Failsafe se repitió tras corregir dos defectos del propio harness (modo de entrega recibido y espera de la fila de la saga). El script falla con exit 1 si falta Docker: no hay skip silencioso. Resultados ordinarios: `target/surefire-reports`; live: `target/failsafe-reports`. Esto no prueba AWS real ni preparación completa para producción.
