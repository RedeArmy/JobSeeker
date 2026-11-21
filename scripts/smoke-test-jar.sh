#!/usr/bin/env bash
# Arranca el jar ejecutable de verdad contra un Postgres 17 con pgvector y un Redis, y comprueba salud y migraciones.
#
# Por qué existe: las pruebas de `mvn verify` corren con su propio classpath de pruebas y no ven un
# problema de empaquetado. Una dependencia con alcance `test` que debía llegar de forma transitiva
# dejó fuera del jar a spring-tx y el jar no arrancaba, con todas las pruebas en verde.
#
# Uso: bash scripts/smoke-test-jar.sh [ruta-al-jar]   (necesita Docker)
set -euo pipefail

jar="${1:-$(ls app/target/app-*.jar | grep -v original | head -1)}"
[ -f "$jar" ] || { echo "ERROR: no existe el jar ${jar}; ejecuta mvn package antes" >&2; exit 2; }

contenedor="jobseeker-smoke-$$"
contenedor_redis="${contenedor}-redis"
puerto_redis=56499
puerto_db=55499
puerto_app=18099
registro="$(mktemp)"
pid_app=""

limpiar() {
  [ -z "$pid_app" ] || kill "$pid_app" 2>/dev/null || true
  docker rm -f "$contenedor" "$contenedor_redis" >/dev/null 2>&1 || true
  rm -f "$registro"
}
trap limpiar EXIT

docker run -d --name "$contenedor" -e POSTGRES_USER=jobseeker -e POSTGRES_PASSWORD=smoke \
  -e POSTGRES_DB=jobseeker -p "127.0.0.1:${puerto_db}:5432" pgvector/pgvector:pg17 >/dev/null
for _ in $(seq 1 30); do
  docker exec "$contenedor" pg_isready -U jobseeker >/dev/null 2>&1 && break
  sleep 1
done

# Redis para las sesiones web (ADR-0020): sin él `/actuator/health` queda en DOWN.
docker run -d --name "$contenedor_redis" -p "127.0.0.1:${puerto_redis}:6379" redis:7.4.12-alpine >/dev/null
for _ in $(seq 1 30); do
  docker exec "$contenedor_redis" redis-cli ping 2>/dev/null | grep -q PONG && break
  sleep 1
done

SPRING_PROFILES_ACTIVE=dev REDIS_HOST=127.0.0.1 REDIS_PORT="$puerto_redis" DB_DEV_HOST=127.0.0.1 DB_DEV_PORT="$puerto_db" DB_DEV_NAME=jobseeker \
  DB_DEV_USER=jobseeker DB_DEV_PASSWORD=smoke SERVER_PORT="$puerto_app" \
  java --enable-native-access=ALL-UNNAMED -jar "$jar" >"$registro" 2>&1 &
pid_app=$!

salud=""
for _ in $(seq 1 60); do
  salud="$(curl -s "http://127.0.0.1:${puerto_app}/actuator/health" || true)"
  case "$salud" in *'"status":"UP"'*) break ;; esac
  kill -0 "$pid_app" 2>/dev/null || { echo "ERROR: el jar terminó antes de arrancar:"; tail -20 "$registro"; exit 1; }
  sleep 1
done
case "$salud" in *'"status":"UP"'*) ;; *) echo "ERROR: el jar no llegó a UP: ${salud}"; tail -20 "$registro"; exit 1 ;; esac

migraciones="$(docker exec "$contenedor" psql -U jobseeker -d jobseeker -tAc \
  "select count(*) from flyway_schema_history where success")"
[ "${migraciones:-0}" -ge 5 ] || { echo "ERROR: se aplicaron ${migraciones} migraciones, se esperaban al menos 5"; exit 1; }
echo "Jar OK: salud UP y ${migraciones} migraciones aplicadas sobre Postgres 17."
