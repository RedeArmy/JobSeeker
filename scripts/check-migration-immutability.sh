#!/usr/bin/env bash
# Comprueba que ninguna migración ya publicada se haya editado, renombrado ni borrado (ADR-0027, decisión 7).
#
# Por qué: Flyway valida la suma de control de cada migración aplicada al arrancar y se niega a
# iniciar si cambió. Eso rompió a ../Clavaris en preproducción el 16-sep-2026, porque ninguna prueba
# parte de una base ya migrada. Agregar un archivo nuevo es siempre válido; cambiar uno que ya llegó
# a la rama base no lo es: la corrección es una migración nueva.
#
# Compara contra el ancestro común de la rama base y HEAD, para que una rama atrasada no vea como
# «borradas» las migraciones que la base añadió después. Necesita historial completo (fetch-depth: 0).
#
# Uso: bash scripts/check-migration-immutability.sh <referencia-base>
set -euo pipefail

base="${1:?uso: check-migration-immutability.sh <referencia-base>}"
# Solo las carpetas de módulo de la raíz: las copias de ejemplo de docs/ no son migraciones reales.
patron='^[^/]+/src/main/resources/db/migration/[^/]+\.sql$'

if ! git rev-parse --verify --quiet "${base}^{commit}" >/dev/null; then
  # Tras un force push, `github.event.before` ya no existe: no hay contra qué comparar.
  echo "AVISO: la referencia base '${base}' no está disponible; se omite la comprobación."
  exit 0
fi

ancestro="$(git merge-base "$base" HEAD || true)"
if [ -z "$ancestro" ]; then
  echo "AVISO: no hay ancestro común con '${base}'; se omite la comprobación."
  exit 0
fi

violaciones=0
while IFS= read -r archivo; do
  [ -n "$archivo" ] || continue
  if ! git cat-file -e "HEAD:${archivo}" 2>/dev/null; then
    echo "ERROR: la migración publicada ${archivo} se borró o se renombró."
    violaciones=$((violaciones + 1))
  elif ! git diff --quiet "$ancestro" HEAD -- "$archivo"; then
    echo "ERROR: la migración publicada ${archivo} se modificó; agrega una migración nueva."
    violaciones=$((violaciones + 1))
  fi
done < <(git ls-tree -r --name-only "$ancestro" | grep -E "$patron" || true)

if [ "$violaciones" -gt 0 ]; then
  echo "Inmutabilidad de migraciones: ${violaciones} infracción(es)."
  exit 1
fi
echo "Inmutabilidad de migraciones: sin cambios en las migraciones publicadas (base ${base})."
