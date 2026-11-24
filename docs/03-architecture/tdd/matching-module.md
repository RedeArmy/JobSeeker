# TDD — `matching-module`
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | 🟡 En revisión — depende de ADR-0017 y ADR-0019, **aprobados con condiciones el 23-nov-2026**; sus controles de aceptación (C1 a C5 y D1 a D4) entran en el orden de entrega |
| **Versión** | 1.0 |
| **Fecha** | 23-nov-2026 |
| **Documentos relacionados** | ADR-0017 (diseño del motor), ADR-0018 (embeddings), ADR-0019 (taxonomía), ADR-0016 (bandeja de salida), ADR-0003, `03-lld.md` §2, `02-hld-system-design.md` §4 y §5, `tdd/cv-module.md` §7, BR-MATCH-01 a 10, BR-VIS-01 a 08, BR-ELIG-06, BR-CAND-07, NFR-PERF-01, 04 y 07, NFR-AVAIL-02 |

---

## 1. Qué cubre este documento

El *Technical Design Document* del módulo `matching`: el motor de compatibilidad que es el diferenciador del producto (~5 sprints de la Ola 1, `roadmap-and-release-plan.md` §3). Las **decisiones** del motor están en el ADR-0017 y la fórmula por dimensión en `03-lld.md` §2.3; aquí se fija **cómo se construye**: casos de uso, piezas, tablas, configuración, pruebas y orden de entrega. Es el documento que, según la regla del proyecto, **nombra las carpetas de casos de uso** de `matching` (hoy solo existe `calculatecompatibilityscore`).

### 1.1 Qué entra y qué no

| Entra | Fuera, con su dueño |
|---|---|
| Embeddings de perfiles y vacantes, su cola y su frescura | Etiquetado de los 300 pares, métricas P@K y NDCG y la prueba contrafactual en CI: bloque «Arnés de evaluación del matching» (+1 sprint), que consume lo que este módulo expone |
| Puntaje por dimensión, agregación, calibración, explicación y «qué pasaría si» | Verificación del reclutador, cuota diaria de perfiles y datos de contacto enmascarados (ADR-0025 y bloque de verificación) |
| Almacenamiento del score vigente, fotos y copia del perfil al postular | La interfaz del desglose y de los sugeridos: se diseña con `ats` y `tracker` |
| Sugerencia de candidatos por vacante (BR-MATCH-06 a 09) | Elegibilidad y screening: son de `job` y **nunca** alteran el score (BR-ELIG-06) |
| Migración de `algorithm_version` por lotes | La taxonomía de habilidades: ADR-0019, dueño `cv` |

## 2. Reparto de responsabilidades

| Pieza | Dónde | Por qué allí |
|---|---|---|
| Dimensiones, agregador, calibrador, plantillas de explicación | `matching`, `domain.service` | Aritmética pura y sin Spring: es lo más probado y lo más cambiante (ARCH-01) |
| Casos de uso y puertos | `matching`, `application.usecase.<caso>` | Un caso de uso por carpeta; el límite transaccional es el caso de uso |
| Lectura de perfil y de vacante | `infrastructure.adapter.out.cv` y `…out.job` | Convierten la lectura pública de `cv` y `job` en la **proyección propia** de `matching` (`CandidateSignals`, `JobSignals`): ningún tipo de otro módulo entra al dominio (ARCH-04, capa anticorrupción) |
| Cliente de embeddings | `infrastructure.adapter.out.ai` | Spring AI confinado aquí (ADR-0014); Ollama por defecto, con `truncate=false` y `num_ctx` explícito (ADR-0018) |
| Vectores, scores y cola | `infrastructure.adapter.out.persistence` | JDBC con pgvector; ninguna entidad JPA fuera de aquí (ARCH-07) |
| Caché de scores | `infrastructure.adapter.out.cache` | Redis, clave por huella de entrada (ADR-0017, decisión 8) |
| Consumidores de eventos | `infrastructure.adapter.in.event` | Reciben `ProfileUpdated`, `JobPostingUpdated`… desde la bandeja, idempotentes (ADR-0016) |
| Controladores (`/api/v1/matching/**`) | `infrastructure.adapter.in.web` | ARCH-08 |

## 3. Modelo de dominio

Registros inmutables en `domain.model`, sin Spring ni JPA (la forma ya está en `03-lld.md` §2.1):

| Tipo | Contenido |
|---|---|
| `DimensionScore` | dimensión, puntaje 0–1, si estaba disponible, peso y la evidencia que lo explica |
| `ScoreBreakdown` | las seis dimensiones, la afinidad semántica calibrada, `α` y `completeness` |
| `CompatibilityScore` | candidato, vacante, puntaje 0–100, desglose, `AlgorithmVersion`, huella de entrada, fecha |
| `AlgorithmVersion` | versión del código, modelo de embeddings, *hash* de pesos y *hash* de calibración |
| `Weights` y `Calibration` | pesos por dimensión (suman 1 tras renormalizar) y parámetros `(a, b)` por modelo |
| `CandidateSignals` / `JobSignals` | proyecciones de lectura: habilidades enlazadas o en texto, años relevantes, nivel educativo, idiomas, ubicación, modalidad, **salario con moneda y periodo** |

