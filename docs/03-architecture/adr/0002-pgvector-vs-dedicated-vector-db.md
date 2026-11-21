# ADR-0002: pgvector vs. base de datos vectorial dedicada
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | 🟡 En revisión |
| **Versión** | 1.0 |
| **Fecha** | Agosto 2026 |
| **Documento relacionado** | System Design Document, Data Model |

---

## Contexto

La capa semántica del motor de matching (BR-MATCH-01) requiere almacenar y consultar embeddings vectoriales de perfiles de candidato y descripciones de vacante, para calcular similitud coseno. El volumen esperado durante la fase de validación es de hasta 10,000 perfiles y 1,000 vacantes activas (NFR-SCAL-02).

## Decisión

Se adopta **pgvector** como extensión de PostgreSQL 16, ya elegido como base de datos principal del sistema, en vez de introducir una base de datos vectorial dedicada.

## Alternativas consideradas

| Alternativa | Por qué se rechazó |
|---|---|
| Base de datos vectorial dedicada (p. ej. Pinecone, Weaviate, Milvus) | Introduce un componente de infraestructura adicional a operar, respaldar y monitorear, con el consiguiente costo de integración (sincronización de datos entre Postgres y el motor vectorial) que no se justifica en el volumen de datos esperado durante la validación |
| Búsqueda de similitud calculada en memoria de aplicación (sin índice vectorial) | No escala más allá de un volumen trivial de perfiles/vacantes; incumpliría NFR-PERF-01 conforme crece la cohorte |

## Consecuencias

**Positivas**

- Una sola base de datos que administrar, respaldar y migrar (Flyway) tanto en desarrollo local (Docker) como en producción (Supabase).
- Consultas híbridas (filtros estructurados SQL + similitud vectorial) en una sola query, sin unir resultados de dos sistemas distintos.
- Menor superficie operativa para un desarrollador único.

**Negativas / trade-offs aceptados**

- pgvector es menos especializado que un motor vectorial dedicado en términos de rendimiento a gran escala (millones de vectores); si el volumen de datos crece significativamente más allá de lo estimado en NFR-SCAL-02, podría requerir revisión de esta decisión.
- Depende de que Supabase soporte la extensión `pgvector` de forma administrada en producción (supuesto documentado en `project-charter.md`).

## Preguntas abiertas

- ¿Qué tipo de índice (`ivfflat` vs. `hnsw`) se calibra como configuración por defecto, y con qué parámetros, dado el volumen esperado de NFR-SCAL-02?
