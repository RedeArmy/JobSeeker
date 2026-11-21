# ADR-0018: Selección y versionado del modelo de embeddings
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | ✅ Aprobado — 18-nov-2026, por el responsable del proyecto. **Enmienda el ADR-0004** (bloqueado) en lo relativo al modelo de embeddings; el modelo sigue siendo provisional hasta el protocolo de 300 pares |
| **Versión** | 1.1 — validado el 18-nov-2026 (sin cambios de decisión) |
| **Fecha** | Noviembre 2026 |
| **Documento relacionado** | ADR-0003, ADR-0004, ADR-0017 (propuesto en la revisión; aún sin redactar), `architecture-review-2026-11/08-medicion-ollama-y-bakeoff-embeddings.md`, `03-lld.md` §2 y §4.2 |

---

## Contexto

las reglas del proyecto §3/§5 y el ADR-0004 fijan `nomic-embed-text` (v1.5) como modelo de embeddings. No hay ninguna medición en español en la documentación, y el contenido real (Guatemala) es mayormente español con terminología técnica en inglés. El piloto del 14–15-nov-2026 (`08-medicion-ollama-y-bakeoff-embeddings.md`) midió cinco modelos sobre 24 vacantes y 56 candidatos sintéticos:

- `nomic-embed-text` v1.5 obtuvo NDCG@10 = 0,780, **significativamente por debajo de una línea base léxica** (0,848) y de los otros cuatro modelos (+0,11 a +0,13); los prefijos de tarea no cambiaron el resultado.
- `qwen3-embedding:0.6b` (0,906), `nomic-embed-text-v2-moe` (0,900), `embeddinggemma` (0,900) y `bge-m3` (0,887) **no son distinguibles entre sí** con esa muestra.
- El coseno bruto no es comparable entre modelos (el mismo 0,70 equivale a 5,6 % hasta 99,9 % de probabilidad de relevancia).
- Los modelos agrupan por familia pero gradúan mal el encaje fino de habilidades.
- Con la opción por defecto de Ollama el texto largo se **trunca en silencio** (512 tokens en `v2-moe`).

El piloto es sintético y pequeño: **no basta para elegir definitivamente**, sí para descartar un candidato y para fijar reglas de integración.

## Decisión

1. **`nomic-embed-text` v1.5 deja de ser el modelo por defecto.**
2. **Modelo provisional por defecto: `qwen3-embedding:0.6b`**, a confirmar con el protocolo completo de 300 pares etiquetados (`01-competencia-y-requerimientos.md` §6). **Preseleccionados para ese protocolo:** `qwen3-embedding:0.6b`, `bge-m3` y `nomic-embed-text-v2-moe` (este último solo si se implementa troceado, por su contexto de 512 tokens).
3. **El modelo es un parámetro versionado, nunca una constante.** Cada vector guarda `embedding_model`, `embedding_dim` y `embedding_content_hash`; la `algorithm_version` incluye el modelo y los parámetros de calibración (`03-lld.md` §2.1). Cambiar de modelo = nueva `algorithm_version` + reindexado por lotes con doble escritura a un **slot siguiente** (`embedding_next*`: mismas columnas, restricción e índice propios), cambio en una transacción de solo metadatos y sin `ALTER` en caliente. Procedimiento probado y medido en `data-model.md` §5.1 y `anexos/embeddings-esquema/`.
4. **Calibración obligatoria por modelo**: el coseno nunca se muestra; se transforma con una función monótona ajustada a pares etiquetados y se recalibra con cada cambio de modelo.
5. **Reglas de integración (no negociables):**
   - Enviar siempre `truncate=false`; medir los tokens antes de llamar; si el texto excede el contexto, aplicar una estrategia explícita y registrada (`embedding_truncated`).
   - Fijar `num_ctx` explícito por modelo (el *runner* de Ollama usó 4.096 con un modelo capaz de 32.768). **Verificado con Spring AI 2.0.2 el 18-nov-2026** capturando la petición HTTP real: `truncate=false` y `keep_alive` configurados como propiedades globales **sí** se envían (y sobreviven a las opciones por llamada), pero **`num_ctx` configurado como propiedad global NO se envía** (`"options": {}`), sin ningún aviso: una configuración que parece correcta no hace nada. Hay que pasarlo **por llamada** (`OllamaEmbeddingOptions.builder().numCtx(…)`), y el adaptador debe tener una prueba que capture el cuerpo HTTP y compruebe `truncate:false` y `options.num_ctx`. Con ambos enviados, Ollama rechaza un texto que excede el contexto al instante (HTTP 400, «the input length exceeds the context length»), que Spring AI lanza como `NonTransientAiException`: el adaptador la captura, aplica la estrategia de reducción registrada y marca `embedding_truncated`.
   - Cada modelo se invoca con su **formato recomendado** (prefijos o instrucciones); el formato forma parte de la `algorithm_version`.
   - Embeber únicamente la **representación canónica sin nombre** (`02-hld-system-design.md` §4.3).
6. **La dirección de cálculo se decide con datos**: el score es bidireccional y el piloto solo midió vacante → candidatos. El protocolo completo mide también candidato → vacante y la alternativa de vector único simétrico antes de congelar el esquema.

