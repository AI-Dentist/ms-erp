#!/usr/bin/env bash
# Live smoke test of MS1 (profile `local`) over HTTP. DEV ONLY, never point it at production.
# - Creates ITS OWN order/patient/egreso; the seeded cuotas (a9...) are never paid.
# - Read-only SQL (docker exec erp-postgres psql) and RabbitMQ management API are optional; checks that need
#   them print SKIP when unavailable. The only broker object created is a temporary queue named
#   erp-smoke-tmp-<id>, deleted on exit.
# Env: MS1_URL (default http://localhost:8080), DEV_USER (default admin-scz), RABBIT_API (default http://localhost:15672),
#      RABBIT_USER/RABBIT_PASS (compose dev defaults), ERP_PG_CONTAINER (default erp-postgres).
# Optional section 11 (Bearer JWT from the local Keycloak: docker compose up -d keycloak) runs only when KEYCLOAK_ISSUER
#      (default http://localhost:8180/realms/dental-platform) answers OIDC discovery; otherwise SKIP. Tokens come from the
#      DEV-ONLY password-grant client KEYCLOAK_TEST_CLIENT (default ms1-local-tests) / KEYCLOAK_TEST_PASSWORD (dev default).
# Requires: bash, curl, python (3) for JSON handling (jq is not needed). Exit code: 0 all PASS, 1 any FAIL.
set -euo pipefail

BASE="${MS1_URL:-http://localhost:8080}"
DEV_USER="${DEV_USER:-admin-scz}"
ORIGIN="http://localhost:4201"
RABBIT_API="${RABBIT_API:-http://localhost:15672}"
RABBIT_USER="${RABBIT_USER:-erp_user}"
RABBIT_PASS="${RABBIT_PASS:-erp_password}"
PG_CONTAINER="${ERP_PG_CONTAINER:-erp-postgres}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CT=(--connect-timeout 3 --max-time 20)

PY=python; command -v python >/dev/null 2>&1 || PY=python3
py() { "$PY" -I -X utf8 "$@" | tr -d '\r'; }
[ -d "/c/Program Files/Docker/Docker/resources/bin" ] && PATH="$PATH:/c/Program Files/Docker/Docker/resources/bin"

TMP="$(mktemp -d)"
TMPQ=""
cleanup() {
  if [ -n "$TMPQ" ]; then
    curl -s "${CT[@]}" -u "$RABBIT_USER:$RABBIT_PASS" -X DELETE "$RABBIT_API/api/queues/%2F/$TMPQ" >/dev/null 2>&1 || true
  fi
  rm -rf "$TMP"
}
trap cleanup EXIT

PASS=0; FAIL=0; SKIP=0
ok()   { PASS=$((PASS+1)); printf 'PASS  %s\n' "$1"; [ -n "${2:-}" ] && printf '      %s\n' "$2" || true; }
bad()  { FAIL=$((FAIL+1)); printf 'FAIL  %s\n' "$1"; [ -n "${2:-}" ] && printf '      %s\n' "$2" || true; }
skip() { SKIP=$((SKIP+1)); printf 'SKIP  %s  (%s)\n' "$1" "${2:-no disponible}"; }
note() { printf '      note: %s\n' "$1"; }
section() { printf '\n== %s ==\n' "$1"; }
# eq NAME ACTUAL EXPECTED
eq() { if [ "$2" = "$3" ]; then ok "$1" "= $2"; else bad "$1" "expected [$3] got [$2]"; fi; }
# has NAME HAYSTACK NEEDLE
has() { if printf '%s' "$2" | grep -qF -- "$3"; then ok "$1" "contains [$3]"; else bad "$1" "[$3] not in [${2:0:300}]"; fi; }

