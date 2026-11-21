#!/usr/bin/env bash
# Comprueba que el stack local levantado con `scripts/dev-up.sh --app` funciona de punta a punta: la
# aplicación en contenedor está sana y migró el esquema, Prometheus la ve, Grafana tiene el dashboard
# aprovisionado y el correo llega a Mailpit.
#
# Qué detecta que las pruebas de Maven no ven: un Dockerfile roto, un Compose que no arranca, una
# configuración de Prometheus o Grafana que carga pero no conecta, o un servicio sano en apariencia.
#
# Uso: bash scripts/smoke-test-stack.sh   (con el stack ya arriba)
set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck disable=SC1091
set -a; . ./.env; set +a

fallos=0
ok() { printf '  ok   %s\n' "$1"; }
mal() { printf '  FALLA %s\n' "$1" >&2; fallos=$((fallos + 1)); }

# Reintenta hasta 60 s: Prometheus tarda unos ciclos de scrape en ver un destino nuevo.
esperar() { local descripcion="$1"; shift; for _ in $(seq 1 30); do "$@" >/dev/null 2>&1 && { ok "$descripcion"; return 0; }; sleep 2; done; mal "$descripcion"; }

app_viva_en_prometheus() {
  curl -fsS 'http://localhost:9090/api/v1/query?query=max(up%7Bjob%3D%22jobseeker%22%7D)' |
    python3 -c "import sys,json; r=json.load(sys.stdin)['data']['result']; sys.exit(0 if r and r[0]['value'][1]=='1' else 1)"
}
dashboard_aprovisionado() {
  curl -fsS -u "admin:${GRAFANA_ADMIN_PASSWORD}" http://localhost:3000/api/dashboards/uid/jobseeker-overview |
    python3 -c "import sys,json; d=json.load(sys.stdin); sys.exit(0 if d['meta']['provisioned'] and d['dashboard']['panels'] else 1)"
}
datasource_sano() {
  curl -fsS -u "admin:${GRAFANA_ADMIN_PASSWORD}" http://localhost:3000/api/datasources/uid/prometheus/health |
    python3 -c "import sys,json; sys.exit(0 if json.load(sys.stdin).get('status')=='OK' else 1)"
}
migraciones_aplicadas() {
  [ "$(docker compose exec -T postgres psql -U "$DB_DEV_USER" -d "$DB_DEV_NAME" -tAc \
    'select count(*) from flyway_schema_history where success')" -ge 5 ]
}
correo_llega() {
  python3 - <<PY
import smtplib, json, urllib.request
from email.message import EmailMessage
m = EmailMessage(); m["From"] = "no-reply@jobseeker.local"; m["To"] = "humo@example.test"
m["Subject"] = "humo-del-stack"; m.set_content("x")
with smtplib.SMTP("localhost", int("${MAIL_PORT}")) as s: s.send_message(m)
d = json.load(urllib.request.urlopen("http://localhost:8025/api/v1/messages"))
raise SystemExit(0 if any(x["Subject"] == "humo-del-stack" for x in d["messages"]) else 1)
PY
}
redis_exige_clave() {
  ! docker compose exec -T redis redis-cli ping 2>&1 | grep -q PONG &&
    docker compose exec -T redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli ping' | grep -q PONG
}
web_redirige_al_login() {
  local cabeceras
  cabeceras="$(curl -sI http://localhost:8080/app/home)"
  grep -qi '^HTTP/[0-9.]* 302' <<<"$cabeceras" && grep -qi '^location:.*/oauth2/authorization/clavaris' <<<"$cabeceras"
}
cabeceras_de_seguridad() {
  local cabeceras
  cabeceras="$(curl -sI http://localhost:8080/app/home)"
  grep -qi "^content-security-policy:.*default-src 'self'" <<<"$cabeceras" &&
    grep -qi '^x-content-type-options: nosniff' <<<"$cabeceras" &&
    grep -qi '^x-frame-options: DENY' <<<"$cabeceras" &&
    grep -qi '^x-trace-id:' <<<"$cabeceras"
}
api_cerrada_sin_credencial() {
  [ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/api/v1/cualquiera)" = "401" ]
}
# Un resultado vacío (el contenedor no responde) NO cuenta como «no es root»: debe ser un UID numérico.
no_es_root() {
  local uid
  uid="$(docker compose exec -T app id -u 2>/dev/null | tr -d '\r')"
  [[ "$uid" =~ ^[0-9]+$ ]] && [ "$uid" != "0" ]
}
# El healthcheck de Docker de la aplicación (bash + /dev/tcp contra /actuator/health/readiness).
app_sana() { [ "$(docker compose ps app --format '{{.Health}}' 2>/dev/null)" = "healthy" ]; }

echo "Prueba de humo del stack:"
esperar "la aplicación en contenedor está sana según Docker" app_sana
esperar "la aplicación en contenedor está viva en Prometheus" app_viva_en_prometheus
esperar "Flyway aplicó las migraciones en Postgres 17" migraciones_aplicadas
esperar "Grafana tiene el dashboard aprovisionado" dashboard_aprovisionado
esperar "la fuente de datos de Grafana conecta con Prometheus" datasource_sano
esperar "un correo enviado por SMTP llega a Mailpit" correo_llega
esperar "Redis rechaza el acceso sin clave y lo acepta con ella" redis_exige_clave
esperar "la aplicación corre sin privilegios de root" no_es_root
esperar "la web sin sesión redirige al login de Clavaris" web_redirige_al_login
esperar "la web envía las cabeceras de seguridad y el identificador de traza" cabeceras_de_seguridad
esperar "la API rechaza sin credencial (cadena cerrada)" api_cerrada_sin_credencial

[ "$fallos" -eq 0 ] && echo "Stack OK." || { echo "$fallos comprobación(es) fallaron." >&2; exit 1; }
