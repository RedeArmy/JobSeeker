# ADR-0019: Taxonomía de habilidades (ESCO) y su normalización
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | 🟡 Propuesto — **no está bloqueado hasta que el responsable del proyecto lo apruebe** |
| **Versión** | 1.0 |
| **Fecha** | 23-nov-2026 |
| **Documento relacionado** | Hallazgo F4 de `architecture-review-2026-11/00-resumen-ejecutivo.md`, `03-lld.md` §2.3 y §4, `data-model.md` §4.12, `tdd/cv-module.md` §12 (D7), ADR-0018, BR-MATCH-01, BR-MATCH-02, BR-ELIG-06 |

---

## Contexto

El 35 % del score estructurado depende de las habilidades (BR-MATCH-01). Hoy la habilidad que declara un candidato (`candidate_skills.raw_name`) y la que pide una vacante son **texto libre**, así que «Java», «java 17», «Programación en Java» y «JDK» son cuatro cosas distintas para el motor. Sin normalizarlas, el 35 % más importante del score compara cadenas, no capacidades: el candidato que escribe distinto al reclutador pierde puntos que merece.

La revisión de noviembre ya diseñó las tablas (`skills`, `skill_aliases`, `job_requirements`; `03-lld.md` §4) y la fórmula que las usa (`03-lld.md` §2.3: coincidencia exacta de `skill_id`, o `0,8 · sim` si la similitud supera un umbral). Lo que faltaba era decidir **de dónde sale la taxonomía, quién la mantiene, cómo se enlaza un texto libre y cuándo se confía en ese enlace**. El sub-paso 3 del TDD de `cv` ya dejó preparado `candidate_skills.skill_id` (nulo y sin clave foránea) a la espera de esta decisión.

Restricciones: el proyecto es de un solo desarrollador, el contenido es mayormente en español con mezcla de inglés técnico (hallazgo F5), y toda explicación del score debe poder citar qué habilidad cubrió qué requisito (BR-MATCH-02).

## Decisión

1. **Fuente: ESCO.** La taxonomía europea de habilidades y competencias (versión 1.2, 13 939 habilidades y conocimientos; existe la 1.2.1, publicada en diciembre de 2025) se importa **una vez** como dato de referencia. Cubre los 28 idiomas de ESCO, entre ellos el español, con etiquetas principales y alternativas. Se fija la versión en la importación y se registra (`taxonomy_version`).
2. **Se completa con habilidades locales.** ESCO queda corta en tecnología reciente (marcos, herramientas). Las que falten se añaden con `source = 'LOCAL'` y sin `esco_uri`, mantenidas como datos semilla versionados en el repositorio y aplicadas por migración. Nunca se editan a mano en producción.
3. **Modelo.** `skills(id, canonical_name, language, esco_uri nullable único, source, embedding…)` y `skill_aliases(alias_norm, skill_id)` como en `03-lld.md` §4, con tres ajustes: la dimensión del vector y sus columnas de modelo siguen el mecanismo de `candidate_profiles` del ADR-0018 (el `vector(768)` del LLD era de `nomic` y ya no aplica), `alias_norm` es la forma normalizada (minúsculas, sin acentos, sin signos) y una habilidad obsoleta de ESCO se conserva con `replaced_by` en vez de borrarse, para no romper enlaces ya hechos.
4. **El texto libre se conserva siempre.** Lo que escribió el candidato (`raw_name`) o el reclutador no se pierde ni se sustituye: el enlace es un dato aparte (`skill_id`), de modo que desenlazar o corregir no destruye nada.
5. **Enlace en tres escalones**, en este orden y deteniéndose en el primero que resuelve:
   1. **Alias exacto**: el texto normalizado coincide con un alias o nombre canónico. Se enlaza solo, sin intervención.
   2. **Similitud semántica alta** (`≥ τ_alta`): el embedding del texto está muy cerca del de una habilidad. Se enlaza solo y queda marcado `LINKED_BY_EMBEDDING`, auditable y reversible.
   3. **Similitud intermedia o ninguna**: **no se enlaza**. Entre `τ_baja` y `τ_alta` se ofrece como sugerencia al usuario («¿te refieres a…?»), que la confirma o no. Por debajo queda como texto libre.
