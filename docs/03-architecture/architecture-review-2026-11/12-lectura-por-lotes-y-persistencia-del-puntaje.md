# 12 — Lectura por lotes (TD-039), puntaje guardado (TD-036), aliases (TD-038) y herramientas de calibración (TD-037)
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | 🟡 En revisión |
| **Fecha** | 25 de noviembre de 2026 |
| **Documentos relacionados** | ADR-0017, ADR-0019, ADR-0030, ADR-0031, `tdd/matching-module.md` §10.3 a §10.5, `11-medicion-recuperacion-vectorial-y-latencia-de-embeddings.md` |

---

## 1. Resumen

| Punto | Estado |
|---|---|
| **TD-039** (leer 200 perfiles uno a uno) | ✅ **Cerrada y medida.** Con 200 candidatos, los sugeridos bajan de **p50 1.246 ms a 118 ms** (10,6 veces) |
| **TD-036** (puntaje sin persistir) | ✅ **Cerrada**, salvo conectar `ats` (TD-040, bloqueada por un módulo que no existe) |
| **TD-038** (habilidades por texto) | 🟡 **Parcial**: aliases de habilidades hechos; la taxonomía ESCO y la experiencia *relevante* siguen abiertas |
| **TD-037** (puntaje sin calibrar) | ⏳ **Sigue abierta, con las herramientas listas.** Cerrarla exige pares etiquetados reales, que no existen |

## 2. TD-039: lectura por lotes

### 2.1 Medición (esta VM, PostgreSQL 17 local, 200 candidatos completos, `size=50`)

| | Consultas por petición | p50 | Máximo |
|---|---|---|---|
| Antes (una cuenta a la vez) | ≈ 2.400 (≈ 12 por candidato) | **1.246 ms** | 1.850 ms |
| Después (por lotes) | ≈ 20 fijas | **118 ms** | 141 ms |

La latencia de arriba **no incluye la ida y vuelta de red**: con un PostgreSQL gestionado (p. ej. 2 ms por consulta) el «antes» habría sumado unos 4,8 s más y no habría cabido en los 3 s de NFR-PERF-04; el «después» suma unos 40 ms. Lo importante es que el costo **ya no crece con el número de candidatos**: es lo que dice `JdbcBatchReadsTest` (9 sentencias para 40 perfiles, 1 para 1).

### 2.2 Qué se hizo

- **`auth`:** `ResolveActorsUseCase` resuelve varias cuentas con una consulta y aplica la misma regla que la individual.
- **`cv`:** `GetProfileSnapshotsUseCase` y `GetVisibleAvailabilitiesUseCase` (mismas reglas de capacidad, exclusión y anonimización). **La lectura individual es el caso de una cuenta**: una sola implementación de las reglas, para que lote e individual no se desvíen. Los repositorios leen con `= any(:ids)` y agrupan por dueño.
- **`matching`:** la etapa 2 pide **una vez** las señales y **una vez** la disponibilidad de todo el conjunto, y solo la de quienes pasaron el filtro de completitud.
- **Compatibilidad:** las lecturas por lotes son **interfaces nuevas** y los puertos ganan métodos `default` (que preguntan de a uno), para no romper a quien simula la lectura individual. Un servicio con dos interfaces debe ser **un solo bean** (dos instancias darían dos candidatos para cada tipo: se encontró al arrancar y se corrigió).

### 2.3 Cómo repetirla

`mvn -pl app -am verify -Dtest='MatchingEmbeddingEndToEndIntegrationTest#measureTheCostOfScoringTheRecoveredCandidates' -Dsuggestions.measure=true` (crea 200 candidatos y mide; solo corre con esa propiedad).

## 3. TD-036: puntaje guardado

### 3.1 Qué se construyó

| Pieza | Qué hace |
|---|---|
| Tablas `compatibility_scores_current`, `compatibility_score_snapshots`, `application_profile_snapshots` | Puntaje vigente por par con motivo, fotos y copia del perfil canónico al postular |
| `calculatecompatibilityscore` | Puntúa y guarda un par; sin vector → `PENDING_EMBEDDING`, nunca a medias |
| `recomputescoresforentity` | Tras procesar un perfil o una vacante, recalcula los pares **ya guardados**; corre aunque el vector no cambie |
| `explainscore` y «qué pasaría si» | `GET /api/v1/matching/jobs/{id}/score` y `POST …/score/what-if`, con la visibilidad del candidato |
| `migratealgorithmversion` | Al cambiar la versión, guarda una foto del puntaje **anterior** y deja el vigente al día; también purga las fotos de más de 24 meses |
| `snapshotapplicationprofile` | Para `ats`: la foto del puntaje y la copia inmutable del perfil canónico al postular |
| Sugeridos | Lo que un reclutador vio queda guardado, en una sola sentencia |