**Cambios respecto a `03-lld.md` §2.3**, que es anterior a decisiones posteriores:

- **Salario:** solo se comparan importes de la **misma moneda** llevados al mismo periodo (anual = 12 × mensual); con monedas distintas la dimensión se **excluye** y el desglose lo dice (BR-MATCH-10, BR-CAND-07, BR-JOB-21).
- **Habilidades:** coincidencia por `skill_id` normalizado (ADR-0019) y, si falta, por similitud de embeddings con `τ_skill`; el requisito sin enlace sigue puntuando.
- **Educación:** `educationLevel` es el catálogo cerrado de `data-model.md` §4.3; el nombre de la institución **no** entra.
- **Ubicación y modalidad:** los valores son los catálogos cerrados `REMOTE`, `HYBRID`, `ONSITE` (`tdd/cv-module.md` §3.1).

Propiedades que se prueban sobre el agregador (§9): `0 ≤ overall ≤ 100`; monotonía (añadir una habilidad que cubre un requisito nunca baja el score); idempotencia; los pesos suman 1 tras renormalizar.

## 4. Casos de uso

Carpetas de `matching-module/src/main/java/com/usac/jobseeker/matching/application/usecase/`.

| Carpeta | Disparo | Qué hace | Puertos de salida principales |
|---|---|---|---|
| `calculatecompatibilityscore` (existe) | Lectura bajo demanda o evento | Lee señales y vectores ya calculados, aplica dimensiones, agrega, escribe el vigente, escribe foto si corresponde y cachea. **No llama a Ollama.** Si falta un vector, devuelve `PENDING_EMBEDDING` | `CandidateSignalsReader`, `JobSignalsReader`, `EmbeddingVectorPort`, `ScoreStorePort`, `ScoreCachePort`, `WeightsProvider`, `CalibrationProvider` |
| `embedentity` | Bandeja: cambio de contenido de perfil o vacante | Construye la representación canónica, compara su *hash* con el guardado y llama a Ollama **solo** si cambió; guarda vector, modelo, `embedding_truncated` y fecha | `CanonicalProfileReader`, `EmbeddingClientPort`, `EmbeddingStorePort` |
| `recomputescoresforentity` | Tras `embedentity` | Elige los pares con motivo (postulados activos, vistos recientes) y encola su cálculo | `ReasonedPairsPort`, `WorkQueuePort` |
| `explainscore` | El usuario abre el desglose | Plantillas deterministas sobre el desglose y «qué pasaría si» (REQ-C-01) sobre el mismo agregador | los de `calculatecompatibilityscore` |
| `suggestcandidatesforjob` | El reclutador abre los sugeridos | Pipeline de tres etapas del ADR-0017 con filtros de descubribilidad, cuenta activa y exclusión, y desempate `OPEN_TO_WORK` | `CandidateRetrievalPort`, los de `calculatecompatibilityscore` |
| `migratealgorithmversion` | Operación | Reprocesa por lotes, empezando por los postulados activos, mostrando la versión por fila | `ScoreStorePort`, `WorkQueuePort` |
| `snapshotapplicationprofile` | Al postular (evento de `ats`) | Guarda la copia inmutable del perfil canónico y la foto del score de ese momento (ADR-0017, decisión 9) | `ApplicationSnapshotPort` |

Los demás casos de uso (consulta del desglose para el reclutador, filtros de exclusión) se nombran con el TDD de `ats`, que es quien los consume. **No se crean carpetas antes de que su sub-paso las implemente.**

## 5. Datos

Las tablas son de `matching` salvo lo indicado (ADR-0027: cada contexto es dueño de las suyas).

| Tabla | Para qué | Notas |
|---|---|---|
| `compatibility_scores_current` | Score vigente por par `(candidato, vacante)`, con *upsert* | Reemplaza el histórico por recálculo de `data-model.md` §4.7 |
| `compatibility_score_snapshots` | Fotos: al postular, al cambiar de etapa, al cambiar `algorithm_version` y muestras | Retención 24 meses; se anonimiza con la cuenta (BR-DATA-05) |
| `application_profile_snapshots` | Perfil canónico inmutable al postular | Su texto libre es un dato derivado de la persona aunque se hayan excluido nombre y contacto. Al anonimizar la cuenta, `canonical_text` y `canonical_hash` pasan a `NULL` y la fila queda como metadato (postulación, `algorithm_version`, fecha); ver D5 |
| `matching_work_queue` | Cola de trabajos (embeber, recalcular) con `FOR UPDATE SKIP LOCKED`, reintentos con espera creciente y variación aleatoria | Misma técnica que la bandeja del ADR-0016 |
| `profile_embeddings` y `job_embeddings` | Vectores con su modelo, *hash* y fecha, y la copia de `discoverable` / `published` para el índice HNSW parcial | Propias de `matching` (decisión D1, resuelta); sin clave foránea a otro contexto; `data-model.md` §4.7.1 |

