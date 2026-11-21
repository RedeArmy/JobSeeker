# ADR-0007: Modelo genérico/extensible de reglas de elegibilidad vs. columnas fijas por tipo de excepción
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | ✅ Aprobado — 20-nov-2026, por el responsable del proyecto, tras la validación descrita al final de este documento |
| **Versión** | 1.1 — aprobado el 20-nov-2026 tras la validación completa |
| **Fecha** | Agosto 2026 · aprobado el 20-nov-2026 |
| **Documento relacionado** | Business Rules (BR-ELIG-01 a 08), Domain Model, Data Model |

---

## Contexto

Se requiere modelar "reglas de elegibilidad" (excepciones) configurables por el reclutador en cada vacante — inspiradas en el modelo de GetOnBoard — que determinan si un candidato puede postular y, opcionalmente, si la vacante le es visible (BR-ELIG-01 a 04). El catálogo inicial cubre nueve dimensiones (país de residencia, autorización legal de trabajo, nivel académico, años de experiencia, modalidad, disponibilidad/jornada, idioma, disposición a reubicarse, expectativa salarial), con la expectativa explícita del negocio de seguir agregando tipos de excepción a futuro.

Existen dos formas razonables de modelar esto en `job-module`:

1. Una columna (o conjunto de columnas) dedicada por tipo de regla, directamente en `job_postings` o en una tabla por tipo de excepción.
2. Una entidad genérica `EligibilityRule` con un campo `ruleType` (enum cerrado) y un campo `criteria` de forma flexible (jsonb), interpretado por tipo en la capa de aplicación.

## Decisión

Se adopta el **modelo genérico/extensible**: una única entidad `EligibilityRule`, hija del agregado `JobPosting`, con:

- `ruleType`: enum cerrado y curado (BR-ELIG-01) — nunca un campo de texto libre arbitrario.
- `criteria`: `jsonb` con la forma específica de cada `ruleType` (p. ej. `{"allowedCountries": ["CO","AR"]}` para `COUNTRY_OF_RESIDENCE`, `{"minYears": 3}` para `YEARS_OF_EXPERIENCE`).
- `hideIfNotEligible` y `active`: flags independientes por regla (BR-ELIG-04/05).

La interpretación de `criteria` (qué operador aplica — pertenencia a lista, umbral mínimo, rango, igualdad booleana) vive en la capa de aplicación de `job-module`, no en el esquema de base de datos.

## Alternativas consideradas

| Alternativa | Por qué se rechazó |
|---|---|
| Columnas fijas por tipo de regla en `job_postings` (p. ej. `allowed_countries`, `min_academic_level`, `required_modality`, ...) | Cada tipo de excepción nuevo requiere una migración Flyway y un cambio de esquema en producción (Supabase); el negocio ya anticipó que el catálogo de excepciones crecerá. También ensancha `job_postings` con columnas mayormente `NULL` para vacantes sin esa excepción activa |
| Tabla dedicada por tipo de regla (`country_eligibility_rules`, `academic_level_eligibility_rules`, ...) | Evita el ensanchamiento de `job_postings`, pero multiplica el número de tablas y de casos de uso de evaluación (uno por tipo), sin resolver el problema de fondo: cada tipo nuevo sigue requiriendo migración y nuevo código de persistencia |
| Motor de reglas completamente libre (`criteria` sin `ruleType` cerrado, o expresión arbitraria tipo DSL/script) | Sobre-ingeniería para el catálogo actual (9 tipos conocidos); además, un campo de regla verdaderamente libre facilitaría configurar criterios discriminatorios (edad, género) que el negocio decidió excluir deliberadamente (ver nota en `business-rules.md` §3.1) |

## Consecuencias

**Positivas**

- Agregar un nuevo tipo de excepción (p. ej. "certificación requerida") es un cambio de código en la capa de aplicación (nuevo caso en el evaluador) más un valor nuevo en el `CHECK` de `rule_type`, sin ensanchar el esquema relacional de `job_postings` ni crear tablas nuevas.
- Una sola tabla (`eligibility_rules`) y un solo caso de uso de evaluación (`ValidateJobEligibilityUseCase`) cubren todos los tipos de regla, en vez de N casos de uso paralelos.
- El desglose por regla incumplida (BR-ELIG-03) es directo: se itera `eligibility_rules` activas y se reporta cuáles fallaron, sin lógica condicional por columna.

**Negativas / trade-offs aceptados**

- `criteria` (jsonb) no tiene validación de forma a nivel de base de datos — una fila con estructura inconsistente para su `ruleType` solo se detecta en tiempo de evaluación, no por una constraint SQL (ver pregunta abierta en `data-model.md` §6 sobre validar con JSON Schema en la capa de aplicación).
- El evaluador de reglas (`job-module`) requiere un `switch`/mapa de estrategias por `ruleType`, siempre en Java (nunca lógica de reglas basada en texto/eval), para no reintroducir el riesgo que llevó a descartar un motor de reglas libre.
- Consultar "candidatos elegibles para esta vacante" con filtros SQL directos sobre `criteria` es más costoso que sobre columnas tipadas (requiere operadores jsonb); aceptable porque el volumen esperado durante la validación (cohorte USAC) es bajo, y el filtrado real ocurre en la capa de aplicación, no en SQL ad-hoc.

## Preguntas abiertas

- ¿Se valida la forma de `criteria` con JSON Schema por `ruleType` en la capa de aplicación antes de persistir, o solo en el formulario del reclutador (capa de presentación)?
- Si el catálogo de `ruleType` crece significativamente (más de ~15-20 tipos), ¿sigue siendo preferible este modelo genérico, o conviene reevaluar hacia un catálogo de tipos de regla en tabla (`eligibility_rule_types`) en vez de un `CHECK` de enum fijo?

## Validación previa a la aprobación (20-nov-2026)

- El modelo genérico ya está reflejado en `data-model.md` §4.11 y `business-rules.md` §3, y se reutiliza para las preguntas de postulación (BR-APPQ), como preveía el ADR.
- Las dos preguntas abiertas (validación de `criteria` con JSON Schema; qué hacer si el catálogo crece) son de implementación y se resuelven en el TDD de `job-module`; no cambian la decisión.
