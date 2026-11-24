# Definition of Done
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | 🟡 En revisión |
| **Versión** | 1.3 — añadido el #17 (deuda técnica registrada o pagada, `technical-debt-register.md`); antes, 1.2: criterio #5 automatizado con ArchUnit (ADR-0015) y añadido el #16 (CSRF, NFR-SEC-08, ADR-0020) |
| **Fecha** | Agosto 2026 · actualizado en noviembre 2026 |
| **Documentos relacionados** | Test Strategy, Git Workflow, Coding Standards, Registro de Deuda Técnica (`technical-debt-register.md`) |

---

## 1. Propósito del documento

Fijar el checklist mínimo que toda historia de usuario o cambio debe cumplir antes de fusionarse a `main`, para que "terminado" sea un criterio verificable y no una apreciación subjetiva — especialmente relevante sin un segundo revisor humano (`git-workflow.md` §5).

## 2. Checklist de Definition of Done

| # | Criterio | Verificación |
|---|---|---|
| 1 | Todas las pruebas pasan (unitarias, integración, y E2E si el cambio toca un flujo cubierto) | Pipeline de CI en verde |
| 2 | Cobertura de lógica de negocio nueva/modificada ≥ 80% | Reporte de cobertura en CI (NFR-MAINT-01) |
| 3 | Self-review completo del diff realizado | Checklist marcado en el PR (`git-workflow.md` §5) |
| 4 | Linter/formatter sin errores ni excepciones manuales | Paso de Checkstyle/formateador en CI (NFR-MAINT-02) |
| 5 | Las pruebas de arquitectura ARCH-01 a ARCH-14 pasan: regla de dependencia hexagonal (sin Spring, JPA, servlet, Jackson ni Micrometer en `domain/`; `application/` sin `infrastructure/` ni web/persistencia y solo con transacciones de Spring), sin acceso a otro módulo salvo por su superficie pública, sin ciclos entre módulos y sin escrituras desde `afterCommit` (ADR-0015, las reglas del proyecto §7.2). Toda excepción lleva un comentario `ARCH-xx` con motivo y fecha de revisión, aprobada en el PR | Prueba ArchUnit del módulo `app` ejecutada en cada build (`mvn verify`) y en CI; una violación rompe el build, no hay modo de advertencia |
| 6 | Toda migración de esquema es Flyway, con versión por marca de tiempo y solo hacia adelante; si es incompatible, va en fases expandir/contraer (ADR-0027) | Revisión de migración en el PR (`data-model.md` §2) y comprobaciones de CI de ADR-0027 |
| 7 | Comentarios de código siguen el estándar de `coding-standards.md` §5 (por qué, no qué; BR-XXX referenciado cuando aplica) | Revisión manual en self-review |
| 8 | Sin código comentado y sin TODOs sin dueño y razón | Revisión manual en self-review |
| 9 | Sin secretos ni credenciales hardcodeadas | Revisión manual + `.gitignore` de `.env` (`security-architecture.md` §4) |
| 10 | Documentación de API actualizada si el cambio afecta un contrato existente | OpenAPI del módulo actualizado, conforme a `api-contract-overview.md` |
| 11 | Toda regla de negocio nueva implicada por el cambio está reflejada en `business-rules.md` con su ID | Revisión cruzada con `business-rules.md` |
| 12 | El cambio no introduce una nueva dependencia externa sin registrar la decisión (ADR si es arquitectónicamente significativa) | Revisión manual |
| 13 | Todo endpoint HTTP nuevo o modificado está anotado (`@Operation`/`@ApiResponse`/`@Schema`) y el spec OpenAPI exportado pasa el linter de CI (ADR-0010, NFR-MAINT-03) | Paso de lint de OpenAPI en CI |
| 14 | Todo endpoint HTTP nuevo tiene una política de rate limiting explícita asignada (no queda sin límite por omisión, NFR-SEC-06) | Revisión manual del filtro/anotación de Bucket4j en el PR (ADR-0011) |
| 15 | Todo endpoint o caso de uso nuevo expone al menos una métrica de negocio propia vía Micrometer y logging estructurado con `traceId` (NFR-OBS-05) | Revisión manual + verificación en `/actuator/prometheus` en el ambiente de PR/staging |
| 16 | Toda ruta mutante nueva o modificada de `/app/**` (`POST/PUT/PATCH/DELETE`) exige el token CSRF y está cubierta por la prueba que recorre todas las rutas mutantes y verifica `403` sin token; todo endpoint de `/api/v1/**` está cubierto por la prueba que verifica `401` sin Bearer y `403`/`404` con otra cuenta o capacidad equivocada (NFR-SEC-08, NFR-SEC-03, ADR-0020) | Prueba automática en CI; ningún endpoint mutante nuevo queda fuera de la lista que recorre la prueba |
| 17 | La deuda técnica que el cambio deja o descubre queda registrada en `technical-debt-register.md` (ficha nueva con evidencia, interés, disparador y criterio de cierre) o se paga en el mismo cambio; la que el cambio cierra se mueve a la sección de resueltas con su PR y su evidencia | Revisión manual de la PR contra el registro; la casilla indica `n/a` si el cambio no deja ni cierra deuda |

## 3. Criterios adicionales para cambios en `matching-module`

Dada la naturaleza diferenciadora del motor de compatibilidad, los cambios en `matching-module` cumplen además:

- El desglose explicable (BR-MATCH-02) se valida con al menos un caso de prueba que verifique que el desglose está presente y es consistente con el score total.
- Un cambio de lógica de cálculo incrementa `algorithm_version` (BR-MATCH-04), nunca sobrescribe silenciosamente el comportamiento de una versión existente.

## 4. Qué NO cubre este documento

Este checklist no reemplaza `acceptance-criteria.md` (criterios de aceptación funcionales por historia de usuario) — DoD es transversal a todo cambio, mientras que los criterios de aceptación son específicos de cada feature.

## 5. Preguntas abiertas

- ~~¿El criterio #5 (regla de dependencia hexagonal) se automatiza con una herramienta como ArchUnit?~~ **Resuelta el 18-nov-2026 (ADR-0015, aprobado):** sí, con ArchUnit en el módulo `app`, como paso bloqueante del build. Hasta que el primer módulo tenga clases, las reglas se declaran con `allowEmptyShould(true)` y el criterio se cumple por defecto; deja de ser trivial desde la primera clase de negocio.
- ¿Se requiere aprobación explícita de un tercero (asesor de tesis) para cambios que afecten decisiones ya documentadas en un ADR, o el self-review del desarrollador es suficiente dentro del checklist de DoD?
