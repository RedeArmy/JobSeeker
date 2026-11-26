#!/usr/bin/env bash
# Valida la configuración del stack de desarrollo con las herramientas oficiales de cada pieza, sin
# arrancar nada: Compose (sintaxis y variables), Prometheus (configuración, reglas y pruebas de las
# reglas), Alertmanager y los dashboards de Grafana (JSON válido con uid y paneles).
#
# Las imágenes de las herramientas son las mismas, fijadas por digest, que usa docker-compose.yml:
# validar con una versión distinta a la que se ejecuta daría falsos resultados.
#
# Uso: bash scripts/check-infra-config.sh   (necesita Docker)
set -euo pipefail
cd "$(dirname "$0")/.."

fallos=0
paso() { printf '== %s\n' "$1"; }
fallo() { printf 'ERROR: %s\n' "$1" >&2; fallos=$((fallos + 1)); }

# Digest de una imagen tal como lo fija docker-compose.yml (una sola fuente de verdad).
imagen() { docker compose config --images 2>/dev/null | grep -E "^$1" | head -1; }

paso "docker compose config (con valores de relleno: solo se valida la estructura)"
env \
  DB_DEV_NAME=x DB_DEV_USER=x DB_DEV_PASSWORD=x DB_DEV_PORT=5433 REDIS_PASSWORD=x REDIS_PORT=6380 \
  MAIL_PORT=1025 OLLAMA_PORT=11434 GRAFANA_ADMIN_PASSWORD=x \
  STORAGE_ACCESS_KEY=x STORAGE_SECRET_KEY=x STORAGE_PORT=8333 CLAMAV_PORT=3310 \
  docker compose --profile ai --profile app --profile files config -q || fallo "docker-compose.yml no es válido"

# Las imágenes de Compose se leen con valores de relleno, para no exigir un .env.
export DB_DEV_NAME=x DB_DEV_USER=x DB_DEV_PASSWORD=x DB_DEV_PORT=5433 REDIS_PASSWORD=x REDIS_PORT=6380 \
  MAIL_PORT=1025 OLLAMA_PORT=11434 GRAFANA_ADMIN_PASSWORD=x \
  STORAGE_ACCESS_KEY=x STORAGE_SECRET_KEY=x STORAGE_PORT=8333 CLAMAV_PORT=3310
prom="$(imagen 'prom/prometheus')"
am="$(imagen 'prom/alertmanager')"
[ -n "$prom" ] && [ -n "$am" ] || { fallo "no se encontraron las imágenes de Prometheus y Alertmanager en docker-compose.yml"; exit 1; }

promtool() { docker run --rm -v "$PWD/infra/docker/prometheus:/etc/prometheus:ro" --entrypoint promtool "$prom" "$@"; }
paso "promtool check config"; promtool check config /etc/prometheus/prometheus.yml || fallo "prometheus.yml no es válido"
paso "promtool check rules"; promtool check rules /etc/prometheus/alert-rules.yml || fallo "alert-rules.yml no es válido"
paso "promtool test rules"; promtool test rules /etc/prometheus/tests/alert-rules.test.yml || fallo "fallan las pruebas de las reglas de alerta"

paso "amtool check-config"
docker run --rm -v "$PWD/infra/docker/alertmanager:/etc/alertmanager:ro" --entrypoint amtool "$am" \
  check-config /etc/alertmanager/alertmanager.yml || fallo "alertmanager.yml no es válido"

paso "dashboards de Grafana"
python3 - <<'PY' || fallos=$((fallos + 1))
import glob, json, sys
errores = 0
for ruta in sorted(glob.glob("infra/docker/grafana/dashboards/*.json")):
    d = json.load(open(ruta, encoding="utf-8"))
    for campo in ("uid", "title", "panels"):
        if not d.get(campo):
            print(f"ERROR: {ruta} no tiene «{campo}»", file=sys.stderr); errores += 1
    print(f"  {ruta}: {len(d.get('panels', []))} paneles")
sys.exit(1 if errores else 0)
PY

if [ "$fallos" -gt 0 ]; then echo "$fallos comprobación(es) fallaron." >&2; exit 1; fi
echo "Configuración del stack válida."
