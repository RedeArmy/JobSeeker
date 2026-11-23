# ADR-0017: Motor de matching v2: pipeline en tres etapas, calibración, datos faltantes y frescura
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | ✅ **Aprobado con condiciones** — 23-nov-2026, por instrucción expresa del responsable del proyecto, tras una revisión técnica con el rol de SDE III senior. Es una revisión **asistida por IA**: no sustituye una revisión humana independiente, y así queda registrado. Las condiciones de la revisión están **incorporadas a las decisiones**; los controles de aceptación C1 a C5 se verifican al implementar (sección «Revisión técnica») |
| **Versión** | 1.1 — revisión técnica del 23-nov-2026 incorporada (decisiones 2, 3, 5, 6, 9 y 10); 1.0 — propuesta |
| **Fecha** | 23-nov-2026 |
| **Documento relacionado** | Hallazgos F2, F3, F6, F7 y F8 de `architecture-review-2026-11/00-resumen-ejecutivo.md`, `02-hld-system-design.md` §4 y §5, `03-lld.md` §2, ADR-0003 (híbrido), ADR-0016 (bandeja de salida), ADR-0018 (embeddings), ADR-0019 (taxonomía), BR-MATCH-01 a 10, BR-ELIG-06, BR-CAND-07 |

---

## Contexto

El ADR-0003 fijó un modelo híbrido (capa semántica más capa estructurada ponderada), pero dejó abiertas cuestiones que la revisión de noviembre calificó de bloqueantes antes de escribir `matching`:

- **F2: el coseno no es un porcentaje.** La similitud coseno de un modelo de embeddings no está calibrada a 0–100; mostrarla como «87 %» sin calibrar engaña al usuario y rompe en espíritu BR-MATCH-02.
- **F3: «precisión ≥ 0,85» no es medible** sin una verdad-terreno, un protocolo de etiquetado y un tamaño de muestra. Con 100 pares el intervalo de confianza del 95 % de p = 0,85 es de ±7 puntos.
- **F7: crecimiento sin control.** El modelo de datos guarda una fila nueva por cada recálculo de cada par; con 10 000 perfiles y 1 000 vacantes los pares posibles son 10⁷.
- **F8: clave de caché incompleta.** `score:{cv_hash}:{job_hash}` no incluye la versión del algoritmo ni los pesos: tras calibrar se servirían scores obsoletos.
- **F6: score «vivo» frente a foto de la postulación.** Un candidato puede inflar su perfil después de postular.
- BR-MATCH-03 pide que el score «nunca esté desactualizado», lo cual es inalcanzable en sentido estricto con un cálculo que llama a un servicio externo (Ollama).

Este ADR decide el diseño del motor; las fórmulas por dimensión, los puertos y las tablas están en `03-lld.md` §2 y §4 y se completan en el TDD de `matching`.

## Decisión

1. **Embeddings por entidad, no por par.** La única llamada cara (Ollama) ocurre cuando cambia el **contenido** de un perfil o de una vacante, y se guarda el vector. El score de un par es aritmética sobre vectores y estructuras ya calculados, en milisegundos. El caso de uso de puntaje **nunca** llama a Ollama: lee vectores existentes. Si falta el vector, el par queda `PENDING_EMBEDDING` y se reintenta; **jamás** se calcula con un vector parcial.
2. **Se embebe una representación canónica, no el texto crudo.** Se genera de forma determinista desde los datos estructurados de `cv` y excluye nombre, foto, contacto, enlaces personales, género y nombre de institución (`02-hld-system-design.md` §4.3). Tres beneficios: menos sesgo de nombre, un *hash* estable como clave de caché y menos datos personales hacia Ollama (BR-DATA-01). Su construcción es de `matching`, no de `cv`, porque depende del modelo de embeddings (ADR-0018). **Los textos libres que entran (descripción de experiencias) pasan antes por una redacción de datos personales** (correos, teléfonos, enlaces y similares): excluir campos por nombre no basta, porque una descripción puede contener un nombre o un contacto.
3. **Pipeline en tres etapas para las sugerencias de candidatos:** (1) recuperación por vecinos más cercanos sobre un índice HNSW **parcial** (`discoverable` y con vector), con los filtros de cuenta activa y no excluido; (2) puntaje estructurado de las seis dimensiones más la afinidad semántica calibrada sobre los 200 recuperados; (3) orden final con desempate por `OPEN_TO_WORK` y corte a top-N. El desempate **nunca** modifica el puntaje (BR-MATCH-07) y se anula por candidato para reclutadores excluidos (BR-MATCH-09), dentro de la misma consulta. **Control C1:** la recuperación por similitud semántica puede dejar fuera a un candidato de buen ajuste estructural pero poco parecido en texto; el *recall* de los 200 recuperados frente a puntuar a todos se mide sobre el conjunto dorado y debe ser **≥ 0,95**, o se amplía el tope.
4. **El puntaje visible es la combinación explícita y calibrada** (esto resuelve la pregunta abierta del ADR-0003):
   `overall = 100 · ((1 − α) · estructurado + α · semántica_calibrada)`, con `α` **visible en el desglose** como una dimensión más («Afinidad semántica»), nunca un ajuste oculto. Valor inicial `α = 0,30`, calibrable y versionado. **El coseno bruto no se muestra nunca.**