uuid() { py -c 'import uuid;print(uuid.uuid4())'; }
# J EXPR  (reads JSON from stdin as `d`; prints strings raw, other values as JSON; empty on error)
J() { py -c 'import sys,json
try:
    d=json.load(sys.stdin); r=eval(sys.argv[1])
    print(r if isinstance(r,str) else json.dumps(r,ensure_ascii=False))
except Exception:
    print("")' "$1"; }

STATUS=""; BODY=""; HDR=""
# gql USER KEY QUERY VARS_JSON -> STATUS BODY HDR (KEY may be "-" for none; USER "-" for no X-Dev-User)
gql() {
  local user="$1" key="$2" q="$3" v="${4:-{\}}" args=()
  Q="$q" V="$v" py -c 'import os,json;print(json.dumps({"query":os.environ["Q"],"variables":json.loads(os.environ["V"])},ensure_ascii=False))' >"$TMP/req.json"
  [ "$user" != "-" ] && args+=(-H "X-Dev-User: $user")
  [ -n "${BEARER:-}" ] && args+=(-H "Authorization: Bearer $BEARER")
  [ "$key" != "-" ] && args+=(-H "Idempotency-Key: $key")
  STATUS=$(curl -s "${CT[@]}" -o "$TMP/body.json" -D "$TMP/hdr.txt" -w '%{http_code}' -X POST "$BASE/graphql" \
    -H 'Content-Type: application/json' -H "Origin: $ORIGIN" "${args[@]}" --data-binary @"$TMP/req.json" || echo 000)
  BODY=$(cat "$TMP/body.json" 2>/dev/null || true); HDR=$(cat "$TMP/hdr.txt" 2>/dev/null || true)
}
hdr_has() { printf '%s' "$HDR" | tr -d '\r' | grep -qi "^$1: *$2\$"; }
errmsg() { printf '%s' "$BODY" | J 'd["errors"][0]["message"]'; }
errcls() { printf '%s' "$BODY" | J 'd["errors"][0]["extensions"]["classification"]'; }

PSQL_OK=0
if command -v docker >/dev/null 2>&1 && docker exec "$PG_CONTAINER" true >/dev/null 2>&1; then PSQL_OK=1; fi
psql_q() { MSYS_NO_PATHCONV=1 docker exec "$PG_CONTAINER" psql -U erp_user -d dental_erp -Atc "$1"; }

RUN_ID="$(date +%Y%m%d%H%M%S)-$RANDOM"
echo "MS1 smoke  base=$BASE  user=$DEV_USER  run=$RUN_ID  date=$(date -Iseconds)"
code=$(curl -s "${CT[@]}" -o /dev/null -w '%{http_code}' "$BASE/actuator/health" || echo 000)
[ "$code" = 200 ] || { echo "FATAL: $BASE/actuator/health -> $code (is MS1 running?)"; exit 2; }

# Seeded fixtures (docs/local-integration-data.md)
PAC=a5000000-0000-0000-0000-000000000005
ODO=a3000000-0000-0000-0000-000000000001
SVC1=a6000000-0000-0000-0000-000000000001   # Exodoncia de Tercer Molar Retenido 1250.00
SVC2=a6000000-0000-0000-0000-000000000002   # Restauración Fotopolimerizable con Resina 280.00
CAJERO=a1000000-0000-0000-0000-000000000002
SEED_CREDIT=a7000000-0000-0000-0000-000000000002

############################################################################
section "1. CORS preflight"
pre() { # URL ORIGIN METHOD HEADERS -> prints "status|allow-origin"
  curl -s "${CT[@]}" -o /dev/null -D "$TMP/pre.txt" -w '%{http_code}' -X OPTIONS "$1" -H "Origin: $2" \
    -H "Access-Control-Request-Method: $3" -H "Access-Control-Request-Headers: $4" >"$TMP/pre.code" || echo 000 >"$TMP/pre.code"
  printf '%s|%s' "$(cat "$TMP/pre.code")" "$(tr -d '\r' <"$TMP/pre.txt" | grep -i '^access-control-allow-origin:' | sed 's/^[^:]*: *//' || true)"
}
H4='content-type,idempotency-key,x-dev-user,authorization'
r=$(pre "$BASE/graphql" "$ORIGIN" POST "$H4"); eq "OPTIONS /graphql allowed origin -> 200" "${r%%|*}" 200; eq "OPTIONS /graphql Allow-Origin exact" "${r#*|}" "$ORIGIN"
allow_h=$(tr -d '\r' <"$TMP/pre.txt" | grep -i '^access-control-allow-headers:' || true)
for h in idempotency-key x-dev-user authorization content-type; do has "preflight allows header $h" "$(printf '%s' "$allow_h" | tr 'A-Z' 'a-z')" "$h"; done
r=$(pre "$BASE/graphql" "http://evil.example" POST "$H4")
if [ -z "${r#*|}" ] && { [ "${r%%|*}" = 403 ] || [ "${r%%|*}" = 200 ]; }; then ok "OPTIONS /graphql evil origin: no Allow-Origin" "status ${r%%|*}"; else bad "OPTIONS /graphql evil origin" "$r"; fi
r=$(pre "$BASE/api/v1/reports/export/00000000-0000-0000-0000-000000000000/pdf" "$ORIGIN" GET "authorization,x-dev-user"); eq "OPTIONS export/pdf status" "${r%%|*}" 200; eq "OPTIONS export/pdf Allow-Origin" "${r#*|}" "$ORIGIN"
r=$(pre "$BASE/api/v1/reports/export/00000000-0000-0000-0000-000000000000/pdf" "http://evil.example" GET "authorization,x-dev-user")
if [ -z "${r#*|}" ]; then ok "OPTIONS export/pdf evil origin: no Allow-Origin" "status ${r%%|*}"; else bad "OPTIONS export/pdf evil origin" "$r"; fi

############################################################################
section "2. Auth (X-Dev-User, roles server-side)"
Q_EMP='query { obtenerConfiguracionEmpresa { nombreComercial nit } }'
gql - - "$Q_EMP"; eq "POST /graphql sin X-Dev-User -> 401" "$STATUS" 401
gql no-existe - "$Q_EMP"; eq "X-Dev-User: no-existe -> 401" "$STATUS" 401
gql "$DEV_USER" - "$Q_EMP"; eq "X-Dev-User: $DEV_USER -> 200" "$STATUS" 200
eq "empresa.nombreComercial presente" "$( [ -n "$(printf '%s' "$BODY" | J 'd["data"]["obtenerConfiguracionEmpresa"]["nombreComercial"]')" ] && echo yes)" yes
gql caja-scz - 'query { listarUsuarios { username } }'; eq "caja-scz listarUsuarios -> HTTP 200" "$STATUS" 200; eq "caja-scz listarUsuarios -> FORBIDDEN" "$(errcls)" FORBIDDEN

############################################################################
section "3. Introspección en vivo"
INTRO='query IntrospectionQuery { __schema { queryType { name } mutationType { name } subscriptionType { name } types { ...FullType } directives { name description locations args { ...InputValue } } } }
fragment FullType on __Type { kind name description fields(includeDeprecated: true) { name description args { ...InputValue } type { ...TypeRef } isDeprecated deprecationReason } inputFields { ...InputValue } interfaces { ...TypeRef } enumValues(includeDeprecated: true) { name description isDeprecated deprecationReason } possibleTypes { ...TypeRef } }
fragment InputValue on __InputValue { name description type { ...TypeRef } defaultValue }
fragment TypeRef on __Type { kind name ofType { kind name ofType { kind name ofType { kind name ofType { kind name ofType { kind name ofType { kind name ofType { kind name } } } } } } } }'
gql "$DEV_USER" - "$INTRO"; eq "IntrospectionQuery -> 200" "$STATUS" 200
printf '%s' "$BODY" | py -c 'import sys,json;json.dump(json.load(sys.stdin),open(sys.argv[1],"w",encoding="utf-8",newline="\n"),indent=2,ensure_ascii=False)' "$ROOT/docs/graphql/introspection-live.json" \
  && ok "guardado docs/graphql/introspection-live.json"
DIFF=$(py - "$ROOT/docs/graphql/introspection.json" "$ROOT/docs/graphql/introspection-live.json" <<'EOF'
import json,sys
def tr(t):
    if t is None: return ""
    if t["kind"]=="NON_NULL": return tr(t.get("ofType"))+"!"
    if t["kind"]=="LIST": return "["+tr(t.get("ofType"))+"]"
    return t["name"]
def norm(p):
    d=json.load(open(p,encoding="utf-8"))["data"]["__schema"]; out={}
    for t in d["types"]:
        if t["name"].startswith("__"): continue
        f={x["name"]:(tr(x["type"]),tuple(sorted((a["name"],tr(a["type"])) for a in x["args"]))) for x in (t.get("fields") or [])}
        f.update({"in:"+x["name"]:tr(x["type"]) for x in (t.get("inputFields") or [])})
        f.update({"enum:"+x["name"]:1 for x in (t.get("enumValues") or [])})
        out[t["name"]]=(t["kind"],f)
    return out
a,b=norm(sys.argv[1]),norm(sys.argv[2]); diffs=[]
for n in sorted(set(a)|set(b)):
    if n not in a: diffs.append("solo en vivo: "+n)
    elif n not in b: diffs.append("solo en documentada: "+n)
    elif a[n]!=b[n]:
        for k in sorted(set(a[n][1])|set(b[n][1])):
            if a[n][1].get(k)!=b[n][1].get(k): diffs.append(f"{n}.{k}: doc={a[n][1].get(k)} vivo={b[n][1].get(k)}")
print("EQUAL types=%d"%len(a) if not diffs else "DIFF "+"; ".join(diffs))
EOF
)
case "$DIFF" in EQUAL*) ok "introspección viva == docs/graphql/introspection.json" "$DIFF";; *) bad "introspección viva difiere" "${DIFF:0:600}";; esac

