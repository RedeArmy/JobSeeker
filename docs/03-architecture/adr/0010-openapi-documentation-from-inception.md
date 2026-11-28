# ADR-0010: Documentación OpenAPI desde el inicio (springdoc, code-first, spec agregado)
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | ✅ Aprobado — 20-nov-2026, por el responsable del proyecto, tras la validación descrita al final de este documento |
| **Versión** | 1.2 — enmienda 1 del 28-nov-2026: springdoc 3.2.1 y nueva herramienta de validación (ver §«Enmienda 1») |
| **Fecha** | Agosto 2026 · aprobado el 20-nov-2026 |
| **Documento relacionado** | API Contract Overview, System Design Document, Definition of Done |

---

## Contexto

`api-contract-overview.md` §10 difería la producción de OpenAPI a "cada módulo, al iniciar su implementación" — un enfoque por-fase, no transversal desde el arranque. El negocio exige ahora que **toda** API esté documentada profesionalmente desde el inicio (Ola 1), no como una tarea de cierre de cada módulo. También queda abierta en ese documento (§11) la pregunta de si se publica un OpenAPI agregado o uno por módulo, dado que es un monolito modular con un único desplegable.

## Decisión

1. **Generación code-first con `springdoc-openapi`** (integración estándar para Spring Boot; con Boot 4.1 se usa la serie 3.x de springdoc (3.2.1 desde la enmienda 1), porque la 2.x no lo soporta — ADR-0014, genera OpenAPI 3.1 a partir de los controladores y DTOs anotados) — no contract-first con YAML escrito a mano ni generación de stubs de servidor. Justificación: un solo desarrollador se beneficia más de que el código sea la fuente de verdad sincronizada automáticamente, que de mantener un contrato YAML separado en paralelo al código (riesgo real de que diverjan sin un segundo revisor que lo detecte).
2. **Un único documento OpenAPI agregado**, no uno por módulo — resuelto por la realidad técnica ya fijada en ADR-0001: es un solo proceso Spring Boot con un único contexto de aplicación, por lo que `springdoc-openapi` produce naturalmente **un** JSON/YAML y **una** UI de Swagger para todo el sistema. La organización por módulo (`domain-model.md` §2) se preserva **dentro** de ese documento único mediante `@Tag` por módulo (`auth`, `cv`, `job`, `matching`, `ats`, `tracker`), no mediante documentos separados.
3. **Anotado obligatorio desde el primer endpoint de la Ola 1** (`@Operation`, `@ApiResponse`, `@Schema` con descripción de negocio, no solo el tipo) — no una tarea diferida. Se agrega como criterio de `definition-of-done.md`.
4. **Validación en CI:** un paso de pipeline exporta el spec generado y lo valida con un linter de OpenAPI (p. ej. Spectral) contra un ruleset mínimo (todo endpoint tiene `summary`, todo código de error usa el sobre de `api-contract-overview.md` §6, todo endpoint versionado bajo `/api/v1`) — un spec que no pasa el lint bloquea el merge, igual que Checkstyle (`coding-standards.md` §6).
5. **Swagger UI expuesta en desarrollo/staging, protegida detrás de autenticación en producción** (nunca pública sin control de acceso, dado que documenta la superficie completa de ataque del sistema — ver `threat-model-stride.md`).

## Alternativas consideradas

| Alternativa | Por qué se rechazó |
|---|---|
| Contract-first (YAML de OpenAPI escrito a mano, código generado desde el contrato) | Proceso más riguroso en equipos grandes, pero para un desarrollador único añade el riesgo de que el YAML y el código diverjan sin que nadie más lo note antes del merge; `springdoc` code-first mantiene una sola fuente de verdad |
| OpenAPI diferido a cierre de cada módulo (diseño original de `api-contract-overview.md` §10) | Contradice el pedido explícito de "documentación profesional desde el inicio"; además, diferirlo generó en la práctica documentación incompleta o inconsistente entre módulos en proyectos comparables |
| Un archivo OpenAPI por módulo | No refleja la realidad de un único desplegable (ADR-0001); forzaría un paso de agregación manual innecesario cuando `springdoc` ya lo resuelve de forma nativa con un contexto Spring único |

## Consecuencias

**Positivas**