5. **Calibración.** El coseno se lleva a [0, 1] con una función monótona ajustada sobre pares etiquetados: sigmoide de Platt por defecto y regresión isotónica si la primera no resulta monótona con la etiqueta. Los parámetros se guardan **por modelo de embeddings** (ADR-0018) y forman parte de `algorithm_version`. Hasta que el protocolo de 300 pares produzca los parámetros, el puntaje se publica marcado como **no calibrado** y no se usa para medir la métrica de precisión. **Control C2:** los mismos pares no pueden calibrar y evaluar a la vez, o la precisión saldría inflada. Los pares se **separan por vacante, no por par** (las 10 de una misma vacante van juntas, porque comparten la verdad-terreno) en calibración y reserva, o con validación cruzada agrupada por vacante, y la precisión se informa con su intervalo de confianza.
6. **Datos faltantes: la dimensión se excluye y los pesos se renormalizan** entre las disponibles; el desglose dice «dato no declarado» y la respuesta lleva `completeness ∈ [0, 1]`. **Omitir un dato no debe poder subir el puntaje:** quien no declara salario o ubicación evita sus penalizaciones y, renormalizado, podría quedar por encima de un perfil completo. Por eso en las **listas de sugeridos solo entran perfiles con `completeness ≥ 0,5`** (valor configurable y versionado) y el puntaje se muestra siempre junto a su completitud. **Esto mitiga, no elimina:** omitir un dato que habría desentonado puede seguir subiendo el puntaje. Lo que sí se garantiza (**control C3**, prueba de propiedad) es que declarar un dato que coincide nunca baja el puntaje frente a omitirlo, de modo que nadie sale perjudicado por completar su perfil con datos favorables. Es distinto de BR-ELIG-08, que es *fail-closed*: la elegibilidad es un filtro binario, el score es comparativo y no debe castigar con cero lo que simplemente no se sabe. **Un salario en otra moneda cuenta como dato faltante** (BR-MATCH-10).
7. **Explicación determinista.** Las explicaciones son plantillas sobre el desglose («Coinciden 5 de 7 requisitos; faltan: …»), con la evidencia citada. El LLM generativo queda **fuera de la ruta crítica**: no alucina, no cuesta y se puede probar. El «qué pasaría si» (REQ-C-01) es aritmética pura sobre el mismo agregador, nunca sugiere declarar lo que no se tiene.
8. **Versionado completo.** `algorithm_version` = (versión del código, modelo de embeddings, *hash* de los pesos, *hash* de la calibración). La huella de entrada es
   `SHA-256(hash del perfil canónico ‖ hash de la vacante canónica ‖ versión del código ‖ modelo ‖ hash de pesos ‖ hash de calibración)` y es la clave de caché en Redis (`score:v1:{huella}`), así que cambiar pesos, modelo o calibración cambia la clave sola (F8).