############################################################################
section "4. Consultas con datos sembrados"
q4() { # NAME QUERY VARS JSON-EXPR-RETURNING-TRUE
  gql "$DEV_USER" - "$2" "${3:-{\}}"
  if [ "$STATUS" = 200 ] && [ -z "$(printf '%s' "$BODY" | J 'd.get("errors")' | grep -v '^$\|^null$' || true)" ] && [ "$(printf '%s' "$BODY" | J "$4")" = true ]; then ok "$1" "HTTP 200, $(printf '%s' "$BODY" | J "$5")"; else bad "$1" "HTTP $STATUS ${BODY:0:300}"; fi
}
q4 obtenerConfiguracionEmpresa 'query{obtenerConfiguracionEmpresa{nombreComercial nit monedaSimbolo}}' '{}' 'bool(d["data"]["obtenerConfiguracionEmpresa"]["nit"])' 'd["data"]["obtenerConfiguracionEmpresa"]'
q4 listarPacientes 'query($b:String){listarPacientes(busqueda:$b){id ciNit nombres}}' '{"b":"LOCAL"}' 'len(d["data"]["listarPacientes"])>=5' 'len(d["data"]["listarPacientes"])'
q4 listarOdontologos 'query{listarOdontologos{id nombres especialidades{codigo}}}' '{}' 'len(d["data"]["listarOdontologos"])>=2' 'len(d["data"]["listarOdontologos"])'
q4 listarEspecialidades 'query{listarEspecialidades{id codigo nombre}}' '{}' 'len(d["data"]["listarEspecialidades"])>=2' 'len(d["data"]["listarEspecialidades"])'
q4 listarConsultorios 'query{listarConsultorios{id codigo nombre estado}}' '{}' 'len(d["data"]["listarConsultorios"])>=2' 'len(d["data"]["listarConsultorios"])'
gql "$DEV_USER" - 'query{listarServiciosOdontologicos{id codigo nombre precioBaseBs}}'
P1=$(printf '%s' "$BODY" | J '[s["precioBaseBs"] for s in d["data"]["listarServiciosOdontologicos"] if s["nombre"]=="Exodoncia de Tercer Molar Retenido"][0]')
P2=$(printf '%s' "$BODY" | J '[s["precioBaseBs"] for s in d["data"]["listarServiciosOdontologicos"] if s["nombre"]=="Restauración Fotopolimerizable con Resina"][0]')
eq "servicio “Exodoncia de Tercer Molar Retenido” Bs" "$P1" 1250.0; eq "servicio “Restauración Fotopolimerizable con Resina” Bs" "$P2" 280.0
q4 "listarOrdenesCobro(PENDIENTE)" 'query{listarOrdenesCobro(estado:PENDIENTE){id estado montoTotalBs}}' '{}' 'len(d["data"]["listarOrdenesCobro"])>=3' 'len(d["data"]["listarOrdenesCobro"])'
q4 "obtenerOrdenCobroDetalle(orden crédito sembrada)" 'query($id:UUID!){obtenerOrdenCobroDetalle(id:$id){id modalidadPago montoTotalBs cuotas{numeroCuota montoCuotaBs estado}}}' "{\"id\":\"$SEED_CREDIT\"}" \
  'd["data"]["obtenerOrdenCobroDetalle"]["modalidadPago"]=="CREDITO" and len(d["data"]["obtenerOrdenCobroDetalle"]["cuotas"])==3' '[c["montoCuotaBs"] for c in d["data"]["obtenerOrdenCobroDetalle"]["cuotas"]]'
q4 listarCuotasPendientesPorPaciente 'query($p:UUID!){listarCuotasPendientesPorPaciente(pacienteId:$p){id numeroCuota montoCuotaBs}}' '{"p":"a5000000-0000-0000-0000-000000000002"}' 'len(d["data"]["listarCuotasPendientesPorPaciente"])>=1' 'len(d["data"]["listarCuotasPendientesPorPaciente"])'
q4 listarRoles 'query{listarRoles{codigo}}' '{}' 'len(d["data"]["listarRoles"])>=3' '[r["codigo"] for r in d["data"]["listarRoles"]]'
q4 listarUsuarios 'query{listarUsuarios{username rol{codigo}}}' '{}' '"admin-scz" in [u["username"] for u in d["data"]["listarUsuarios"]]' 'len(d["data"]["listarUsuarios"])'
q4 listarAuditLogs 'query{listarAuditLogs(limite:5){accion entidadAfectada}}' '{}' 'isinstance(d["data"]["listarAuditLogs"],list)' 'len(d["data"]["listarAuditLogs"])'

############################################################################
section "5. Flujo financiero (orden propia del smoke)"
K=$(uuid)
gql "$DEV_USER" "$K" 'mutation($i:CrearOrdenCobroInput!){crearOrdenCobroManual(input:$i){id estado montoTotalBs saldoPendienteBs cuotas{id numeroCuota montoCuotaBs estado}}}' \
  "{\"i\":{\"pacienteId\":\"$PAC\",\"odontologoId\":\"$ODO\",\"items\":[{\"servicioId\":\"$SVC1\",\"cantidad\":1},{\"servicioId\":\"$SVC2\",\"cantidad\":1}],\"descuentoBs\":\"0.00\",\"observaciones\":\"smoke $RUN_ID\"}}"
