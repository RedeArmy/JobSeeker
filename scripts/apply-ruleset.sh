#!/usr/bin/env bash
# Aplica .github/rulesets/main.json al repositorio: lo crea o, si ya existe uno con ese nombre, lo
# actualiza (idempotente). Requiere `gh` autenticado con permisos de administración.
#
# Por qué existe: un ruleset se configura por API, no por un archivo que GitHub lea; versionarlo aquí
# deja la protección de `main` revisable en una PR y reproducible, y `check_ruleset.py` comprueba en
# el CI que cada check requerido corresponda a un job que existe. Aplicarlo es un paso manual y
# posterior a fusionar la PR que cambia el archivo: un check requerido que aún no existe en `main`
# bloquearía la propia PR que lo introduce.
set -euo pipefail
cd "$(dirname "$0")/.."

archivo=".github/rulesets/main.json"
python3 scripts/check_ruleset.py
repo="$(gh repo view --json nameWithOwner -q .nameWithOwner)"
nombre="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["name"])' "$archivo")"
id="$(gh api "repos/${repo}/rulesets" -q ".[] | select(.name==\"${nombre}\") | .id")"

if [ -n "$id" ]; then
  gh api -X PUT "repos/${repo}/rulesets/${id}" --input "$archivo" -q '"actualizado: ruleset \(.id) (\(.enforcement))"'
else
  gh api -X POST "repos/${repo}/rulesets" --input "$archivo" -q '"creado: ruleset \(.id) (\(.enforcement))"'
fi