### 3.2 Reglas que se verificaron

- **Solo pares con motivo** (`SUGGESTED`, `CANDIDATE_VIEW`, `APPLIED`): nunca se materializan todos los pares. El motivo más fuerte se conserva: un postulado nunca baja.
- **Visibilidad del candidato (BR-VIS-01, BR-JOB-11, BR-ACCT-05):** vacantes publicadas o cerradas, que no son suyas; para quien no puede, la vacante no existe (`404`).
- **«Actualizando…» (BR-MATCH-03):** si hay un cambio en cola, la respuesta lo dice en vez de mostrar un valor viejo como vigente.
- **Anonimización (BR-DATA-05):** el desglose pasa a `{}` y el texto canónico a `NULL`; **se conservan** el par, el puntaje y la versión, porque el reclutador que ya evaluó a esa persona conserva su registro.
- **Qué pasaría si (REQ-C-01):** aritmética sobre una copia, sin guardar nada; solo calcula lo que el candidato pide.

### 3.3 Desvíos del ADR-0017 (ADR-0031, aprobado)

1. **Sin caché en Redis por huella.** La huella del ADR combinaba los *hashes* del perfil y la vacante canónicos, pero esos textos **excluyen** los datos estructurados (salario, país, nivel de las habilidades): cambiarlos no cambiaría la huella y se serviría un puntaje viejo. Incluirlos exige leer las señales, que es justo el costo que la caché evitaría; el resto es aritmética de milisegundos.
2. **El recálculo corre dentro del trabajador de la cola**, no como un trabajo aparte: hereda reintentos y retroceso, y la métrica de frescura pasa a medir «edición → puntaje actualizado».
3. **El puntaje del candidato se calcula al pedirlo**, así que nunca es viejo; la copia guardada sirve a `ats` y a la auditoría. Medido: **p50 42 ms y p95 75 ms** (petición completa), frente a ≤ 2 s (NFR-PERF-01).

## 4. TD-038: aliases de habilidades

Una habilidad cubre un requisito si su nombre normalizado coincide **o** pertenece al mismo grupo de alias («JS» y «JavaScript», «k8s» y «Kubernetes», «ML» y «aprendizaje automático»): 36 grupos, solo equivalencias **inequívocas** («Spring» y «Spring Boot», «Angular» y «AngularJS», «Java» y «JavaScript» **no** se unen: un falso positivo infla un puntaje). Una prueba verifica que ningún nombre esté en dos grupos. Es una medida provisional: con la taxonomía del ADR-0019 estos grupos pasan a ser las habilidades locales sembradas (decisión 2 de ese ADR).

**Lo que no se hizo, y por qué.** La experiencia *relevante* se descartó: sin taxonomía ni embeddings por puesto habría que comparar textos de títulos en español e inglés, y una heurística léxica penalizaría a quien escribe «Desarrollador» frente a «Developer». Sin un conjunto etiquetado no hay forma de saber si mejora o empeora el puntaje. La taxonomía ESCO exige descargar el *dataset* con su licencia (atribución obligatoria) y un módulo de importación en `cv`.

**Versión del algoritmo.** Comparar por alias cambia el puntaje de habilidades, así que el código del algoritmo pasa de 1 a 2: un puntaje guardado con la 1 deja de ser comparable y `migratealgorithmversion` lo recalcula por lotes con una foto del anterior (BR-MATCH-04).

## 5. TD-037: herramientas de calibración

`scripts/eval/matching_eval.py` ya calculaba Platt, isotónica, validación agrupada por vacante, ECE, Brier y P@K/NDCG. Se añadieron los dos comandos que faltaban para **cerrar** el punto cuando haya datos:

- **`emit-config`**: ajusta Platt y emite `jobseeker.matching.scoring.calibration.a` y `.b`, **o se niega** si no se cumple el protocolo (mínimo 300 pares y 30 vacantes, ambas clases, pendiente positiva, ECE fuera de pliegue acotado y Brier mejor que predecir la tasa base). Publicar parámetros que no pasan esas pruebas haría `calibrated: true` sin serlo.
- **`retrieval-recall`** (control C1): qué fracción de los pares relevantes queda entre los 200 de mayor coseno, con intervalo por vacante y veredicto frente a 0,95; avisa si los conjuntos son tan pequeños que no informa.

**Qué falta para cerrar TD-037:** pares etiquetados reales (protocolo de 300 pares, ADR-0018) y el umbral contrafactual (C5). Con ellos: `emit-config` → pegar las dos propiedades → `calibrated: true` y el indicador `jobseeker_matching_scoring_calibrated` pasa a 1.
