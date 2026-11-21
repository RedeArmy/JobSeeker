# ADR-0003: Scoring híbrido (embeddings + reglas ponderadas) vs. puramente semántico
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | 🟡 En revisión |
| **Versión** | 1.0 |
| **Fecha** | Agosto 2026 |
| **Documento relacionado** | System Design Document, Business Rules, Domain Model |

---

## Contexto

Todo score de compatibilidad debe ir acompañado de un desglose explicable por dimensión — nunca una caja negra (BR-MATCH-02). Un modelo puramente semántico (solo similitud de embeddings entre CV y vacante) produce un único número de similitud sin una forma natural de descomponerlo en dimensiones interpretables por el usuario (habilidades, experiencia, formación, ubicación, salario).

## Decisión

Se adopta un **modelo híbrido de dos capas**:

1. **Capa semántica**: similitud coseno entre embeddings de CV y vacante, generados con `nomic-embed-text` vía Ollama.
2. **Capa estructurada ponderada**: reglas explícitas sobre dimensiones estructuradas (habilidades, experiencia, formación, ubicación/modalidad, salario, y otras que se calibren), con pesos configurables — no hardcodeados (BR-MATCH-05).

El score final combina ambas capas; el desglose expuesto al usuario se construye a partir de la capa estructurada, complementado por la señal semántica.

## Alternativas consideradas

| Alternativa | Por qué se rechazó |
|---|---|
| Scoring puramente semántico (solo similitud de embeddings) | Imposible de descomponer de forma confiable en un desglose por dimensión sin heurísticas adicionales; incumple BR-MATCH-02 directamente |
| Scoring puramente basado en reglas (sin capa semántica) | Pierde la capacidad de capturar afinidad de contenido no capturada por reglas estructuradas explícitas (p. ej. redacción similar de responsabilidades, sinónimos de habilidades no catalogados) |

## Consecuencias

**Positivas**

- Cada score es explicable por diseño: el desglose por dimensión es un requisito de primera clase del modelo, no un añadido posterior.
- Los pesos configurables permiten calibración empírica continua, empezando con usuarios reales desde el cierre de la Ola 1 (`roadmap-and-release-plan.md` §10) sin reescribir el modelo ni redeployar código.
- El versionado del algoritmo (`algorithm_version`, BR-MATCH-04) permite comparar precisión entre configuraciones de pesos distintas.

**Negativas / trade-offs aceptados**

- Mayor complejidad de implementación que un modelo de una sola capa: requiere mantener y calibrar dos mecanismos de cálculo en paralelo.
- La calidad del resultado depende de una calibración de pesos razonable desde el inicio; pesos mal calibrados pueden producir un score técnicamente explicable pero poco preciso (riesgo documentado en `system-design-document.md` §8).

## Preguntas abiertas

- ¿Cuáles son los pesos iniciales por defecto antes de la primera calibración empírica con la cohorte de validación?
- ¿La capa semántica contribuye al score final con un peso propio explícito, o solo se usa para desempatar/ajustar dentro de los rangos que produce la capa estructurada?