**Frescura.** Los objetivos del ADR-0017 (decisión 10) se miden con tres métricas: edad del trabajo más viejo de la cola, tiempo hasta score actualizado tras la edición (histograma) y pares en `PENDING_EMBEDDING`. La regla de alerta de NFR-OBS-04 se escribe con ellas.

## 6. Configuración

Nada se codifica en una constante (BR-MATCH-05): pesos por dimensión, `α`, umbrales `τ`, el tope de recuperación (200), el *top-N* máximo (50), la ventana de agrupación de ediciones y la retención de fotos van en `jobseeker.matching.*`, con valores por defecto del ADR-0017. Al arrancar se calculan el *hash* de pesos y el de calibración y se registran; un cambio de configuración **produce otra `algorithm_version`**, no una mutación silenciosa. Los parámetros de calibración viven por modelo de embeddings.

## 7. API

Las rutas heredan las convenciones de `api-contract-overview.md`; el contrato lo genera springdoc (ADR-0010).

| Método y ruta | Quién | Política de límite | Errores clave |
|---|---|---|---|
| `GET /api/v1/matching/jobs/{id}/score` | candidato, sobre vacantes visibles para él (BR-VIS-01) | lectura | `404` si no es visible, `202 SCORE_PENDING` si el vector aún no existe |
| `GET /api/v1/matching/jobs/{id}/suggestions?page&size≤50` | reclutador **propietario** (BR-VIS-03) | estricta, más la cuota diaria de perfiles | `403` si no es el dueño, `429 QUOTA_EXCEEDED` |

El desglose lleva `completeness`, `α` y la marca de **no calibrado** mientras no exista la calibración (ADR-0017, decisión 5). El coseno bruto no aparece en ninguna respuesta.

## 8. Rendimiento y degradación

- **NFR-PERF-01:** score visible en ≤ 2 s con caché y ≤ 5 s sin ella; como el caso de uso no llama a Ollama, el camino sin caché es lectura más aritmética.
- **NFR-PERF-04:** sugeridos en ≤ 3 s sobre 10⁴ perfiles descubribles; el índice HNSW parcial y los 200 recuperados lo permiten. Con `hnsw.iterative_scan = relaxed_order` para los filtros residuales (`02-hld-system-design.md` §5.2). Se **mide** con vectores reales: la medición de pgvector del ADR-0018 fue sobre un corpus semisintético y es una cota optimista (`data-model.md` §6).
- **NFR-AVAIL-02:** con Ollama caído se sirve el último score con aviso; la cola acumula y se vacía al volver. No se calcula con datos a medias.

## 9. Pruebas

- **Dominio:** pruebas unitarias sin Spring de cada dimensión y del agregador, con los casos de borde de `03-lld.md` §2.3 (sin requisitos, rango de un solo punto, monedas distintas).
- **Propiedades** (monotonía, idempotencia, límites, pesos que suman 1): con un generador de datos aleatorios. `03-lld.md` propone **jqwik**; es una dependencia nueva y por la regla del proyecto necesita decisión registrada (D2).
- **Adaptadores:** Postgres 17 con pgvector real (Testcontainers) para vectores, *upsert* del vigente y la cola, incluida la concurrencia de varios trabajadores; Ollama sustituido por un doble que verifica `truncate=false` y el `num_ctx` enviados.
- **Contrato con `cv` y `job`:** que `CandidateSignals` y `JobSignals` se construyen solo con lo que sus lecturas devuelven y que ni nombre, foto, contacto ni institución llegan al dominio.
- **Equidad:** la prueba contrafactual (cambiar nombre, género o institución no mueve el puntaje más del umbral) corre en el arnés; aquí se prueba que la representación canónica **no contiene** esos campos.
- **Privacidad:** ni el log, ni las etiquetas de métricas, ni la carga de los eventos contienen datos del perfil (BR-DATA-01).
- **Frescura:** una prueba de integración que edita un perfil y comprueba que el score se actualiza dentro del objetivo con un Ollama de prueba.
- **Reglas de arquitectura:** ARCH-01 a 14; `matching` no es dependencia de `cv` ni de `job` (ARCH-06).

## 10. Orden de entrega

