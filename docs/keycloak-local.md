# Keycloak local (solo desarrollo)

Identity provider **solo para desarrollo** que permite probar `Authorization: Bearer <JWT>` de extremo a extremo contra MS1 en el perfil `local`. Nunca se usa en producción: `start-dev` (H2, HTTP plano), contraseñas públicas y un cliente con *password grant*.

```bash
docker compose up -d keycloak          # primer arranque ~1.5 min (augmentation + import del realm)
docker compose ps keycloak             # esperar "healthy"
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

- Imagen fija `quay.io/keycloak/keycloak:26.8.1`, publicada solo en loopback: `http://localhost:8180`.
- Realm importado de `docker/keycloak/dental-platform-realm.json` (montado de solo lectura; la importación ignora un realm que ya exista, así que un cambio del JSON exige `docker compose rm -sf keycloak && docker compose up -d keycloak`; no hay volumen, el estado vive en el contenedor).
- Consola de administración `http://localhost:8180/admin` (realm `master`): `admin` / `admin-local-only`. Son valores **solo locales**; se sobrescriben con `KEYCLOAK_ADMIN` y `KEYCLOAK_ADMIN_PASSWORD`.

## Realm `dental-platform`

| Elemento | Valor |
|---|---|
| Issuer | `http://localhost:8180/realms/dental-platform` |
| Roles de realm | `ADMIN`, `CAJERO`, `RECEPCIONISTA`, `ODONTOLOGO` (igual a `roles.codigo` de MS1) |
| Duración del access token | 15 min |

Usuarios sintéticos (mismos `username` que la semilla `R__local_integration_seed.sql`; un rol cada uno; correo `@local.invalid`, `emailVerified`, sin acciones requeridas). **Contraseña de desarrollo de los tres: `LocalDev2026!`.**

| Usuario | Rol |
|---|---|
| `admin-scz` | `ADMIN` |
| `caja-scz` | `CAJERO` |
| `recepcion-scz` | `RECEPCIONISTA` |

Los tokens llevan `preferred_username` y `realm_access.roles` (lo que lee MS1) y `aud: ms-erp` (mapper de audiencia; **MS1 no valida la audiencia**, queda preparado para hacerlo más adelante).

## Clientes

| Client ID | Uso | Configuración |
|---|---|---|
| `mf-erp` | Angular `http://localhost:4201` | público, *authorization code* + **PKCE S256 obligatorio**; redirect URIs `http://localhost:4201/*`, web origins `http://localhost:4201`; sin *direct access grants*, sin implícito, sin service account |
| `ms1-local-tests` | pruebas locales de desarrollo | público, **solo *password grant*** (*direct access grants*). **SOLO DESARROLLO/PRUEBAS: nunca importar ni habilitar en producción** |

## Login del frontend (PKCE)

- Authority / issuer: `http://localhost:8180/realms/dental-platform` (discovery en `/.well-known/openid-configuration`).
- `clientId`: `mf-erp`; `responseType`: `code`; `scope`: `openid profile` (los roles viajan en el access token por el scope `roles`, incluido por defecto).
- `redirectUri` / `post_logout_redirect_uri`: `http://localhost:4201/` (cualquier ruta bajo ese origen).
- Sin `code_challenge_method=S256` Keycloak responde `error=invalid_request` (verificado). Con el mismo cliente el *password grant* se rechaza (`unauthorized_client`).
- Enviar el access token a MS1 como `Authorization: Bearer <token>` en `http://localhost:8080/graphql`.

## Token para `curl`

```bash
TOKEN=$(curl -s -d grant_type=password -d client_id=ms1-local-tests -d username=admin-scz \
  --data-urlencode 'password=LocalDev2026!' -d scope=openid \
  http://localhost:8180/realms/dental-platform/protocol/openid-connect/token | python -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')
curl -s http://localhost:8080/graphql -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
  --data '{"query":"{ listarUsuarios { username } }"}'
```

## Comportamiento de MS1 (perfil `local`)

- Emisor: `ERP_LOCAL_JWT_ISSUER_URI` (por defecto el issuer de arriba; vacío = Bearer deshabilitado, `401`).
- El emisor se resuelve **de forma perezosa**: MS1 arranca con Keycloak caído y los Bearer reciben `401` (no `500`) hasta que Keycloak responde; no hace falta reiniciar MS1. Una vez descargadas las claves, un Keycloak que se detiene no invalida las claves ya cacheadas.
- Un Bearer presente **siempre** gana: `X-Dev-User` se ignora, y un Bearer inválido nunca cae en `X-Dev-User`. Sin Bearer sigue funcionando `X-Dev-User`.
- Roles: se toman del claim `realm_access.roles` (`ROLE_<ROL>`), igual que el rol de BD con `X-Dev-User` mientras el realm y la tabla `usuarios` coincidan para el mismo `username`. Las comprobaciones `@PreAuthorize` son idénticas (`caja-scz` → `listarUsuarios` = `FORBIDDEN` por ambas vías).
- Auditoría: el actor se resuelve por `keycloak_id` (subject) o, si no existe, por `preferred_username` → el usuario de BD (`admin-scz`); la semilla no rellena `keycloak_id`.
- La cadena de producción (`KEYCLOAK_ISSUER_URI`, sin `X-Dev-User`) no cambia.
