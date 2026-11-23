# ADR-0017: Motor de matching v2: pipeline en tres etapas, calibración, datos faltantes y frescura
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | 🟡 Propuesto — **no está bloqueado hasta que el responsable del proyecto lo apruebe** |
| **Versión** | 1.0 |
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
2. **Se embebe una representación canónica, no el texto crudo.** Se genera de forma determinista desde los datos estructurados de `cv` y excluye nombre, foto, contacto, enlaces personales, género y nombre de institución (`02-hld-system-design.md` §4.3). Tres beneficios: menos sesgo de nombre, un *hash* estable como clave de caché y menos datos personales hacia Ollama (BR-DATA-01). Su construcción es de `matching`, no de `cv`, porque depende del modelo de embeddings (ADR-0018).
3. **Pipeline en tres etapas para las sugerencias de candidatos:** (1) recuperación por vecinos más cercanos sobre un índice HNSW **parcial** (`discoverable` y con vector), con los filtros de cuenta activa y no excluido; (2) puntaje estructurado de las seis dimensiones más la afinidad semántica calibrada sobre los 200 recuperados; (3) orden final con desempate por `OPEN_TO_WORK` y corte a top-N. El desempate **nunca** modifica el puntaje (BR-MATCH-07) y se anula por candidato para reclutadores excluidos (BR-MATCH-09), dentro de la misma consulta.
4. **El puntaje visible es la combinación explícita y calibrada** (esto resuelve la pregunta abierta del ADR-0003):
   `overall = 100 · ((1 − α) · estructurado + α · semántica_calibrada)`, con `α` **visible en el desglose** como una dimensión más («Afinidad semántica»), nunca un ajuste oculto. Valor inicial `α = 0,30`, calibrable y versionado. **El coseno bruto no se muestra nunca.**
5. **Calibración.** El coseno se lleva a [0, 1] con una función monótona ajustada sobre pares etiquetados: sigmoide de Platt por defecto y regresión isotónica si la primera no resulta monótona con la etiqueta. Los parámetros se guardan **por modelo de embeddings** (ADR-0018) y forman parte de `algorithm_version`. Hasta que el protocolo de 300 pares produzca los parámetros, el puntaje se publica marcado como **no calibrado** y no se usa para medir la métrica de precisión.
6. **Datos faltantes: la dimensión se excluye y los pesos se renormalizan** entre las disponibles; el desglose dice «dato no declarado» y la respuesta lleva `completeness ∈ [0, 1]`. Es distinto de BR-ELIG-08, que es *fail-closed*: la elegibilidad es un filtro binario, el score es comparativo y no debe castigar con cero lo que simplemente no se sabe. **Un salario en otra moneda cuenta como dato faltante** (BR-MATCH-10).
7. **Explicación determinista.** Las explicaciones son plantillas sobre el desglose («Coinciden 5 de 7 requisitos; faltan: …»), con la evidencia citada. El LLM generativo queda **fuera de la ruta crítica**: no alucina, no cuesta y se puede probar. El «qué pasaría si» (REQ-C-01) es aritmética pura sobre el mismo agregador, nunca sugiere declarar lo que no se tiene.
8. **Versionado completo.** `algorithm_version` = (versión del código, modelo de embeddings, *hash* de los pesos, *hash* de la calibración). La huella de entrada es
   `SHA-256(hash del perfil canónico ‖ hash de la vacante canónica ‖ versión del código ‖ modelo ‖ hash de pesos ‖ hash de calibración)` y es la clave de caché en Redis (`score:v1:{huella}`), así que cambiar pesos, modelo o calibración cambia la clave sola (F8).
9. **Almacenamiento: vigente más fotos.** Nunca se materializan todos los pares: solo los «con motivo» (postulados, vistos por el candidato, top-N recientes). Una tabla `compatibility_scores_current` (clave `(candidato, vacante)`, con *upsert*) y una `compatibility_score_snapshots` que se escribe **solo** al postular, al cambiar de etapa, al cambiar `algorithm_version` y en muestras periódicas para estudiar deriva. Retención de fotos: 24 meses. Al postular se guarda además una **copia inmutable del perfil canónico** de ese momento (F6): el reclutador evalúa lo que el candidato presentó, no lo que edite después.
10. **Frescura con objetivo explícito, no «instantánea».** BR-MATCH-03 se interpreta como **eventual con límite**: tras editar un perfil, el *p95* del score actualizado es ≤ 90 s (se agrupan ediciones en una ventana de 30 a 60 s y se re-embebe una vez); tras editar una vacante, ≤ 60 s; un cambio de versión del algoritmo se migra por lotes en ≤ 24 h empezando por los postulados activos. Mientras un par está pendiente, la interfaz lo dice («actualizando…»). Con Ollama caído se sirve el último score con aviso y, al volver, la cola se vacía en ≤ 15 min con control de presión.
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
2. Arnés de evaluación del matching: conjunto dorado, P@K, NDCG y prueba contrafactual en CI nocturno (bloque propio de la Ola 1).
3. Tablas `compatibility_scores_current`, `compatibility_score_snapshots` y la copia del perfil canónico al postular, en `data-model.md`, con sus anonimizaciones (BR-DATA-05).
4. Actualizar `data-model.md` §4.7: hoy describe el histórico por recálculo que esta decisión reemplaza.

## Preguntas abiertas

- ¿La ventana de agrupación de ediciones (30–60 s) y el `α` inicial son valores finales? No: se calibran con el conjunto etiquetado y se versionan.
- ¿Qué se muestra cuando `completeness` es muy baja? Propuesta: un aviso «tu perfil tiene pocos datos para comparar» en vez de un número; el umbral se decide con la interfaz.
- ¿Los snapshots periódicos «para estudiar deriva» valen su almacenamiento en el MVP? Se deja el mecanismo y se enciende solo si el arnés lo pide.