ORDEN=$(printf '%s' "$BODY" | J 'd["data"]["crearOrdenCobroManual"]["id"]')
if [ "$STATUS" = 200 ] && [ -n "$ORDEN" ]; then ok "crearOrdenCobroManual" "orden=$ORDEN total=$(printf '%s' "$BODY" | J 'd["data"]["crearOrdenCobroManual"]["montoTotalBs"]')"; else bad "crearOrdenCobroManual" "HTTP $STATUS ${BODY:0:400}"; echo "FATAL: sin orden no se puede seguir"; exit 1; fi
FIRST=$(py -c 'import datetime;print(datetime.date.today()+datetime.timedelta(days=7))')
gql "$DEV_USER" "$(uuid)" 'mutation($i:ConfigurarPlanPagosInput!){configurarPlanDePagos(input:$i){id modalidadPago montoSubtotalBs descuentoBs montoTotalBs saldoPendienteBs cantidadCuotas cuotas{id numeroCuota montoCuotaBs fechaVencimiento estado}}}' \
  "{\"i\":{\"ordenCobroId\":\"$ORDEN\",\"modalidadPago\":\"CREDITO\",\"descuentoBs\":\"29.99\",\"cantidadCuotas\":3,\"fechaPrimeraCuota\":\"$FIRST\",\"intervaloDias\":30}}"
PLAN=$(printf '%s' "$BODY" | J 'd["data"]["configurarPlanDePagos"]')
eq "configurarPlanDePagos total = 1530 - 29.99" "$(printf '%s' "$PLAN" | J 'd["montoTotalBs"]')" 1500.01
CHK=$(printf '%s' "$PLAN" | py -c 'import sys,json
from decimal import Decimal as D
p=json.load(sys.stdin); a=[D(str(c["montoCuotaBs"])) for c in p["cuotas"]]; t=D(str(p["montoTotalBs"]))
print("OK %s sum=%s"%(a,sum(a)) if len(a)==3 and sum(a)==t and a[0]==a[1] and a[2]-a[0]==D("0.01") else "BAD %s total=%s"%(a,t))')
case "$CHK" in OK*) ok "3 cuotas suman el total y el centavo sobrante va a la última" "$CHK";; *) bad "reparto de cuotas" "$CHK";; esac
CUOTA1=$(printf '%s' "$PLAN" | J 'd["cuotas"][0]["id"]'); CUOTA2=$(printf '%s' "$PLAN" | J 'd["cuotas"][1]["id"]')
M1=$(printf '%s' "$PLAN" | J 'd["cuotas"][0]["montoCuotaBs"]'); M2=$(printf '%s' "$PLAN" | J 'd["cuotas"][1]["montoCuotaBs"]')
COBRO='mutation($i:CobrarEfectivoInput!){cobrarCuotaEnEfectivo(input:$i){id numeroRecibo ciNitCliente razonSocialCliente montoTotalBs}}'
VARS1="{\"i\":{\"cuotaId\":\"$CUOTA1\",\"usuarioCajeroId\":\"$CAJERO\",\"ciNitCliente\":\"LOCAL-0005\",\"razonSocialCliente\":\"Smoke Local $RUN_ID\"}}"
K1=$(uuid); K2=$(uuid)
gql "$DEV_USER" "$K1" "$COBRO" "$VARS1"; B1="$BODY"
REC1=$(printf '%s' "$B1" | J 'd["data"]["cobrarCuotaEnEfectivo"]["id"]'); NUM1=$(printf '%s' "$B1" | J 'd["data"]["cobrarCuotaEnEfectivo"]["numeroRecibo"]')
if [ "$STATUS" = 200 ] && [ -n "$REC1" ]; then ok "cobrarCuotaEnEfectivo cuota 1 (K1)" "recibo=$NUM1 id=$REC1 monto=$(printf '%s' "$B1" | J 'd["data"]["cobrarCuotaEnEfectivo"]["montoTotalBs"]')"; else bad "cobrarCuotaEnEfectivo" "HTTP $STATUS ${BODY:0:400}"; fi
gql "$DEV_USER" "$K1" "$COBRO" "$VARS1"
if hdr_has Idempotency-Replayed true; then ok "replay K1: Idempotency-Replayed: true"; else bad "replay K1 sin cabecera Idempotency-Replayed" "$(printf '%s' "$HDR" | tr -d '\r' | head -5 | tr '\n' ' ')"; fi
eq "replay K1: cuerpo idéntico" "$BODY" "$B1"
gql "$DEV_USER" "$K2" "$COBRO" "$VARS1"
eq "misma cuota con K2 -> clasificación BAD_REQUEST" "$(errcls)" BAD_REQUEST; has "misma cuota con K2 -> mensaje" "$(errmsg)" "La cuota no está pendiente de pago"
gql "$DEV_USER" "$K1" "$COBRO" "${VARS1/Smoke Local/Otro Payload}"
eq "K1 con payload distinto -> 422" "$STATUS" 422; note "body: ${BODY:0:200}"
if [ "$PSQL_OK" = 1 ]; then
  eq "psql: 1 movimiento INGRESO para la cuota 1" "$(psql_q "select count(*) from movimientos_caja where cuota_id='$CUOTA1' and tipo_movimiento='INGRESO'")" 1
  eq "psql: 1 recibo para la cuota 1" "$(psql_q "select count(*) from recibos r join movimientos_caja m on m.id=r.movimiento_caja_id where m.cuota_id='$CUOTA1'")" 1
  eq "psql: cuota 1 PAGADA" "$(psql_q "select estado from cuotas_pago where id='$CUOTA1'")" PAGADA
  EXP=$(py -c "from decimal import Decimal as D;print(D('1500.01')-D('$M1'))")
  eq "psql: saldo de la orden = total - cuota 1" "$(psql_q "select saldo_pendiente_bs from ordenes_cobro where id='$ORDEN'")" "$EXP"
  eq "psql: estado de la orden PARCIAL" "$(psql_q "select estado from ordenes_cobro where id='$ORDEN'")" PARCIAL