9. **Almacenamiento: vigente más fotos.** Nunca se materializan todos los pares: solo los «con motivo» (postulados, vistos por el candidato, top-N recientes). Una tabla `compatibility_scores_current` (clave `(candidato, vacante)`, con *upsert*) y una `compatibility_score_snapshots` que se escribe **solo** al postular, al cambiar de etapa, al cambiar `algorithm_version` y en muestras periódicas para estudiar deriva. Retención de fotos: 24 meses. Al postular se guarda además una **copia inmutable del perfil canónico** de ese momento (F6): el reclutador evalúa lo que el candidato presentó, no lo que edite después. **La copia y las fotos se anonimizan con la cuenta** (BR-DATA-05): aunque la representación canónica excluye los campos de identidad, su texto libre es un dato derivado de la persona.
10. **Frescura con objetivo explícito, no «instantánea».** BR-MATCH-03 se interpreta como **eventual con límite** (NFR-PERF-07): tras editar un perfil o una vacante, el *p95* del score actualizado es **≤ 120 s**. Se agrupan ediciones en una ventana de **30 s**, se re-embebe una vez y el modelo se mantiene residente en memoria (`keep_alive`) para no pagar su carga en frío. El objetivo sale de lo medido (`08-medicion-ollama-y-bakeoff-embeddings.md`): con `qwen3-embedding:0.6b` son **42 ms por token**, así que un perfil canónico de 500 a 800 tokens tarda de **21 a 34 s** en una CPU libre, más **29 s** si el modelo estaba descargado; 30 s de agrupación más 34 s de cálculo ya son 64 s antes de cualquier cola. **Control C4:** se mide con Ollama real antes de fijar el objetivo en el panel de alertas, y si el *p95* no cabe se ajusta el objetivo, no la medición.
    Un cambio de `algorithm_version` se migra por lotes: el **lote prioritario** (postulados activos y pares vistos recientemente) en ≤ 24 h; el **reprocesado completo** de 10 000 perfiles cuesta de **58 a 93 horas** de un solo trabajador en CPU (10 000 × 500 a 800 tokens × 42 ms), así que corre en segundo plano con el procedimiento de doble ranura del ADR-0018 y nunca bloquea el servicio. Mientras un par está pendiente, la interfaz lo dice («actualizando…»). Con Ollama caído se sirve el último score con aviso y, al volver, la cola se vacía en ≤ 15 min con control de presión.
11. **Cola de trabajos en PostgreSQL**, no un *broker*: tabla de trabajos con `FOR UPDATE SKIP LOCKED`, alimentada por la bandeja de salida (ADR-0016). Es la misma técnica que ya usa la bandeja y no añade infraestructura.
12. **La equidad se mide, no se supone.** Cada cambio de `algorithm_version` pasa una prueba contrafactual (cambiar solo el nombre, el género o la institución del perfil no mueve el puntaje más de un umbral) y una ablación de la exclusión de la institución. Un cambio **no se promueve** si P@10 o NDCG@10 caen más de 2 puntos o si falla la contrafactual (`01-competencia-y-requerimientos.md` §6).

## Alternativas consideradas

| Alternativa | Por qué se rechazó |
|---|---|
| Mostrar el coseno como porcentaje | Es un número sin significado en 0–100; engaña y no es defendible ante una prueba de precisión |
| Un único puntaje semántico, sin capa estructurada | Ya descartado en el ADR-0003: no es explicable ni configurable |
| Un ajuste oculto de la mezcla (el `α` no se muestra) | Rompe el principio de explicabilidad (BR-MATCH-02): el usuario no sabría de dónde sale su número |
| Datos faltantes como cero | Castiga a quien no declaró un dato; confunde «no sé» con «no cumple» |
| Recalcular todos los pares en cada cambio | 10⁷ pares: inviable en almacenamiento y en tiempo |
| Un *broker* de mensajes para la cola de embeddings | Infraestructura nueva para un volumen que PostgreSQL con `SKIP LOCKED` absorbe de sobra |
| LLM generativo para explicar el score | No reproducible, con costo y riesgo de alucinar; las plantillas sobre el desglose son verificables |

## Consecuencias

- **Positivas:** el número que se muestra es defendible y reproducible; cambiar pesos o modelo no sirve scores viejos; el almacenamiento está acotado; el coste de Ollama es por cambio de contenido, no por consulta; la métrica de precisión es medible.
- **Costos:** el motor tiene más piezas que un cálculo directo (cola, calibración, fotos, huella); hay que etiquetar 300 pares y mantener un arnés de evaluación (+1 sprint, ya en la hoja de ruta); la interfaz debe comunicar estados pendientes.
- **Riesgos:** (a) la calibración depende de la calidad del etiquetado; (b) si el modelo de embeddings cambia, hay que recalibrar y migrar; (c) el SLO de 90 s depende de que Ollama responda: se monitorea con las métricas del ADR-0012.

## Acciones derivadas

1. TDD de `matching` (`tdd/matching-module.md`), que baja esta decisión a casos de uso, tablas y orden de entrega.
2. Arnés de evaluación del matching: conjunto dorado, P@K, NDCG y prueba contrafactual en CI nocturno (bloque propio de la Ola 1). Ya existe una primera herramienta de medición, sin dependencias, que calcula la calibración con validación agrupada por vacante e intervalos de confianza: `scripts/eval/README.md`. Está probada con datos sintéticos de verdad conocida; **todavía no se ha ejecutado con datos reales**, que no existen.
3. Tablas `compatibility_scores_current`, `compatibility_score_snapshots` y la copia del perfil canónico al postular, en `data-model.md`, con sus anonimizaciones (BR-DATA-05).
4. Actualizar `data-model.md` §4.7: hoy describe el histórico por recálculo que esta decisión reemplaza.

