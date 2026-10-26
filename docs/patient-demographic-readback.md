# Leer y conservar los datos demográficos del paciente

`Paciente` expone `fechaNacimiento: Date` y `genero: String`, ambos nullable, en las respuestas de `crearPaciente`, `actualizarPaciente`, `listarPacientes` y `obtenerPacientePorId`. Devuelve los valores persistidos, no valores calculados ni predeterminados. Las selecciones antiguas siguen siendo válidas.

## Camino rápido: crear → leer → reenviar

Todos los datos siguientes son sintéticos. El UUID de las respuestas es ilustrativo: sustituirlo por el devuelto al crear. Son documentos y variables para enviar a GraphQL con la autenticación habitual, no llamadas a un entorno compartido.

### 1. Crear

```graphql
mutation CrearDemografia($input: CrearPacienteInput!) {
  crearPaciente(input: $input) { id fechaNacimiento genero }
}
```

Variables:

```json
{"input":{"ciNit":"SYN-DEMO-001","nombres":"Synthetic","apellidos":"Fixture","telefonoWhatsapp":"+59170000000","email":null,"direccion":null,"fechaNacimiento":"1992-02-29","genero":"Autodescrito"}}
```

Respuesta ilustrativa:

```json
{"data":{"crearPaciente":{"id":"33333333-3333-4333-8333-333333333333","fechaNacimiento":"1992-02-29","genero":"Autodescrito"}}}
```

`Date` usa `yyyy-MM-dd`; `genero` es texto libre, no un enum. El ejemplo muestra deliberadamente un valor distinto de una lista cerrada de géneros.

### 2. Leer antes de editar

```graphql
query LeerParaEditar($id: UUID!) {
  obtenerPacientePorId(id: $id) {
    id ciNit nombres apellidos telefonoWhatsapp email direccion fechaNacimiento genero
  }
}
```

Variables:

```json
{"id":"33333333-3333-4333-8333-333333333333"}
```

Respuesta ilustrativa:

```json
{"data":{"obtenerPacientePorId":{"id":"33333333-3333-4333-8333-333333333333","ciNit":"SYN-DEMO-001","nombres":"Synthetic","apellidos":"Fixture","telefonoWhatsapp":"+59170000000","email":null,"direccion":null,"fechaNacimiento":"1992-02-29","genero":"Autodescrito"}}}
```

El listado permite las mismas selecciones:

```graphql
query ListarDemografia($busqueda: String) {
  listarPacientes(busqueda: $busqueda) { id fechaNacimiento genero }
}
```

Variables:

```json
{"busqueda":"SYN-DEMO-001"}
```

Respuesta ilustrativa:

```json
{"data":{"listarPacientes":[{"id":"33333333-3333-4333-8333-333333333333","fechaNacimiento":"1992-02-29","genero":"Autodescrito"}]}}
```

### 3. Cambiar el nombre conservando los demás valores leídos

```graphql
mutation ReenviarDemografia($id: UUID!, $input: ActualizarPacienteInput!) {
  actualizarPaciente(id: $id, input: $input) { id nombres fechaNacimiento genero }
}
```

Variables (reenviar los valores leídos, incluidos los opcionales):

```json
{"id":"33333333-3333-4333-8333-333333333333","input":{"ciNit":"SYN-DEMO-001","nombres":"Synthetic edited","apellidos":"Fixture","telefonoWhatsapp":"+59170000000","email":null,"direccion":null,"fechaNacimiento":"1992-02-29","genero":"Autodescrito"}}
```

Respuesta ilustrativa:

```json
{"data":{"actualizarPaciente":{"id":"33333333-3333-4333-8333-333333333333","nombres":"Synthetic edited","fechaNacimiento":"1992-02-29","genero":"Autodescrito"}}}
```

## Null y reemplazo: no es un PATCH

El comportamiento previo de `actualizarPaciente` no cambia: reemplaza los campos del input. Omitir `fechaNacimiento` o `genero` los borra, igual que enviar `null`. No se añade ninguna regla de conservación implícita. Para conservarlos, leer y reenviar; no enviar `id` ni `activo` dentro del input.

Con la misma mutación anterior, estas variables borran ambos valores:

```json
{"id":"33333333-3333-4333-8333-333333333333","input":{"ciNit":"SYN-DEMO-001","nombres":"Synthetic edited","apellidos":"Fixture","telefonoWhatsapp":"+59170000000","email":null,"direccion":null,"fechaNacimiento":null,"genero":null}}
```

Respuesta ilustrativa:

```json
{"data":{"actualizarPaciente":{"id":"33333333-3333-4333-8333-333333333333","nombres":"Synthetic edited","fechaNacimiento":null,"genero":null}}}
```

La creación con esos campos omitidos o explícitamente `null` también devuelve `null`; listado y detalle preservan ese resultado. No se asignan fechas ni géneros por defecto.

## Permisos y contrato canónico

- Lectura, sin cambios: ADMIN, RECEPCIONISTA, CAJERO y ODONTOLOGO.
- Crear/actualizar, sin cambios: ADMIN y RECEPCIONISTA. Los otros roles no adquieren permisos de escritura; los rechazos no modifican el paciente.
- No cambian filtrado de activos, baja lógica, validaciones, auditoría ni outbox.
- El único cambio de producción son dos campos nullable del SDL; los inputs y la persistencia ya existían. No hay migraciones ni resolvers nuevos.
- `docs/graphql/schema.graphqls` y `docs/graphql/introspection.json` son los artefactos canónicos generados desde el esquema de la aplicación por `SchemaExportTest` con `-Dcontract.regenerate=true`. No editar introspección a mano.

Los documentos y variables del [contrato comparado](frontend-contract-diff.md) se validan mediante `FrontendExamplesValidationTest`; `PatientDemographicsGraphQLIntegrationTest` verifica persistencia, null, reenvío, compatibilidad y permisos con pacientes sintéticos en PostgreSQL embebido aislado.

## Verificación reproducible

Con JDK 25, ejecute `./mvnw test`. La suite usa PostgreSQL embebido aislado;
no necesita datos compartidos. `SchemaExportTest` compara los artefactos canónicos
sin regenerarlos por defecto; los ejemplos y permisos se verifican con las pruebas
indicadas arriba.