else skip "verificación psql de cobro" "docker/contenedor $PG_CONTAINER no accesible"; fi
# egreso
KE=$(uuid); DESC="smoke egreso $RUN_ID"
EGR='mutation($i:RegistrarEgresoInput!){registrarEgresoCaja(input:$i){id tipoMovimiento categoria montoBs descripcion}}'
VARSE="{\"i\":{\"usuarioCajeroId\":\"$CAJERO\",\"categoria\":\"INSUMOS\",\"metodoPago\":\"EFECTIVO\",\"montoBs\":\"12.34\",\"descripcion\":\"$DESC\"}}"
gql "$DEV_USER" "$KE" "$EGR" "$VARSE"; BE="$BODY"; EGID=$(printf '%s' "$BE" | J 'd["data"]["registrarEgresoCaja"]["id"]')
if [ "$STATUS" = 200 ] && [ -n "$EGID" ]; then ok "registrarEgresoCaja" "id=$EGID"; else bad "registrarEgresoCaja" "HTTP $STATUS ${BODY:0:300}"; fi
gql "$DEV_USER" "$KE" "$EGR" "$VARSE"; eq "egreso replay: cuerpo idéntico" "$BODY" "$BE"
if hdr_has Idempotency-Replayed true; then ok "egreso replay: Idempotency-Replayed: true"; else bad "egreso replay sin cabecera"; fi
[ "$PSQL_OK" = 1 ] && eq "psql: un solo egreso con esa descripción" "$(psql_q "select count(*) from movimientos_caja where descripcion='$DESC'")" 1 || skip "psql egreso único"
# flujo de caja: lunes 00:00 (-04:00) de esta semana hasta el domingo
read -r WS WE < <(py -c 'import datetime as d;tz=d.timezone(d.timedelta(hours=-4));n=d.datetime.now(tz);m=(n-d.timedelta(days=n.weekday())).replace(hour=0,minute=0,second=0,microsecond=0);print(m.isoformat(timespec="seconds"),(m+d.timedelta(days=7)-d.timedelta(seconds=1)).isoformat(timespec="seconds"))')
FLUJO='query($a:DateTime!,$b:DateTime!){obtenerFlujoCaja(fechaInicio:$a,fechaFin:$b){totalIngresosBs totalEgresosBs saldoNetoBs movimientos{id tipoMovimiento categoria metodoPago montoBs descripcion recibo{id numeroRecibo pdfUrlS3}}}}'
gql "$DEV_USER" - "$FLUJO" "{\"a\":\"$WS\",\"b\":\"$WE\"}"; FLU="$BODY"
eq "obtenerFlujoCaja semana ($WS .. $WE) -> 200" "$STATUS" 200
eq "flujo semana incluye ingreso y egreso del smoke" "$(printf '%s' "$FLU" | J "len([m for m in d['data']['obtenerFlujoCaja']['movimientos'] if m['id']=='$EGID' or (m['recibo'] or {}).get('id')=='$REC1'])")" 2
SEEDW=$(printf '%s' "$FLU" | J '[m["id"] for m in d["data"]["obtenerFlujoCaja"]["movimientos"] if m["id"].startswith("aa000000")] and len([m for m in d["data"]["obtenerFlujoCaja"]["movimientos"] if m["id"].startswith("aa000000")])')
if [ "${SEEDW:-0}" -ge 1 ] 2>/dev/null; then ok "flujo semana incluye movimientos sembrados" "$SEEDW sembrados en la semana actual"; else bad "flujo semana sin movimientos sembrados" "las fechas del seed se fijan en la 1ª carga; semana actual no los contiene (¿base antigua?)"; fi
note "totales semana: $(printf '%s' "$FLU" | J '{k:d["data"]["obtenerFlujoCaja"][k] for k in ("totalIngresosBs","totalEgresosBs","saldoNetoBs")}')"

############################################################################
section "6. QR local (mock). NOTA: el esquema NO tiene consultarEstadoQrPagoFacil; se usa obtenerOrdenCobroDetalle{cuotas{estado}} + obtenerFlujoCaja"
QRQ='mutation($c:UUID!,$n:String!,$r:String!){generarQrPagoFacil(cuotaId:$c,ciNitCliente:$n,razonSocialCliente:$r){id cuotaId pagofacilTransactionId montoBs estado fechaExpiracion qrImagenBase64}}'
gql "$DEV_USER" "$(uuid)" "$QRQ" "{\"c\":\"$CUOTA2\",\"n\":\"LOCAL-0005\",\"r\":\"Smoke Local $RUN_ID\"}"
TX=$(printf '%s' "$BODY" | J 'd["data"]["generarQrPagoFacil"]["pagofacilTransactionId"]')
case "$TX" in MOCK-*) ok "generarQrPagoFacil id de transacción MOCK-" "$TX estado=$(printf '%s' "$BODY" | J 'd["data"]["generarQrPagoFacil"]["estado"]')";; *) bad "generarQrPagoFacil" "HTTP $STATUS ${BODY:0:300}";; esac
eq "QR montoBs = monto de la cuota 2" "$(printf '%s' "$BODY" | J 'd["data"]["generarQrPagoFacil"]["montoBs"]')" "$M2"
eq "QR imagen base64 válida (bytes>0)" "$(printf '%s' "$BODY" | py -c 'import sys,json,base64;d=json.load(sys.stdin);print(len(base64.b64decode(d["data"]["generarQrPagoFacil"]["qrImagenBase64"]))>0)')" True
SIM='mutation($t:String!){simularConfirmacionQrDev(pagofacilTransactionId:$t){id numeroRecibo montoTotalBs}}'
KS=$(uuid)
gql "$DEV_USER" "$KS" "$SIM" "{\"t\":\"$TX\"}"; BS="$BODY"; REC2=$(printf '%s' "$BS" | J 'd["data"]["simularConfirmacionQrDev"]["id"]'); NUM2=$(printf '%s' "$BS" | J 'd["data"]["simularConfirmacionQrDev"]["numeroRecibo"]')
if [ "$STATUS" = 200 ] && [ -n "$REC2" ]; then ok "simularConfirmacionQrDev -> Recibo" "recibo=$NUM2 id=$REC2 monto=$(printf '%s' "$BS" | J 'd["data"]["simularConfirmacionQrDev"]["montoTotalBs"]')"; else bad "simularConfirmacionQrDev" "HTTP $STATUS ${BODY:0:300}"; fi
gql "$DEV_USER" "$KS" "$SIM" "{\"t\":\"$TX\"}"; eq "simulación replay (misma key): mismo recibo" "$BODY" "$BS"
gql "$DEV_USER" "$(uuid)" "$SIM" "{\"t\":\"$TX\"}"
note "simulación con key NUEVA: HTTP $STATUS recibo=$(printf '%s' "$BODY" | J 'd["data"]["simularConfirmacionQrDev"]["numeroRecibo"]') error=$(errmsg)"
[ "$PSQL_OK" = 1 ] && eq "psql: un solo movimiento QR_PAGOFACIL para la cuota 2" "$(psql_q "select count(*) from movimientos_caja where cuota_id='$CUOTA2'")" 1 || skip "psql movimiento QR único"
gql "$DEV_USER" - 'query($id:UUID!){obtenerOrdenCobroDetalle(id:$id){estado saldoPendienteBs cuotas{numeroCuota estado}}}' "{\"id\":\"$ORDEN\"}"
eq "detalle: cuota 2 PAGADA (alternativa a consultarEstadoQr)" "$(printf '%s' "$BODY" | J 'd["data"]["obtenerOrdenCobroDetalle"]["cuotas"][1]["estado"]')" PAGADA
note "orden: $(printf '%s' "$BODY" | J 'd["data"]["obtenerOrdenCobroDetalle"]')"