- Resuelve la pregunta abierta de `api-contract-overview.md` §11 sobre agregación.
- El spec siempre refleja el código real (generado, no mantenido a mano), eliminando el riesgo de documentación obsoleta.
- La validación en CI convierte "documentado profesionalmente" en un criterio verificable, no una apreciación subjetiva — mismo espíritu que el resto de `definition-of-done.md`.

**Negativas / trade-offs aceptados**

- Depende de la calidad de las anotaciones que el desarrollador efectivamente escriba — `springdoc` no puede inferir una buena descripción de negocio si el código no la tiene; el criterio de DoD es la salvaguarda, no una garantía automática.
- Añade un paso más al pipeline de CI (lint del spec exportado), con el consiguiente tiempo de build adicional — aceptable frente al costo de documentación desactualizada.

## Preguntas abiertas

- ¿Qué ruleset exacto de Spectral (o linter equivalente) se adopta — uno de la comunidad (p. ej. `spectral:oas`) o uno propio con las reglas específicas de `api-contract-overview.md` §6/§8?
- ¿El spec exportado se versiona en el repositorio (snapshot commiteado en cada release) además de servirse en tiempo de ejecución, para tener un histórico auditable de la evolución del contrato?

## Validación previa a la aprobación (20-nov-2026)

- springdoc-openapi 3.2.0 verificado con Spring Boot 4.1.2 (`07-…`); la serie 2.x no soporta Boot 4. Se corrigió el texto que hablaba de Boot 3.x.
- Un único documento agregado es coherente con el desplegable único (`app`, ADR-0026).
- Pendiente para el Sprint 0, no bloquea: el *ruleset* concreto del linter y si se versiona un *snapshot* del spec.

## Enmienda 1 (28-nov-2026): springdoc 3.2.1 y validación con el esquema oficial

**Por qué.** springdoc 3.2.1 (una versión de parche) pasa a swagger-core 3.0.0, sobre Jackson 3. La PR de Dependabot que lo subía (#66) solo cambiaba springdoc, y la aplicación dejaba de arrancar (`ClassNotFoundException: io.swagger.v3.core.util.MapperCustomizer`). La causa era `swagger-parser`, la herramienta con que las pruebas validaban el spec (decisión 4): sigue sobre swagger-core 2 y Jackson 2, arrastraba `swagger-core` 2.2.x y ganaba la mediación de versiones de Maven también en el *classpath* de ejecución. Ninguna versión de `swagger-parser` publicada a esta fecha (la última es 2.1.49) funciona con swagger-core 3: no llega a inicializarse.

**Qué cambia.**

1. springdoc **3.2.1** y swagger **3.0.0**. Desde la 3.0.0 los artefactos de swagger son Jakarta sin sufijo (`swagger-annotations`, `swagger-core`, `swagger-models`); `-jakarta` se quedó en la 2.2.x. El POM padre fija los tres, no solo las anotaciones, para que ninguna otra dependencia los degrade.
2. La decisión 4 se cumple sin `swagger-parser`:
   - la **validez** del spec se comprueba contra el **esquema JSON oficial de OpenAPI 3.1** de la OpenAPI Initiative (`schema-base`, que además valida cada objeto Schema contra el dialecto), copiado en `app/src/test/resources/openapi-3.1/` para no depender de la red;
   - el validador es `com.networknt:json-schema-validator` **3.0.9**: soporta JSON Schema 2020-12, usa Jackson 3 como el resto de la aplicación, licencia Apache 2.0, publicado hace más de dos semanas, solo en pruebas;
   - las **reglas del proyecto** (`OpenApiRules`) se aplican igual que antes, sobre el modelo de swagger-core 3.
3. Dependabot agrupa springdoc y swagger en una sola PR, para que no vuelva a subir uno sin el otro.

**Alternativas descartadas.** Mantener springdoc 3.2.0 sin actualizar (no resuelve el problema: la siguiente versión traerá el mismo salto). Spectral en un paso aparte de CI, como sugería la decisión 4: añade un binario de Node y otro paso al pipeline para algo que el esquema oficial ya cubre en la misma prueba.

**Pregunta abierta que esto responde en parte.** El *ruleset* es el propio (`OpenApiRules`, reglas de `api-contract-overview.md` §6 y §8) más el esquema oficial; no se adopta `spectral:oas`.
