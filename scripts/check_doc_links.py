#!/usr/bin/env python3
"""Falla si la documentación referencia un documento que no existe.

Por qué existe: la documentación se reorganiza y se cita entre sí (ADRs, revisión de arquitectura,
anexos); una referencia rota engaña a quien lee una decisión. Estos documentos citan casi siempre
entre comillas invertidas (`06-spike-contrato-clavaris.md`) y no con enlaces Markdown, así que se
revisan las dos formas:
  - enlaces relativos `[texto](ruta.md)`;
  - referencias `nombre.md` o `carpeta/nombre.md` entre comillas invertidas.
Se omiten las rutas fuera del repositorio (`../Clavaris/...`), las que llevan comodines o llaves
y las anclas (#...) y direcciones http(s).
"""
import re
import subprocess
import sys
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent
ENLACE = re.compile(r"\]\((?!https?:|#|mailto:)([^)\s#]+)(?:#[^)]*)?\)")
REFERENCIA = re.compile(r"`([A-Za-z0-9_./-]+\.md)`")


def versionados() -> set:
    """Archivos que el repositorio versiona (o versionaría): los que el CI ve, no los que solo existen en
    el disco del desarrollador por estar ignorados (un archivo local no puede ser el destino de una cita)."""
    salida = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        cwd=RAIZ, capture_output=True, check=True,
    ).stdout.decode()
    return {RAIZ / ruta for ruta in salida.split("\0") if ruta}


def existe(doc: Path, destino: str, nombres: set, archivos: set) -> bool:
    candidatos = [(doc.parent / destino).resolve(), (RAIZ / destino).resolve(), (RAIZ / "docs" / destino).resolve()]
    if any(c in archivos for c in candidatos):
        return True
    # Una cita por nombre suelto (`data-model.md`) vale si ese archivo existe en docs/.
    if "/" not in destino:
        return destino in nombres
    # Una ruta escrita desde una carpeta intermedia (`architecture-review-2026-11/06-….md`, relativa
    # a docs/03-architecture/) vale si algún documento termina exactamente en esa ruta.
    return any(str(ruta.relative_to(RAIZ)).endswith("/" + destino) for ruta in (RAIZ / "docs").rglob("*.md"))


def main():
    docs = sorted((RAIZ / "docs").rglob("*.md"))
    nombres = {d.name for d in docs}
    archivos = versionados()
    rotos, total = [], 0
    for md in docs:
        texto = md.read_text(encoding="utf-8")
        candidatos = [(d, True) for d in ENLACE.findall(texto)] + [(d, False) for d in REFERENCIA.findall(texto)]
        for destino, es_enlace in candidatos:
            if destino.startswith("..") and not es_enlace:
                continue  # documentos de otros repositorios (p. ej. ../Clavaris/...)
            if "*" in destino or "{" in destino:
                continue
            total += 1
            if not existe(md, destino, nombres, archivos):
                rotos.append(f"{md.relative_to(RAIZ)} -> {destino}")
    for linea in sorted(set(rotos)):
        print(f"ERROR: referencia rota: {linea}")
    print(f"Referencias a documentos revisadas: {total} | rotas: {len(set(rotos))}")
    return 1 if rotos else 0


if __name__ == "__main__":
    sys.exit(main())