############################################################################
section "7. Recibo PDF (URL presignada)"
RQ='query($a:DateTime!,$b:DateTime!){obtenerFlujoCaja(fechaInicio:$a,fechaFin:$b){movimientos{recibo{id numeroRecibo pdfUrlS3}}}}'
URL=""
for i in 1 2 3 4 5 6 7 8 9 10; do
  gql "$DEV_USER" - "$RQ" "{\"a\":\"$WS\",\"b\":\"$WE\"}"
  URL=$(printf '%s' "$BODY" | J "[m['recibo']['pdfUrlS3'] for m in d['data']['obtenerFlujoCaja']['movimientos'] if m['recibo'] and m['recibo']['id']=='$REC1'][0] or ''")
  [ -n "$URL" ] && break; sleep 1
done
if [ -n "$URL" ]; then ok "admin: pdfUrlS3 del recibo $NUM1" "${URL%%\?*}?<firmado> (intentos: $i)"; else bad "admin: pdfUrlS3 vacío tras sondeo de 10 s" "${BODY:0:300}"; fi
gql caja-scz - "$RQ" "{\"a\":\"$WS\",\"b\":\"$WE\"}"
[ -n "$(printf '%s' "$BODY" | J "[m['recibo']['pdfUrlS3'] for m in d['data']['obtenerFlujoCaja']['movimientos'] if m['recibo'] and m['recibo']['id']=='$REC1'][0] or ''")" ] && ok "caja-scz puede leer pdfUrlS3" || bad "caja-scz no obtiene pdfUrlS3" "${BODY:0:300}"
gql recepcion-scz - "$RQ" "{\"a\":\"$WS\",\"b\":\"$WE\"}"
eq "recepcion-scz pdfUrlS3 -> FORBIDDEN" "$(errcls)" FORBIDDEN
if [ -n "$URL" ]; then
  code=$(curl -s "${CT[@]}" -o "$TMP/r.pdf" -D "$TMP/pdf.hdr" -w '%{http_code}' "$URL" || echo 000)
  eq "GET URL presignada (sin cabeceras de auth) -> 200" "$code" 200
  eq "magic %PDF" "$(head -c 4 "$TMP/r.pdf")" "%PDF"
  has "content-type application/pdf" "$(tr -d '\r' <"$TMP/pdf.hdr" | tr 'A-Z' 'a-z')" "content-type: application/pdf"
  note "tamaño: $(wc -c <"$TMP/r.pdf") bytes"
  eq "GET sin query string (anónimo) -> 403" "$(curl -s "${CT[@]}" -o /dev/null -w '%{http_code}' "${URL%%\?*}" || echo 000)" 403
fi
code=$(curl -s "${CT[@]}" -o /dev/null -w '%{http_code}' -H "X-Dev-User: $DEV_USER" "$BASE/api/v1/recibos/$REC1/pdf" || echo 000)
case "$code" in 404|401) ok "GET /api/v1/recibos/{id}/pdf no existe (con admin)" "HTTP $code";; *) bad "GET /api/v1/recibos/{id}/pdf" "HTTP $code";; esac
code=$(curl -s "${CT[@]}" -o /dev/null -w '%{http_code}' "$BASE/api/v1/recibos/$REC1/pdf" || echo 000)
case "$code" in 404|401) ok "GET /api/v1/recibos/{id}/pdf anónimo" "HTTP $code";; *) bad "GET /api/v1/recibos/{id}/pdf anónimo" "HTTP $code";; esac

############################################################################
section "8. Agente ERP, MCP y exportaciones"
gql "$DEV_USER" - 'query{obtenerEstadoServidorMcp{activo protocolVersion toolsRegistradas{nombre}}}'
has "MCP status activo" "$BODY" '"activo":true'
gql "$DEV_USER" - 'mutation{ejecutarAgenteErp(instruccion:"Monitorea mi negocio",modoAutonomoMonitoreo:true){respuestaNaturalAgente toolsMcpInvocadas accionCrudEjecutada}}'
has "agente monitorea sin CRUD" "$BODY" '"accionCrudEjecutada":null'
gql "$DEV_USER" - 'mutation{ejecutarAgenteErp(instruccion:"Genera un reporte de precios de servicios"){respuestaNaturalAgente reporteGenerado{reporteId columnas tipoGraficoSugerido urlDescargaExcel urlDescargaPdf}}}'
RID=$(printf '%s' "$BODY" | J 'd["data"]["ejecutarAgenteErp"]["reporteGenerado"]["reporteId"]')
if [ -n "$RID" ]; then
  ok "agente generó reporte real" "reporteId=$RID"
  for f in xlsx pdf; do
    code=$(curl -s "${CT[@]}" -o "$TMP/exp.$f" -w '%{http_code}' -H "X-Dev-User: $DEV_USER" "$BASE/api/v1/reports/export/$RID/$f" || echo 000)
    eq "export $f del reporte real -> 200" "$code" 200
  done
