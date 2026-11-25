# 11 — Recuperación vectorial (TD-012) y latencia de embeddings (TD-029)
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | 🟡 En revisión |
| **Fecha** | 25 de noviembre de 2026 |
| **Documentos relacionados** | `07-spike-bibliotecas-java-25.md` §4, `08-medicion-ollama-y-bakeoff-embeddings.md`, ADR-0002, ADR-0017, ADR-0026, `data-model.md` §5, `anexos/embeddings-esquema/recall/` |

---

## 1. Resumen

| Punto | Estado |
|---|---|
| **TD-012** (recall de la recuperación con filtros) | ✅ **Cerrada.** La regla ya estaba medida pero no aplicada: ahora `nearestDiscoverable` fija `hnsw.iterative_scan = relaxed_order` y `ef_search` nunca por debajo de la página pedida, y una prueba de regresión la protege |
| **TD-029** (latencia de embeddings) | ⏳ **Sigue abierta, con el instrumento listo.** Esta VM no expone AVX2 (`/proc/cpuinfo` sin `avx`, `avx2` ni `fma`), así que **no se puede medir aquí**; `scripts/eval/embedding_latency.py` lo mide en hardware con AVX2 y se niega a hacerlo sin él |

## 2. TD-012: lo que ya se sabía y lo que faltaba

El anexo `recall/` (18 y 20-nov-2026, PostgreSQL 16 y 17) ya había medido con vectores reales que, con un filtro adicional selectivo y sin `hnsw.iterative_scan`, el recall@20 cae a 0,38, y que con `relaxed_order` vuelve a ≈ 1. **Pero el código no aplicaba esa regla**, y su consulta tiene un filtro que el índice parcial no cubre (`embedding_model = :model`). Lo que faltaba era aplicarla y medirla con la consulta real.

### 2.1 Método

- La consulta es la del adaptador (`JdbcEmbeddingRepository.nearestDiscoverable`) con el índice parcial `WHERE discoverable AND embedding IS NOT NULL`.
- Corpus **sintético con geometría de embeddings reales** (60 grupos con una dirección común, coseno medio entre grupos ≈ 0,5, ruido): no uniforme, porque los vectores uniformes aleatorios dan recall 0,28 y no dicen nada. 20 % de perfiles ocultos. 30 consultas por celda.
- El **recall** compara con el top-*k* exacto (sin índice, mismo filtro). «Sin regla» es solo `ef_search`, sin escaneo iterativo, como lo dejaría pgvector.
- **Se fuerza el HNSW** quitando, dentro de una transacción que se revierte, el btree de `embedding_model`; a estos tamaños el planificador a veces elige el escaneo exacto, que es correcto pero no mide el índice. La primera versión de la prueba de regresión no lo forzaba y pasaba sin ejercitar el índice: se detectó con `EXPLAIN` y se corrigió; la prueba ahora **afirma** que el plan usa `idx_pe_embedding_hnsw`.
- PostgreSQL 17 + pgvector 0.8.7 (`pgvector/pgvector:pg17`), en esta VM.

### 2.2 Resultados

**A. Operación normal: 30 % de los vectores son de otro modelo** (el filtro por modelo no es selectivo: pasa el 70 %).

| Perfiles | Página | `ef_search` | Con la regla | Sin la regla |
|---|---|---|---|---|
| 10.000 | 10 / 20 | 40–200 | recall 1,000; página completa | igual |
| 10.000 | **50** | **40** | recall 1,000; **50 filas** | recall **0,556**; **27,8 filas** |
| 10.000 | 50 | 100 / 200 | recall 1,000; 50 filas | recall 1,000; 50 filas |
| 30.000 | 10 / 20 / 50 | 40–200 | recall 0,988–1,000; página completa | igual salvo página 50 con `ef_search` 40 (recall 0,556; 27,8 filas) |

Con un filtro no selectivo la regla apenas cambia el recall; lo que rompe es **pedir una página de 50 con el `ef_search` por defecto de 40**: el índice solo explora 40 candidatos.

**B. Un cambio de modelo en curso: 90 % de los vectores son de otro modelo** (filtro muy selectivo; 10.000 perfiles).

| Página | `ef_search` | Con la regla | Sin la regla |
|---|---|---|---|
| 10 | 40 | recall 0,983; 10 filas | recall **0,357**; **3,6 filas** |
| 20 | 100 | recall 0,950; 20 filas | recall 0,442; 8,8 filas |
| 50 | 40 | recall 0,948; 50 filas | recall **0,071**; **3,6 filas** |
| 50 | 200 | recall 0,962; 50 filas | recall 0,369; 18,5 filas |

Es el caso del que advertía el anexo: sin escaneo iterativo, el filtro por modelo se aplica *después* del recorrido y se pierden casi todos los resultados. Con la regla la página sale completa y con recall ≈ 0,95.

