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

Compose inicia PostgreSQL, RabbitMQ y MinIO con credenciales **solo locales**. Flyway aplica las migraciones al arrancar. GraphQL: `http://localhost:8081/graphql`; GraphiQL, únicamente en `dev`: `/graphiql`.

**No exponga `dev` a redes no confiables:** omite Keycloak y cada petición anónima actúa como `admin` con todos los roles. Fuera de `dev`, configure `KEYCLOAK_ISSUER_URI` y use JWT válidos. No reutilice credenciales de Compose en producción.

| Configuración | Variables |
|---|---|
| Base principal | `ERP_DB_URL`, `ERP_DB_USER`, `ERP_DB_PASSWORD` |
| RabbitMQ | `ERP_RABBIT_HOST`, `ERP_RABBIT_PORT`, `ERP_RABBIT_USER`, `ERP_RABBIT_PASSWORD` |
| Puerto HTTP | `ERP_PORT` (8081 por defecto) |
| IA | `OPENAI_API_KEY`, `ERP_AI_MODEL` (`gpt-4o-mini` por defecto) |
| Endpoint IA opcional | `SPRING_AI_OPENAI_BASE_URL`: URL base de API del proveedor compatible con OpenAI |
| Base de reportes | `ERP_REPORT_DB_USER`, `ERP_REPORT_DB_PASSWORD`; opcional `ERP_REPORT_DB_URL` |

Inyecte secretos mediante el mecanismo seguro del entorno; no los incluya en el repositorio ni en el historial de comandos.

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

## Verificación y límites de la evidencia

`./mvnw test` usa PostgreSQL 18 embebido y modelos/puertos simulados, sin llamadas reales a IA ni PagoFácil. Las pruebas cubren preguntas con fechas/categorías a través del adaptador, resultados SQL, permisos de lectura, rechazos sin cambios de filas/secuencias, propiedad/TTL y binarios PDF/XLSX.

No se ha probado aquí el proveedor IA real, JWT productivo, entrega RabbitMQ por red, Compose en ejecución ni el script de operador vía `psql`. La cota de 500 filas tiene prueba con JDBC simulado; el timeout de bloqueo sí se verifica contra PostgreSQL real. El hosting del proveedor no constituye un requisito adicional.
