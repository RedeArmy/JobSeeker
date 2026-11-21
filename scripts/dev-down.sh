#!/usr/bin/env bash
# Detiene el stack de desarrollo local. Conserva los datos (volúmenes) salvo con --volumes.
#
# Uso: bash scripts/dev-down.sh [--volumes]
set -euo pipefail
cd "$(dirname "$0")/.."

opciones=()
case "${1:-}" in
  --volumes)
    read -r -p "Esto BORRA la base de datos, Redis y los dashboards locales. ¿Continuar? [s/N] " respuesta
    [ "$respuesta" = "s" ] || { echo "Cancelado."; exit 0; }
    opciones+=(--volumes)
    ;;
  "") ;;
  *) echo "opción desconocida: $1" >&2; exit 2 ;;
esac

# Todos los perfiles, para que también se detengan Ollama y la aplicación si estaban arriba.
docker compose --profile ai --profile app down "${opciones[@]}"