**C. Latencia** (la consulta con el HNSW forzado, en esta VM, 30 consultas por celda): p50 ≈ 26–40 ms y p95 ≈ 31–66 ms en todas las configuraciones, con y sin regla; un único pico de 170 ms atribuible a las pausas de la VM (ver la nota de VM congelada). Muy por debajo de NFR-PERF-04 (3 s). **La regla no tiene coste medible.**

**D. Plan del planificador.** Con 10.000 perfiles unas veces usa el HNSW y otras el escaneo exacto (30.000 y 90 % de otro modelo: escaneo exacto); ambos dan resultados correctos y cuestan ~30 ms. La regla actúa solo si se usa el índice, y no hace daño si no.

### 2.3 Decisión aplicada

1. `nearestDiscoverable` fija con `set_config(..., true)`, **local a la transacción** del caso de uso (no se filtra al *pool*): `hnsw.iterative_scan = relaxed_order` y `hnsw.ef_search = max(configurado, página)`.
2. `ef_search` es configuración (`jobseeker.matching.retrieval.ef-search`, `MATCHING_RETRIEVAL_EF_SEARCH`), por defecto **100**: es el menor de los medidos con recall ≈ 1 y página de 50 completa.
3. Como `relaxed_order` puede entregar los resultados algo desordenados, la consulta se **reordena por distancia** en una consulta exterior (BR-MATCH-06 muestra un orden).

### 2.4 Qué no se midió (límites)

- **Perfiles reales.** El corpus es sintético con geometría realista: mide planes, latencia y el efecto de las reglas, **no** la calidad sobre perfiles reales de la cohorte (eso es el protocolo de 300 pares de ADR-0018). El anexo `recall/` sigue siendo la referencia con vectores reales.
- **Más de 30.000 perfiles.** NFR-SCAL-02 son 10.000; con órdenes de magnitud más, repetir la medición (el procedimiento es esta clase de medición, ver §4).
- **Supabase.** Que su pgvector sea ≥ 0,8.0 (`iterative_scan`) sigue sin verificarse (doc 07 §7); si no lo fuera, el ajuste sería inocuo pero el recall volvería a caer con filtros selectivos. Comprobarlo con `select * from pg_available_extensions where name = 'vector'` antes del primer despliegue (TD-005).
- **Filtros adicionales futuros** (exclusión BR-MATCH-09, cuenta activa): cuando existan, repetir la medición B con ellos.

## 3. TD-029: latencia de embeddings

### 3.1 Por qué no se puede cerrar hoy

La VM de desarrollo no expone AVX/AVX2/FMA (§8.2 del doc 08): `llama.cpp` corre con SSE y un embedding de ~25 tokens tarda segundos. Cualquier cifra de esta VM mediría la VM, no el sistema. Se verificó de nuevo en esta sesión (`/proc/cpuinfo`).

### 3.2 Lo que sí se entrega

`scripts/eval/embedding_latency.py` (solo biblioteca estándar, con pruebas): comprueba que el equipo expone AVX2 y **se niega a medir sin él** (código 2, salvo `--allow-no-avx2` que avisa de que no sirve como cierre), mide `/api/embed` con la petición exacta de la aplicación (`truncate=false`, `num_ctx`, `keep_alive`) para tres tamaños (~25, ~100 y ~400 tokens), en frío y en caliente, con repeticiones y opcionalmente varios `--num-thread`, y compara **p95 + ventana de la cola** con el objetivo de 120 s (código 1 si no cabe).

### 3.3 Cómo cerrar TD-029

1. En el equipo con AVX2 donde correrá Ollama (o su réplica): `python3 -I scripts/eval/embedding_latency.py --repeats 20 --out latencia.json`.
2. Pegar la tabla en `08-…` §8 y archivar `latencia.json` en `anexos/`.
3. Si el p95 no cabe, ver si fijar `--num-thread` por debajo de la cuota de CPU lo estabiliza.
4. ADR-0026 ya exige que el huésped exponga AVX2; comprobarlo con el propio *script*.

## 4. Cómo repetir la medición de recall

```
mvn -pl matching-module -am test -Dtest=RetrievalRecallMeasurement \
    -Dretrieval.measure=true -Dretrieval.sizes=10000,30000 -Dretrieval.otherShare=0.9
```

La clase no corre sin `-Dretrieval.measure=true`. La prueba de regresión `JdbcEmbeddingRetrievalRecallTest` (4.000 perfiles, siempre activa) protege la regla: página completa, recall ≥ 0,95 frente al exacto con el HNSW forzado y comprobado por `EXPLAIN`, orden por similitud, el control negativo (sin regla la página sale incompleta) y que el ajuste no se filtra fuera de la transacción.
