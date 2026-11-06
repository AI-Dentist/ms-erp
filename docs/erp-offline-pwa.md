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