6. **Los umbrales son provisionales.** `τ_alta` y `τ_baja` salen de calibrarse con pares etiquetados, con el mismo protocolo y el mismo conjunto que el ADR-0018 para el modelo de embeddings, y se versionan. Hasta entonces solo operan los escalones 1 y 3: el escalón 2 está apagado.
7. **Una habilidad sin enlace sigue puntuando.** `03-lld.md` §2.3 ya cubre el caso: el requisito se compara por similitud contra las habilidades del candidato, no por identificador. Normalizar mejora la precisión y la explicación; no es un requisito para participar en el matching.
8. **Dueño: el módulo `cv`.** La taxonomía es parte del dominio del perfil (qué sabe una persona) y `cv` ya es de quien dependen `job` y `matching` en el mapa de contextos. `cv` expone un caso de uso de normalización que `job` y `matching` consumen desde sus adaptadores de salida. No se crea un módulo nuevo para una tabla de referencia.
9. **Equidad.** La normalización no usa nombre, institución ni ningún dato personal: solo el texto de la habilidad. Esto es compatible con BR-ELIG-06 (la elegibilidad no altera el score) y con la minimización del ADR-0018.
10. **Niveles.** ESCO no define niveles de dominio. El nivel `1 a 4` de `candidate_skills.proficiency` y el mínimo exigido en `job_requirements` son propios de JobSeeker y se interpretan en la aplicación.

## Alternativas consideradas

| Alternativa | Por qué se rechazó |
|---|---|
| Seguir con texto libre y confiar solo en embeddings | Funciona como respaldo (decisión 7), pero no permite explicar «cubrió el requisito X con la habilidad Y» de forma verificable, y el 35 % del score queda a merced de la calibración de un modelo aún provisional |
| Taxonomía propia desde cero | Es trabajo de curación enorme para una sola persona y se reinventa algo que ya existe, multilingüe y mantenido |
| Taxonomías comerciales (Lightcast y similares) | Licencia y costo incompatibles con un proyecto de tesis; no resuelve el español mejor |
| O*NET | Orientada a Estados Unidos y en inglés; el contenido de este proyecto es en español |
| Que un modelo generativo extraiga y normalice habilidades | Contradice la decisión de dejar el LLM fuera de la ruta crítica (`02-hld-system-design.md` §4.5): costo, falta de reproducibilidad y riesgo de inventar habilidades que la persona no declaró |
| Enlazar siempre por embedding, sin confirmación | Un enlace erróneo entre dos habilidades parecidas pero distintas («Java» y «JavaScript») cambiaría el score sin que nadie lo vea; por eso solo se enlaza sin intervención lo que es casi seguro |

## Consecuencias

- **Positivas:** el 35 % del score pasa a comparar capacidades; la explicación puede citar evidencia concreta; las habilidades de un candidato y de una vacante quedan alineadas sin que ninguno cambie su forma de escribir.
- **Costos:** hay que importar y mantener un dato de referencia (13 939 filas más alias y vectores), calcular un embedding por habilidad cuando cambie el modelo (mismo procedimiento de cambio de modelo del ADR-0018) y curar las habilidades locales.
- **Riesgo:** un alias ambiguo («C», «Go», «R») puede enlazarse mal. Se mitiga con una lista de alias ambiguos que **nunca** se enlazan por el escalón 1 sin contexto, y con que el escalón 2 está apagado hasta calibrarse.
- **Migración:** `candidate_skills.skill_id` y `job_requirements.skill_id` se rellenan después, con una migración de expansión y un trabajo por lotes; nada de lo ya guardado se rompe.

## Acciones derivadas

1. **Verificar la licencia y el texto de atribución de ESCO antes de importar.** La búsqueda de esta sesión confirmó que la clasificación es de libre reutilización y está en 28 idiomas, pero **no confirmó** el texto exacto de la licencia ni la atribución obligatoria. Es una condición previa a la importación, no una formalidad.
2. TDD de `cv`: el caso de uso de normalización y la tabla `skills` entran en un sub-paso propio (§10), después del sub-paso 3.
3. Añadir `taxonomy_version` a los metadatos del enlace y, si cambia el contenido de la representación canónica, a la huella de entrada del score (ADR-0017).
4. Armar el conjunto de pares etiquetados de habilidades junto al protocolo de 300 pares del ADR-0018.

## Preguntas abiertas

- ¿Quién cura las habilidades locales y con qué criterio de entrada (frecuencia mínima, revisión)? Propuesta: el responsable del proyecto, por lotes, desde las habilidades más escritas que quedaron sin enlace.
- ¿Se expone al candidato la lista de sugerencias de enlace en la Ola 1, o solo se acumulan para curación? Propuesta: solo acumular en la Ola 1; mostrarlas exige diseño de interfaz.
- ¿Los alias de ESCO en español de España cubren el uso de Guatemala? Se mide con el mismo conjunto etiquetado; si no, se añaden alias locales.
