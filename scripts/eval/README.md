# Arnés de medición del matching

Herramienta de medición para dos controles de los ADR-0017 y ADR-0019. Solo usa la biblioteca
estándar de Python: no añade ninguna dependencia al proyecto. **No es parte de la aplicación.**

## Qué NO es

Una cifra solo vale lo que valgan los datos con que se calcula. Hoy el proyecto **no tiene datos
reales**: ni candidatos, ni CV, ni pares etiquetados. Con datos sintéticos la herramienta solo
demuestra que **funciona**, no dice nada sobre el sistema. Los resultados de la tesis salen de
datos reales de la cohorte, etiquetados por dos personas independientes (κ ≥ 0,6).

## Cobertura de ESCO (ADR-0019, controles D3 y D4)

```
python3 -I scripts/eval/matching_eval.py coverage --esco esco_skills_es.csv --skills habilidades.csv
```

- `--esco`: el CSV de habilidades de ESCO en español (columnas `conceptUri`, `preferredLabel`,
  `altLabels`, `hiddenLabels`). Se descarga del portal de ESCO; ver las condiciones de atribución en
  el ADR-0019 (acción derivada 1).
- `--skills`: un CSV con la columna `skill` y, opcional, `source_id` (de qué CV o anuncio sale).

Informa, sobre habilidades **distintas** (una muy repetida no infla la cifra) y sobre menciones:
cuántas se enlazan por etiqueta exacta única, cuántas son ambiguas (nunca se enlazan solas) y cuántas
quedan sin enlace, con intervalo de confianza de Wilson. Lista las más frecuentes sin enlace, que
son las candidatas a habilidades locales.

**Tamaño de muestra:** unas 250 a 300 habilidades distintas dan un intervalo de unos ±5 puntos; con
menos, la herramienta lo avisa. Mide los dos primeros escalones del ADR-0019; el de embeddings sigue
apagado hasta calibrarse.

## Calibración y ranking (ADR-0017, controles C2 y C5)

```
python3 -I scripts/eval/matching_eval.py calibrate --pairs pares_etiquetados.csv
```

CSV con `vacancy_id`, `candidate_id`, `cosine`, `label` (0 a 3; relevante es ≥ 2) y, opcional,
`score` (el puntaje por el que se ordena; por defecto el coseno).

- **Validación agrupada por vacante**, nunca por par: los pares de una misma vacante comparten
  verdad-terreno y, si cayeran a ambos lados, la precisión saldría inflada (C2).
- Compara Platt e isotónica con Brier, pérdida logarítmica, error de calibración esperado (ECE) y AUC,
  con intervalo por *bootstrap* remuestreando **vacantes**, y contra la tasa base.
- P@K y NDCG@K por vacante con su intervalo. Calibrar no cambia el orden (es una función monótona),
  así que el ranking depende de la columna `score`.
- Avisa si hay menos de 30 vacantes (el protocolo pide al menos 30) o menos que pliegues.

## Pruebas

```
python3 -I -m unittest discover -s scripts/eval/tests -t scripts/eval/tests
```

Usan datos sintéticos con verdad conocida (dos clases normales, cuya calibración real es una
logística exacta) y comprueban, entre otras cosas, que Platt la recupera, que el *bootstrap* no
fusiona vacantes repetidas y que una etiqueta ambigua nunca se enlaza sola. Se comprobó además, con
errores introducidos a propósito, que las pruebas detectan los fallos que importan.