## Preguntas abiertas

- ¿La ventana de agrupación de ediciones (30–60 s) y el `α` inicial son valores finales? No: se calibran con el conjunto etiquetado y se versionan.
- ¿Qué se muestra cuando `completeness` es muy baja? Propuesta: un aviso «tu perfil tiene pocos datos para comparar» en vez de un número; el umbral se decide con la interfaz.
- ¿Los snapshots periódicos «para estudiar deriva» valen su almacenamiento en el MVP? Se deja el mecanismo y se enciende solo si el arnés lo pide.

## Revisión técnica (23-nov-2026)

Revisión con el rol de SDE III senior, asistida por IA y a petición del responsable del proyecto. Se contrastó el ADR con los ADR ya aprobados (0003, 0014, 0016, 0018), con las mediciones del proyecto (`08-medicion-ollama-y-bakeoff-embeddings.md`) y con la coherencia interna de sus propias cifras.

**Veredicto: aprobado con condiciones.** La dirección es correcta y bien fundada: embeddings por entidad y no por par, puntaje calibrado y explicable, versionado completo y almacenamiento acotado resuelven los hallazgos F2, F3, F6, F7 y F8, y **cierran la pregunta abierta del ADR-0003** sobre el peso de la capa semántica. No contradice ningún ADR aprobado: completa el 0003, usa la bandeja del 0016 y el modelo provisional del 0018.

| # | Hallazgo | Gravedad | Tratamiento |
|---|---|---|---|
| R1 | **Omitir un dato podía subir el puntaje**: renormalizar al excluir una dimensión premia a quien declara menos | Alta | Decisión 6 corregida: completitud mínima en sugeridos y prueba de propiedad (C3). **Riesgo residual aceptado:** omitir un dato que desentonaría aún puede ayudar; se vigila con la completitud visible |
| R2 | **El objetivo de frescura de 90 s no cabía en lo medido**: 30–60 s de agrupación más 21–34 s de embedding, más 29 s de carga en frío | Alta | Decisión 10 corregida: 120 s, modelo residente y medición con Ollama real (C4) |
| R3 | **«Lote principal ≤ 24 h» era ambiguo**: el reprocesado completo de 10 000 perfiles son 58–93 h de CPU | Media | Decisión 10: se distingue lote prioritario del reprocesado completo, con su duración |
| R4 | **Calibrar y evaluar con los mismos 300 pares infla la precisión**, y la métrica de éxito (≥ 0,85) se mediría sobre datos de ajuste | Alta | Decisión 5: separación por vacante y validación cruzada agrupada (C2) |
| R5 | **La representación canónica no es libre de datos personales**: su texto libre puede contener nombres o contactos, y la copia al postular la conservaba | Alta | Decisiones 2 y 9: redacción de datos personales antes de embeber y anonimización de las copias |
| R6 | **La recuperación por similitud puede perder candidatos de buen ajuste estructural**, el costo conocido de un pipeline en etapas | Media | Decisión 3: medir el *recall* de los 200 frente a puntuar a todos (C1) |
| R7 | **BR-MATCH-03 dice «siempre actualizado»** y el ADR lo reinterpreta como eventual con límite | Media | Se actualiza la justificación de la regla y se crea NFR-PERF-07, que `data-model.md` ya citaba sin existir |
| R8 | Los umbrales de la prueba contrafactual («un umbral») no están definidos | Baja | Control C5: se fijan con el conjunto dorado antes de promover la primera `algorithm_version` |

**Controles de aceptación** (se verifican al implementar; ninguno bloquea la aprobación de la dirección):

- **C1** — *recall* de la recuperación ≥ 0,95 frente a puntuar a todos, sobre el conjunto dorado.
- **C2** — separación de calibración y evaluación por vacante, con intervalo de confianza.
- **C3** — prueba de propiedad: declarar un dato que coincide nunca baja el puntaje frente a omitirlo.
- **C4** — frescura medida con Ollama real, con el modelo residente, antes de publicar el objetivo.
- **C5** — umbral numérico de la prueba contrafactual fijado antes de promover una versión.

**Lo que esta revisión no cubre.** No valida con datos reales la calibración ni la precisión: eso exige el protocolo de 300 pares. Tampoco reemplaza la revisión de una persona ajena al proyecto, que para una tesis es deseable.