else
  bad "reporte mediante agente no disponible" "$BODY"
fi
FAKE=$(uuid)
code=$(curl -s "${CT[@]}" -o "$TMP/e.txt" -w '%{http_code}' -H "X-Dev-User: $DEV_USER" "$BASE/api/v1/reports/export/$FAKE/pdf" || echo 000)
eq "export pdf reporteId aleatorio (admin) -> 404" "$code" 404; eq "  cuerpo" "$(cat "$TMP/e.txt")" "Reporte no disponible"
code=$(curl -s "${CT[@]}" -o /dev/null -w '%{http_code}' -H "X-Dev-User: recepcion-scz" "$BASE/api/v1/reports/export/$FAKE/pdf" || echo 000)
eq "export pdf como recepcion-scz -> 403" "$code" 403
code=$(curl -s "${CT[@]}" -o "$TMP/e.txt" -w '%{http_code}' -H "X-Dev-User: $DEV_USER" "$BASE/api/v1/reports/export/$FAKE/csv" || echo 000)
eq "export formato inválido (csv) -> 400" "$code" 400

############################################################################
section "9. n8n / WhatsApp"
HEALTH=$(curl -s "${CT[@]}" "$BASE/actuator/health" || true)
if [ -f "$ROOT/src/main/resources/application-local.yml" ] && py - "$ROOT/src/main/resources/application-local.yml" <<'EOF'
import re,sys
t=open(sys.argv[1],encoding="utf-8").read()
sys.exit(0 if re.search(r"n8n:\s*\n\s*enabled:\s*false",t) else 1)
EOF
then ok "erp.n8n.enabled=false en application-local.yml"; else bad "erp.n8n.enabled no es false en application-local.yml"; fi
gql "$DEV_USER" - "$RQ" "{\"a\":\"$WS\",\"b\":\"$WE\"}" >/dev/null
[ "$PSQL_OK" = 1 ] && eq "psql: recibos del smoke con enviado_whatsapp=false" "$(psql_q "select count(*) from recibos where id in ('$REC1','$REC2') and enviado_whatsapp")" 0 || skip "psql enviado_whatsapp"
note "WhatsApp: NO CONFIGURADO. Confirmar pagos no demuestra entrega de mensajes."

############################################################################
section "10. RabbitMQ: outbox de catálogo"
if [ "$PSQL_OK" != 1 ]; then skip "outbox" "psql no disponible"; else
  EXCH="dental.topic.exchange"; RK="catalogo.paciente.creado"
  RAPI() { curl -s "${CT[@]}" -u "$RABBIT_USER:$RABBIT_PASS" "$@"; }
  BIND=$(RAPI "$RABBIT_API/api/exchanges/%2F/$EXCH/bindings/source" || true)
  NB=$(printf '%s' "$BIND" | J '[b["routing_key"] for b in d if b["routing_key"].startswith("catalogo.")]')
  note "bindings catalogo.* previos en $EXCH: ${NB:-no se pudo consultar la API de gestión}"
  CI="SMK$(date +%H%M%S)$RANDOM"
  gql "$DEV_USER" "$(uuid)" 'mutation($i:CrearPacienteInput!){crearPaciente(input:$i){id ciNit}}' "{\"i\":{\"ciNit\":\"$CI\",\"nombres\":\"Smoke\",\"apellidos\":\"Paciente $RUN_ID\",\"telefonoWhatsapp\":\"+59170000000\"}}"
  PID=$(printf '%s' "$BODY" | J 'd["data"]["crearPaciente"]["id"]')
  if [ -n "$PID" ]; then ok "crearPaciente" "id=$PID ciNit=$CI"; else bad "crearPaciente" "HTTP $STATUS ${BODY:0:300}"; fi
  sleep 3
  ROW=$(psql_q "select routing_key||'|attempts='||attempts||'|sent='||(sent_at is not null) from catalog_event_outbox where payload->>'pacienteId'='$PID' or payload::text like '%$PID%' limit 1")
  note "outbox tras 3 s SIN cola enlazada: ${ROW:-sin fila}"
  if [ -z "${NB//[\[\]]/}" ]; then
    case "$ROW" in *sent=false*) ok "sin cola enlazada: la fila queda sin enviar (publicación mandatory -> NO_ROUTE -> reintento)" "$ROW";; *) bad "esperado sent=false sin binding" "$ROW";; esac
  fi
  # cola temporal propia, borrada al salir
  TMPQ="erp-smoke-tmp-$RANDOM$RANDOM"
  c1=$(RAPI -o /dev/null -w '%{http_code}' -X PUT -H 'content-type: application/json' -d '{"durable":true,"auto_delete":false}' "$RABBIT_API/api/queues/%2F/$TMPQ" || echo 000)
  c2=$(RAPI -o /dev/null -w '%{http_code}' -X POST -H 'content-type: application/json' -d "{\"routing_key\":\"$RK\"}" "$RABBIT_API/api/bindings/%2F/e/$EXCH/q/$TMPQ" || echo 000)
  note "cola temporal $TMPQ creada ($c1) y enlazada a $RK ($c2)"
  SENT=""
  for i in $(seq 1 40); do
    SENT=$(psql_q "select count(*) from catalog_event_outbox where payload::text like '%$PID%' and sent_at is not null"); [ "$SENT" = 1 ] && break; sleep 1
  done
  if [ "$SENT" = 1 ]; then ok "con cola enlazada el outbox entrega (sent_at no nulo)" "tras ~${i}s: $(psql_q "select routing_key||' attempts='||attempts||' sent_at='||sent_at from catalog_event_outbox where payload::text like '%$PID%'")"; else bad "outbox no entregado en 40 s" "$(psql_q "select routing_key||' attempts='||attempts from catalog_event_outbox where payload::text like '%$PID%'")"; fi
  MSG=$(RAPI -X POST -H 'content-type: application/json' -d '{"count":5,"ackmode":"ack_requeue_false","encoding":"auto"}' "$RABBIT_API/api/queues/%2F/$TMPQ/get" || true)
  eq "cola temporal recibió el mensaje con routing key $RK" "$(printf '%s' "$MSG" | J 'len([m for m in d if m["routing_key"]=="'$RK'" and "'$PID'" in m["payload"]])')" 1
  RAPI -o /dev/null -X DELETE "$RABBIT_API/api/queues/%2F/$TMPQ"; TMPQ=""
  note "cola temporal eliminada"
