#!/usr/bin/env bash
# Falla si un archivo versionado menciona herramientas de asistencia de código o sus archivos locales.
#
# Por qué: el repositorio no debe depender de ellas ni nombrarlas (decisión del responsable del
# proyecto, noviembre de 2026). Esas rutas se ignoran con `.git/info/exclude`, nunca en un archivo
# versionado. La integración de producto con una API de IA (p. ej. `CLAUDE_API_KEY`) NO entra aquí.
#
# El patrón se arma por fragmentos para que este mismo archivo no lo contenga.
set -euo pipefail
cd "$(dirname "$0")/.."

a="cl"; b="aude"
patron="${a}${b}\\.md|${a}${b}\\.local|\\.${a}${b}|${a}${b} code|${a}${b}-code"
# --untracked: también los archivos nuevos que aún no están en un commit.
if git grep -nIiE --untracked -e "$patron" -- . ':!scripts/check-no-tooling-references.sh'; then
  echo "ERROR: hay menciones a herramientas de asistencia de código en archivos versionados." >&2
  exit 1
fi
echo "Sin menciones a herramientas de asistencia de código."
