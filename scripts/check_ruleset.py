#!/usr/bin/env python3
"""Valida .github/rulesets/main.json contra los workflows del repositorio.

Por qué: un check requerido cuyo nombre no coincide con ningún job nunca se reporta y bloquea todas
las PRs para siempre; y un ruleset al que se le quita una regla de protección debilita `main` sin que
nadie lo note. Solo usa la biblioteca estándar, como el resto de los verificadores.
"""
import json
import re
import sys
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent
REGLAS_OBLIGATORIAS = {"deletion", "non_fast_forward", "pull_request", "required_status_checks"}


def nombres_de_jobs() -> set:
    # El `name:` de un job tiene exactamente cuatro espacios de sangría; los de pasos van tras `- `.
    patron = re.compile(r"^ {4}name:\s*(.+?)\s*$")
    nombres = set()
    for workflow in (RAIZ / ".github" / "workflows").glob("*.yml"):
        for linea in workflow.read_text(encoding="utf-8").splitlines():
            coincide = patron.match(linea)
            if coincide:
                nombres.add(coincide.group(1).strip("\"'"))
    return nombres


def main() -> int:
    ruleset = json.loads((RAIZ / ".github" / "rulesets" / "main.json").read_text(encoding="utf-8"))
    errores = []
    reglas = {regla["type"]: regla for regla in ruleset["rules"]}

    for tipo in sorted(REGLAS_OBLIGATORIAS - reglas.keys()):
        errores.append(f"falta la regla de protección obligatoria '{tipo}'")
    if ruleset.get("enforcement") != "active":
        errores.append("el ruleset debe estar con enforcement 'active'")
    if ruleset.get("bypass_actors"):
        errores.append("el ruleset no debe permitir saltarse la protección (bypass_actors)")

    if "required_status_checks" in reglas:
        contextos = [c["context"] for c in reglas["required_status_checks"]["parameters"]["required_status_checks"]]
        existentes = nombres_de_jobs()
        for contexto in contextos:
            if contexto not in existentes:
                errores.append(f"el check requerido '{contexto}' no es el nombre de ningún job de los workflows")

    for error in errores:
        print(f"ERROR: {error}", file=sys.stderr)
    if not errores:
        print(f"Ruleset válido: {len(contextos)} checks requeridos, todos corresponden a un job.")
    return 1 if errores else 0


if __name__ == "__main__":
    sys.exit(main())