fi

############################################################################
section "11. Keycloak: Authorization Bearer (opcional, solo si el emisor local responde)"
KC_ISSUER="${KEYCLOAK_ISSUER:-http://localhost:8180/realms/dental-platform}"
KC_CLIENT="${KEYCLOAK_TEST_CLIENT:-ms1-local-tests}"
KC_PASS="${KEYCLOAK_TEST_PASSWORD:-LocalDev2026!}"
if [ "$(curl -s "${CT[@]}" -o /dev/null -w '%{http_code}' "$KC_ISSUER/.well-known/openid-configuration" || echo 000)" != 200 ]; then
  skip "Keycloak Bearer" "emisor $KC_ISSUER no responde; docker compose up -d keycloak"
else
  kc_token() { # USER -> access_token (empty on failure)
    curl -s "${CT[@]}" -d grant_type=password -d "client_id=$KC_CLIENT" -d "username=$1" --data-urlencode "password=$KC_PASS" -d scope=openid "$KC_ISSUER/protocol/openid-connect/token" | J 'd["access_token"]'
  }
  TA=$(kc_token admin-scz); TC=$(kc_token caja-scz); TR=$(kc_token recepcion-scz)
  if [ -z "$TA" ] || [ -z "$TC" ] || [ -z "$TR" ]; then bad "tokens de Keycloak para admin-scz/caja-scz/recepcion-scz" "grant de contraseña rechazado"; else
    ok "tokens obtenidos con el cliente $KC_CLIENT (password grant, solo DEV)" "claims admin-scz: $(printf '%s' "$TA" | cut -d. -f2 | py -c 'import sys,json,base64;t=sys.stdin.read().strip();c=json.loads(base64.urlsafe_b64decode(t+"="*(-len(t)%4)));print({k:c.get(k) for k in ("preferred_username","aud")},c["realm_access"]["roles"])')"
    Q_USERS='query { listarUsuarios { username } }'
    BEARER="$TA" gql - - "$Q_USERS"
    if [ "$STATUS" = 401 ]; then bad "Bearer admin-scz listarUsuarios -> 200" "HTTP 401: esta instancia de MS1 probablemente arrancó sin ERP_LOCAL_JWT_ISSUER_URI; reiníciela con el perfil local actual"
    else eq "Bearer admin-scz listarUsuarios -> 200" "$STATUS" 200; has "Bearer admin-scz ve los usuarios sembrados" "$BODY" "caja-scz"; fi
    BEARER="$TC" gql - - "$Q_USERS"; eq "Bearer caja-scz listarUsuarios -> HTTP 200" "$STATUS" 200; eq "Bearer caja-scz listarUsuarios -> FORBIDDEN" "$(errcls)" FORBIDDEN
    BEARER="$TR" gql - - "$Q_EMP"; eq "Bearer recepcion-scz obtenerConfiguracionEmpresa -> 200" "$STATUS" 200
    BEARER="$TC" gql admin-scz - "$Q_USERS"; eq "Bearer caja-scz + X-Dev-User admin-scz: la cabecera se ignora (FORBIDDEN)" "$(errcls)" FORBIDDEN
    POS=$(( ${#TA} - 12 ))
    TAMPERED="${TA:0:POS}$([ "${TA:POS:1}" = A ] && echo B || echo A)${TA:POS+1}"
    BEARER="$TAMPERED" gql - - "$Q_USERS"; eq "Bearer manipulado -> 401" "$STATUS" 401
    BEARER="$TAMPERED" gql admin-scz - "$Q_USERS"; eq "Bearer manipulado + X-Dev-User válido -> 401 (sin respaldo)" "$STATUS" 401
    SPEC="KCS$RANDOM"
    BEARER="$TA" gql - "$(uuid)" 'mutation($c:String!){crearEspecialidad(codigo:$c,nombre:"Smoke Bearer"){id}}' "{\"c\":\"$SPEC\"}"
    SID=$(printf '%s' "$BODY" | J 'd["data"]["crearEspecialidad"]["id"]')
    if [ -n "$SID" ]; then ok "Bearer admin-scz crearEspecialidad" "id=$SID"; else bad "Bearer admin-scz crearEspecialidad" "HTTP $STATUS ${BODY:0:300}"; fi
    if [ "$PSQL_OK" = 1 ] && [ -n "$SID" ]; then
      eq "psql: auditoría atribuida al usuario de BD admin-scz" "$(psql_q "select u.username from audit_logs a join usuarios u on u.id=a.usuario_id where a.entidad_id='$SID'")" admin-scz
    else skip "psql auditoría Bearer"; fi
    BEARER="$TC" gql - "$(uuid)" 'mutation{crearEspecialidad(codigo:"KC-DENY",nombre:"Smoke Bearer Denied"){id}}'; eq "Bearer caja-scz crearEspecialidad -> FORBIDDEN" "$(errcls)" FORBIDDEN
    [ "$PSQL_OK" = 1 ] && eq "psql: la mutación denegada no dejó filas" "$(psql_q "select count(*) from especialidades where codigo='KC-DENY'")" 0 || skip "psql mutación denegada"
  fi
fi

############################################################################
printf '\nRESUMEN: PASS=%d FAIL=%d SKIP=%d\n' "$PASS" "$FAIL" "$SKIP"
printf 'IDs creados: orden=%s cuota1=%s cuota2=%s recibo1=%s(%s) recibo2=%s(%s) egreso=%s\n' "$ORDEN" "$CUOTA1" "$CUOTA2" "$REC1" "$NUM1" "$REC2" "$NUM2" "$EGID"
[ "$FAIL" -eq 0 ]
