#!/usr/bin/env bash
# Falla si un archivo versionado deja un marcador de trabajo pendiente sin dueño y razón.
#
# Por qué: coding-standards.md §5 prohíbe los TODO sin dueño ni razón, pero nada lo hacía cumplir; la
# regla S1135 de Sonar no sirve porque confunde la palabra española «todo» con un marcador. Aquí solo
# cuenta el marcador en mayúsculas, y es válido solo con la forma `TODO(dueño): razón`.
#
# El patrón se arma por fragmentos para que este archivo no lo contenga.
set -euo pipefail
cd "$(dirname "$0")/.."

a="TO"; b="DO"; c="FIX"; d="ME"
sin_dueno="\\b(${a}${b}|${c}${d})\\b(?!\\([A-Za-z0-9_.-]+\\): ?\\S)"
if git grep -nIP --untracked -e "$sin_dueno" -- '*.java' '*.yml' '*.yaml' '*.sql' '*.sh' '*.xml' '*.py' '*.properties' ':!scripts/check-todo-markers.sh'; then
  echo "ERROR: hay marcadores de trabajo pendiente sin dueño y razón; usa la forma ${a}${b}(dueño): razón." >&2
  exit 1
fi
echo "Sin marcadores de trabajo pendiente sin dueño y razón."
