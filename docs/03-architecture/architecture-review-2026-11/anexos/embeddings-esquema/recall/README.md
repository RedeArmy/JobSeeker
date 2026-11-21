# Recall de HNSW con vectores reales (18-nov-2026)

- `embed_real.py`: embebe con Ollama (`qwen3-embedding:0.6b`, `truncate=false`, `num_ctx=1024`) los **104 textos reales del bake-off** (80 candidatos y 24 vacantes, con el prefijo de instrucción del modelo para las vacantes). 37 min en esta VM (sin AVX, 2 CPU).
- `real_vectors.json.gz`: los 104 vectores de 1024 dimensiones (sin datos personales: los textos del bake-off son sintéticos).
- `recall.py`: construye tres corpus de 10.000 candidatos, crea el índice HNSW parcial `WHERE discoverable` y compara, para las 24 vacantes reales, el top-20 del índice con el top-20 **exacto** (escaneo secuencial forzado).
- `recall.log` y `recall_result.json`: salida.

## Advertencia metodológica

Los 104 vectores son reales; **los corpus de 10.000 no**. «real_aumentado» son combinaciones convexas de 2-3 vectores reales más ruido gaussiano, renormalizadas: heredan la geometría de embeddings reales (coseno medio entre candidatos reales 0,51; vacante-candidato entre 0,17 y 0,85) pero solo tienen 80 puntos de partida, así que son **más fáciles** de indexar que un corpus real de 10⁴ perfiles distintos. Léanse como una cota optimista. «uniforme_aleatorio» es el peor caso y no representa texto real.

## Resultados (recall@20, media de 24 consultas)

| Corpus | Filtro | `iterative_scan` | `ef_search`=40 | `ef_search`=100 |
|---|---|---|---|---|
| real aumentado (ruido 0,3) | descubribles | off / relaxed_order | 0,998 / 0,998 | 1,000 / 1,000 |
| real aumentado (ruido 0,3) | + país GT (~14 %) | **off** | **0,381** | 0,929 |
| real aumentado (ruido 0,3) | + país GT | relaxed_order | 0,996 | 0,998 |
| real aumentado (ruido 0,8) | descubribles | off / relaxed_order | 0,998 / 0,998 | 1,000 / 1,000 |
| real aumentado (ruido 0,8) | + país GT | **off** | **0,379** | 0,904 |
| real aumentado (ruido 0,8) | + país GT | relaxed_order | 1,000 | 1,000 |
| uniforme aleatorio | descubribles | cualquiera | 0,283 | 0,537 |
| uniforme aleatorio | + país GT | off / relaxed_order | 0,271 / 0,569 | 0,527 / 0,606 |

## Conclusiones

1. Con la geometría de embeddings reales, HNSW con `ef_search` por defecto (40) alcanza recall@20 ≈ 0,998 en la consulta que el índice cubre (candidatos descubribles).
2. **Con un filtro adicional selectivo, sin `hnsw.iterative_scan` el recall cae a 0,38** (el filtro se aplica después del recorrido del índice y se pierden resultados). Con `relaxed_order` vuelve a ≈ 1,0. El escaneo iterativo es **obligatorio** para consultas filtradas, lo que exige pgvector ≥ 0,8.0.
3. Con vectores uniformes aleatorios el recall es bajo (0,28): es el peor caso conocido de los índices de vecino más próximo en alta dimensión y no debe usarse para medir la calidad de la búsqueda; la prueba de latencia del esquema (vectores aleatorios) mide planes y tiempos, no calidad.
4. Con `relaxed_order` los resultados pueden salir ligeramente desordenados por distancia (según la documentación de pgvector, **no verificado aquí**): si el orden exacto importa, reordenar la salida con una consulta externa.

## No verificado

Un corpus real de miles de perfiles distintos (se medirá con el protocolo de 300 pares del ADR-0018 o con datos reales de la cohorte), el recall con filtros más selectivos, y el reordenamiento de `relaxed_order`.

## Repetición en PostgreSQL 17.12 (20-nov-2026, ADR-0028)

Se repitió el mismo `recall.py`, con los mismos 104 vectores reales y la misma semilla, sobre `pgvector/pgvector:pg17` (PostgreSQL 17.12, pgvector 0.8.7). Resultado completo en `recall_result_pg17.json`.

- **Corpus «real aumentado» (los dos niveles de ruido): las 16 cifras coinciden exactamente con las de PostgreSQL 16, a tres decimales.** Se mantiene el hallazgo que importa: con el filtro de país, sin `hnsw.iterative_scan` el recall@20 es 0,38 con `ef_search`=40 (0,90–0,93 con 100) y con `relaxed_order` sube a 0,996–1,000.
- **Corpus uniforme aleatorio (el caso patológico, sin estructura):** diferencias de −0,017 a +0,054 sin dirección clara. Se atribuyen al azar de la construcción del índice y no cambian la conclusión (este corpus no es representativo).
- **No se repitió** la medición de tiempos del DDL de cambio de modelo (`../README.md`), solo el recall.