| Sub-paso | Entrega | Depende de |
|---|---|---|
| 1 | Dominio puro: dimensiones, agregador, calibrador y explicación, con las pruebas de propiedades | ADR-0017 aprobado, D2 |
| 2 | `calculatecompatibilityscore` con puertos y dobles en memoria; `compatibility_scores_current` y caché | 1 |
| 3 | `embedentity`, la cola y el adaptador de Ollama; los dos adaptadores de lectura | `cv` sub-paso 5 (`getprofilesnapshot`), TDD de `job`, ADR-0018 con modelo definitivo |
| 4 | `recomputescoresforentity` y los consumidores de la bandeja | 3 |
| 5 | `explainscore` y el «qué pasaría si»; el endpoint del score | 2 |
| 6 | `suggestcandidatesforjob` y el índice HNSW parcial | 3, D1 |
| 7 | `snapshotapplicationprofile` y `migratealgorithmversion` | postulación mínima de `ats` |
| — | Normalización de habilidades en el cálculo | ADR-0019 aprobado y el caso de uso de `cv` |

**Controles de aceptación de los ADR aprobados con condiciones**, que este orden debe cumplir: C1 (*recall* de la recuperación) en el sub-paso 6; C2 (calibración y evaluación separadas por vacante) y C5 (umbral contrafactual) en el arnés; C3 (declarar un dato que coincide nunca baja el puntaje) en el sub-paso 1; C4 (frescura medida con Ollama real) en el sub-paso 4; D1 a D4 de la taxonomía, con su sub-paso en `cv`.

El **arnés de evaluación** corre en paralelo desde el sub-paso 2 y debe estar antes de calibrar `α` y los parámetros del sub-paso 5.

## 11. Decisiones abiertas

| ID | Pregunta | Recomendación |
|---|---|---|
| **D1** | ✅ **Resuelta (23-nov-2026): los vectores viven en tablas propias de `matching`.** Antes estaban en `candidate_profiles` y `job_postings`, pero quien los escribe es `matching`, lo que rompía que cada contexto sea dueño de sus tablas (ADR-0027); y el índice HNSW parcial exige que `discoverable` esté en la misma tabla | `profile_embeddings` y `job_embeddings` con una **copia** de `discoverable` / `published`, que mantienen `CandidateDiscoverabilityChanged` y los eventos de `job`; por defecto `false` (lado seguro). Implementado en el sub-paso 3 (migración y adaptador); `data-model.md` §4.7.1 |
| **D2** | ¿Se acepta jqwik como dependencia de pruebas? | Sí, solo en `test`, con su decisión registrada |
| **D3** | La fuente de verdad del contenido de la vacante para la representación canónica: ¿el texto de la descripción o los requisitos estructurados? | Ambos: la descripción para la afinidad semántica y los requisitos para las dimensiones, con una sola huella sobre su combinación canónica; se define con el TDD de `job` |
| **D4** | Qué hacer con un score ya publicado cuando cambia la moneda del candidato | Recalcular por el flujo normal: es un cambio de perfil como cualquier otro |
| **D5** | ✅ **Resuelta (24-nov-2026): la copia pierde su texto al anonimizar y las fotos nunca guardan texto del candidato.** `cv` borra habilidades, idiomas, autorizaciones, salario y disponibilidad (BR-DATA-05), y esta copia es donde vive el historial del reclutador: un conjunto de habilidades específicas es casi único y reidentifica junto al `account_id`, así que conservarlo la dejaría como una huella | (1) **Copia del perfil:** al recibir `profile.anonymized`, `canonical_text` y `canonical_hash` se ponen en `NULL` (restricción `CHECK`: con `anonymized_at` la fila no tiene texto); la fila se conserva con la postulación, `algorithm_version` y la fecha. (2) **Fotos del score:** guardan los puntajes, la disponibilidad de cada dimensión y la versión, y su evidencia se expresa **en términos de los requisitos de la vacante** (cuáles se cubren y en qué nivel), nunca con texto libre del candidato: el historial que el reclutador necesita es la decisión y su porqué, y eso no depende de lo que escribió la persona. Por eso las fotos no hay que anonimizarlas. (3) Lo hace un caso de uso `anonymizematchingdata` que también anula el bloque de embedding; idempotente, y correcto aunque llegue un `profile.updated` tardío porque el orden por agregado y el nivel disparado lo cubren (ADR-0016, enmienda). Criterio de aceptación de ese sub-paso: prueba de que, tras el evento, ninguna tabla de `matching` conserva texto del candidato |

## 12. No cubierto todavía

- La interfaz del desglose, de los sugeridos y de los estados pendientes.
- Cuota diaria, verificación del reclutador y datos de contacto enmascarados (ADR-0025).
- Búsqueda de vacantes (ADR-0023): comparte el modelo de embeddings pero es otro caso de uso, de `job`.
- La notificación al candidato cuando un reclutador lo ve en sugeridos: ninguna regla la pide.