## Alternativas consideradas

| Alternativa | Por qué se descarta o se pospone |
|---|---|
| Mantener `nomic-embed-text` v1.5 | Peor que una línea base léxica y que cuatro alternativas, con diferencia significativa |
| `bge-m3` | Estadísticamente empatado, con el mejor comportamiento entre idiomas y contexto de 8.192, pero el más lento (97 ms/token en la VM de prueba) y con menos separación entre relevantes e irrelevantes. **Preseleccionado** |
| `nomic-embed-text-v2-moe` | Empatado y rápido, pero contexto de 512 tokens: un CV canónico real lo excede. Solo con troceado. **Preseleccionado condicionado** |
| `embeddinggemma` | Empatado; coste intermedio, contexto de 2.048 y licencia a verificar. No preseleccionado |
| LLM externo (API) para embeddings | Contradice la decisión de costo y privacidad del ADR-0004; se reserva para casos selectivos |
| Elegir ahora sin protocolo completo | El piloto es sintético y favorece al método léxico; elegir con él sería falsa precisión |

## Consecuencias

**Positivas**
- Se corrige una decisión con evidencia medida y se evita basar la métrica principal de la tesis en el modelo peor evaluado.
- El versionado de modelo y calibración permite cambiar de modelo sin migraciones traumáticas.

**Negativas / trade-offs aceptados**
- Vectores de 1024 dimensiones en lugar de 768 (≈ 35 % más de almacenamiento; a escala de 10⁴ perfiles sigue siendo del orden de decenas de MB).
- `qwen3` y `gemma` usan formatos distintos para consulta y documento, lo que complica el score bidireccional.
- Licencias de los tres preseleccionados **verificadas en sus fichas oficiales el 16-nov-2026** (Apache-2.0, MIT y Apache-2.0; ver `08-…` §5). Quedan reservas menores: no se leyeron los archivos `LICENSE`, ni las licencias de los datos de entrenamiento, y `embeddinggemma` (Gemma Terms of Use según fuentes secundarias) no se pudo verificar.

## Acciones derivadas

- ✅ Hecho el 18-nov-2026: enmendados las reglas del proyecto §3, §5 y §10 y añadida la nota de enmienda en el ADR-0004. ✅ Hecho el 18-nov-2026: `integration-design.md` §2 y `data-model.md` §3, §4.4, §4.6, §4.7, §5 y §5.1 (columnas de versionado).
- ~~Verificar las licencias~~ Hecho (ver Consecuencias). Pendiente: leer los archivos `LICENSE` de los tres modelos antes de redistribuir pesos en imágenes publicadas.
- Ejecutar el protocolo completo en hardware con AVX2 (la VM de prueba no expone AVX).

## Preguntas abiertas

- ¿Se guardan dos vectores por entidad (consulta y documento) o se adopta un formato simétrico? (se decide con la medición candidato → vacante)
- Estrategia de troceado para CV largos (resumen determinista por secciones vs. promedio de fragmentos).
- ¿Se evalúa un *reranker* o un cuantizado distinto del `qwen3` Q8_0 tras el protocolo completo?

## Validación previa a la aprobación (18-nov-2026)

**Veredicto: aprobar tal como está.** Lo que se aprueba es la **decisión de proceso**, no un modelo definitivo: dejar de usar `nomic-embed-text` v1.5 por defecto, tratar el modelo como parámetro versionado y calibrado, y fijar `qwen3-embedding:0.6b` solo como valor provisional hasta el protocolo de 300 pares. Es lo que el ADR ya dice.

- **Cifras contrastadas** con `08-medicion-ollama-y-bakeoff-embeddings.md`: NDCG@10 de 0,906, 0,900, 0,900, 0,887, línea base léxica 0,848 y `nomic` v1.5 0,780, y el mismo coseno 0,70 ↔ 5,6 % de probabilidad con `nomic` v1.5. Todas coinciden.
- **Coherencia con ADR-0003/0004:** la capa semántica sigue siendo parte del modelo híbrido; el ADR-0004 (Ollama por defecto) no cambia, solo cambia el modelo concreto. Al aprobar, añadir en ADR-0004 y las reglas del proyecto §3/§5 la nota «enmendado por ADR-0018» (ya listado en acciones derivadas).
- **Pendientes que no bloquean la aprobación:** tiempos en hardware con AVX2 (la VM de prueba no lo expone); el protocolo completo (300 pares, sesgo por nombres, dirección candidato→vacante); confirmar que Spring AI 2.0.2 expone la opción `truncate=false` y `num_ctx` de Ollama (el spike solo probó contra un servidor simulado); ~~el almacenamiento con 1024 dimensiones~~ **ejecutado el 18-nov-2026** (10.000 filas, HNSW parcial de 54 MB, top-20 con mediana de 4,6 ms; procedimiento de cambio de modelo probado de 1024 a 768 dimensiones). Recall medido el 18-nov-2026 con los 104 vectores reales del bake-off sobre corpus semisintéticos de 10.000: ≈ 0,998 con el predicado del índice y **0,38 con un filtro selectivo sin `hnsw.iterative_scan`** (≈ 1,0 con él); cota optimista, falta un corpus real. Sigue sin probarse en el Postgres de Supabase.

