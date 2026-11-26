#!/usr/bin/env bash
# Levanta el stack de desarrollo local (docker-compose.yml) y espera a que esté sano.
#
# Uso: bash scripts/dev-up.sh [--ai] [--files] [--app]
#   --ai     añade Ollama y baja el modelo de embeddings (imagen y modelo pesados)
#   --files  añade el almacén S3 (SeaweedFS) y el antivirus (ClamAV, hasta 1,85 GiB de memoria)
#   --app    añade la aplicación en un contenedor (se construye desde app/Dockerfile)
#
# La primera vez genera `.env` con contraseñas aleatorias y puertos libres: ese archivo no se versiona
# (.gitignore) y solo contiene valores de desarrollo local. Los nombres que usa salen de .env.example.
set -euo pipefail
cd "$(dirname "$0")/.."

perfiles=()
for arg in "$@"; do
  case "$arg" in
    --ai) perfiles+=(--profile ai) ;;
    --files) perfiles+=(--profile files) ;;
    --app) perfiles+=(--profile app) ;;
    -h | --help) sed -n '2,11p' "$0"; exit 0 ;;
    *) echo "opción desconocida: $arg (usa --help)" >&2; exit 2 ;;
  esac
done

command -v docker >/dev/null || { echo "ERROR: docker no está instalado" >&2; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "ERROR: falta el plugin docker compose" >&2; exit 1; }

secreto() { openssl rand -base64 24 | tr -d '/+=' | cut -c1-24; }

# Primer puerto libre a partir de uno dado: el 5432 suele estar ocupado por un Postgres del anfitrión.
puerto_libre() {
  local puerto="$1"
  while ss -ltn "sport = :${puerto}" 2>/dev/null | grep -q LISTEN; do puerto=$((puerto + 1)); done
  echo "$puerto"
}

generar_env() {
  echo "Generando .env con valores de desarrollo local..."
  local db_port redis_port
  db_port="$(puerto_libre 5433)"
  redis_port="$(puerto_libre 6380)"
  cat > .env <<ENV
# Generado por scripts/dev-up.sh: valores de desarrollo local. No se versiona.
SPRING_PROFILES_ACTIVE=dev

DB_DEV_HOST=localhost
DB_DEV_PORT=${db_port}
DB_DEV_NAME=jobseeker
DB_DEV_USER=jobseeker
DB_DEV_PASSWORD=$(secreto)

REDIS_HOST=localhost
REDIS_PORT=${redis_port}
REDIS_PASSWORD=$(secreto)

# Clave AES-256 con la que se cifra el refresh token de la sesión (ADR-0020); solo desarrollo local.
TOKEN_ENCRYPTION_ACTIVE_KID=k1
TOKEN_ENCRYPTION_KEYS=k1:$(openssl rand -base64 32)

OLLAMA_HOST=localhost
OLLAMA_PORT=11434
OLLAMA_EMBEDDING_MODEL=qwen3-embedding:0.6b

# Almacén de archivos y antivirus (ADR-0021), con el perfil `files` (--files).
STORAGE_ENDPOINT=http://localhost:8333
STORAGE_BUCKET=jobseeker-dev
STORAGE_ACCESS_KEY=$(secreto)
STORAGE_SECRET_KEY=$(secreto)
STORAGE_PORT=8333
CLAMAV_HOST=localhost
CLAMAV_PORT=3310

MAIL_HOST=localhost
MAIL_PORT=1025
MAIL_FROM=no-reply@jobseeker.local

GRAFANA_ADMIN_PASSWORD=$(secreto)
ENV
  chmod 600 .env
}

[ -f .env ] || generar_env

# Los puertos fijos del stack: si algo ajeno los ocupa, Compose fallaría con un error poco claro.
if [ -z "$(docker compose ps -q 2>/dev/null)" ]; then
  for puerto in 3000 8025 9090 9093; do
    if ss -ltn "sport = :${puerto}" 2>/dev/null | grep -q LISTEN; then
      echo "ERROR: el puerto ${puerto} ya está en uso; libéralo o cambia el mapeo en docker-compose.yml" >&2
      exit 1
    fi
  done
fi

docker compose "${perfiles[@]}" up -d --wait --build

# shellcheck disable=SC1091
set -a; . ./.env; set +a
if printf '%s\n' "${perfiles[@]:-}" | grep -qx ai; then
  echo "Bajando el modelo de embeddings ${OLLAMA_EMBEDDING_MODEL} (la primera vez tarda)..."
  docker compose exec ollama ollama pull "${OLLAMA_EMBEDDING_MODEL}"
fi

cat <<RESUMEN

Stack listo:
  Postgres 17     localhost:${DB_DEV_PORT}   (base ${DB_DEV_NAME}, usuario ${DB_DEV_USER})
  Redis           localhost:${REDIS_PORT}
  Mailpit         http://localhost:8025       (SMTP en localhost:${MAIL_PORT})
  Prometheus      http://localhost:9090
  Alertmanager    http://localhost:9093
  Grafana         http://localhost:3000       (usuario admin; contraseña en .env)

Para ejecutar la aplicación en el anfitrión con estos valores:
  set -a; . ./.env; set +a
  mvn -B -q -pl app -am -DskipTests package && java --enable-native-access=ALL-UNNAMED -jar app/target/app-*-SNAPSHOT.jar
Para detener el stack: bash scripts/dev-down.sh
RESUMEN
