# ADR-0005: Thymeleaf + HTMX vs. SPA (React/Vue)
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | ✅ Aprobado — 20-nov-2026, por el responsable del proyecto, tras la validación descrita al final de este documento |
| **Versión** | 1.1 — aprobado el 20-nov-2026 tras la validación completa |
| **Fecha** | Agosto 2026 · aprobado el 20-nov-2026 |
| **Documento relacionado** | System Design Document |

---

## Contexto

El frontend debe cubrir formularios (registro, carga de CV, publicación de vacantes), listados filtrables (pipeline de reclutador, tracker de candidato) y vistas de desglose de score, con interactividad moderada. El proyecto es desarrollado por una sola persona dentro de un plazo de tesis acotado.

## Decisión

Se adopta **Thymeleaf + HTMX**: renderizado del lado del servidor con fragmentos HTML actualizados dinámicamente vía HTMX, sin introducir un framework SPA.

## Alternativas consideradas

| Alternativa | Por qué se rechazó |
|---|---|
| SPA con React/Vue/Angular | Duplica lógica de validación y de estado entre frontend y backend; añade un stack de build/tooling adicional (bundlers, gestión de estado, tipado) que consume tiempo de desarrollo no disponible para un desarrollador único en un plazo fijo; requeriría además exponer una API REST completa desde el día uno solo para servir la UI |
| Renderizado del lado del servidor sin HTMX (solo recarga de página completa) | Suficiente funcionalmente, pero degrada la experiencia de usuario en interacciones frecuentes (filtrar pipeline, ver desglose de score) sin justificación, cuando HTMX resuelve esto con costo de implementación mínimo |

## Consecuencias

**Positivas**

- Una sola fuente de verdad para la lógica de validación y de negocio (backend Java), sin duplicarla en el cliente.
- Superficie de mantenimiento reducida: no hay un segundo proyecto (frontend SPA) con su propio ciclo de dependencias y versiones.
- HTMX permite interacciones dinámicas (actualizar el pipeline, expandir un desglose de score) sin escribir JavaScript de gestión de estado.

**Negativas / trade-offs aceptados**

- Menor flexibilidad para interacciones de UI altamente dinámicas o offline-first, si el producto las requiriera en el futuro.
- La API REST completa (documentada en `api-contract-overview.md`) se desarrolla igual, pero no es el único consumidor de la lógica de aplicación en el corto plazo — es una decisión que puede revisarse si el producto necesita integraciones externas más adelante (ver `vision-document.md` §6, integraciones con ATS externos).

## Preguntas abiertas

- Si en el futuro se requiere una app móvil nativa (fuera de alcance del MVP, ver PRD-MVP §4), ¿se expondría la misma API REST de `api-contract-overview.md`, o se diseñaría una API separada?

## Validación previa a la aprobación (20-nov-2026)

- El *starter* de Thymeleaf carga en Spring Boot 4.1.2 / Java 25 (`07-…`) y el ADR-0020 ya fijó la combinación sesión + CSRF + CSP estricta para `/app/**`.
- Pendiente de verificar en un navegador real durante el Sprint 0: CSP estricta y cookie `__Host-` con HTMX (`09-…` §10).
- La pregunta sobre una app móvil queda fuera del MVP (PRD-MVP §4) y no condiciona la decisión.
