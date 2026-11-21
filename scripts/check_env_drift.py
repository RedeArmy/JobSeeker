#!/usr/bin/env python3
"""Comprueba que .env.example y la configuración no se desfasen.

Por qué existe: .env.example es el único catálogo de los secretos y puntos de conexión de cada
entorno. Si la configuración (application-*.yml, docker-compose*.yml) usa una ${VARIABLE} que no
está declarada ahí, quien despliegue no sabe que debe definirla y el arranque falla tarde.

Reglas:
  - Falla (código 1) si una ${VARIABLE} usada en configuración NO está en .env.example.
  - Avisa (no falla) de las variables declaradas que nada usa todavía; pasar --strict las trata
    como error. Mientras la configuración sean solo marcadores de posición, casi todas lo estarán.
"""
import re
import sys
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent
USO = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)")
DECLARACION = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*)=", re.MULTILINE)
# Variables que Docker Compose y el entorno definen por sí mismos: no son secretos de la aplicación.
IMPLICITAS = {"HOME", "PWD", "USER", "PATH"}


def archivos_de_configuracion():
    patrones = ["**/application*.yml", "**/application*.yaml", "docker-compose*.yml", "docker-compose*.yaml"]
    for patron in patrones:
        for ruta in RAIZ.glob(patron):
            if "target" in ruta.parts or ".git" in ruta.parts:
                continue
            yield ruta


def sin_comentarios(texto):
    # Un ${VAR} dentro de un comentario YAML no es una dependencia real.
    return "\n".join(linea.split(" #")[0] for linea in texto.splitlines() if not linea.lstrip().startswith("#"))


def main():
    estricto = "--strict" in sys.argv
    declaradas = set(DECLARACION.findall((RAIZ / ".env.example").read_text(encoding="utf-8")))
    usadas = {}
    for ruta in archivos_de_configuracion():
        for nombre in USO.findall(sin_comentarios(ruta.read_text(encoding="utf-8"))):
            usadas.setdefault(nombre, set()).add(str(ruta.relative_to(RAIZ)))

    faltan = {n: f for n, f in usadas.items() if n not in declaradas and n not in IMPLICITAS}
    sobran = sorted(declaradas - set(usadas))

    for nombre, fuentes in sorted(faltan.items()):
        print(f"ERROR: ${{{nombre}}} se usa en {', '.join(sorted(fuentes))} pero no está en .env.example")
    if sobran:
        nivel = "ERROR" if estricto else "AVISO"
        print(f"{nivel}: {len(sobran)} variable(s) de .env.example que ninguna configuración usa todavía")
    print(f"Declaradas: {len(declaradas)} | usadas por la configuración: {len(usadas)} | faltan: {len(faltan)}")
    return 1 if faltan or (estricto and sobran) else 0


if __name__ == "__main__":
    sys.exit(main())
