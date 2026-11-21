# ADR-0001: Monolito modular vs. microservicios
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | ✅ Aprobado — 20-nov-2026, por el responsable del proyecto, tras la validación descrita al final de este documento |
| **Versión** | 1.1 — aprobado el 20-nov-2026 tras la validación completa |
| **Fecha** | Agosto 2026 · aprobado el 20-nov-2026 |
| **Documento relacionado** | System Design Document |

---

## Contexto

El sistema debe soportar seis módulos de negocio con límites de dominio claros (`auth`, `cv`, `job`, `matching`, `ats`, `tracker`) más código compartido (`shared-kernel`). El proyecto es desarrollado por **una sola persona** dentro de un marco de tiempo de tesis de aproximadamente 5-6 meses (`project-charter.md`), con requisitos de disciplina de ingeniería equivalentes a un entorno productivo (CI/CD, testing, observabilidad).

## Decisión

Se adopta un **monolito modular**: un único desplegable Spring Boot en el que cada módulo de negocio mantiene límites de dominio estrictos internamente (DDD + arquitectura hexagonal + vertical slice, ver `coding-standards.md` §2), pero se compila y despliega como una sola unidad.

## Alternativas consideradas

| Alternativa | Por qué se rechazó |
|---|---|
| Microservicios (un servicio desplegable por módulo) | El costo operativo (orquestación, descubrimiento de servicios, observabilidad distribuida, despliegues independientes, resiliencia entre llamadas de red) no se justifica con un solo desarrollador y un plazo de tesis fijo; el beneficio principal de microservicios (escalado y despliegue independientes por equipo) no aplica cuando hay un único equipo |
| Monolito sin modularización interna (todo en un solo paquete) | Perdería la separación de dominio necesaria para razonar sobre el sistema, dificultaría pruebas aisladas por módulo y una eventual extracción futura a servicios si el proyecto continúa después de la tesis |

## Consecuencias

**Positivas**

- Despliegue y operación simples: un solo artefacto, un solo pipeline de CI/CD.
- Los límites de dominio se preservan a nivel de código (paquetes, reglas de dependencia), permitiendo una futura extracción a microservicios si el producto escala más allá de la validación de tesis, sin reescritura completa.
- Transacciones que cruzan módulos relacionados (p. ej. postulación + score) pueden resolverse sin necesidad de sagas o consistencia eventual compleja.

**Negativas / trade-offs aceptados**

- Todo el sistema escala como una unidad; no es posible escalar `matching-module` (probablemente el más intensivo en cómputo) de forma independiente del resto.
- Un fallo severo no aislado en un módulo puede afectar la disponibilidad de los demás si no se gestionan cuidadosamente los límites de fallo internos.
- Requiere disciplina activa (revisión de dependencias en cada PR, ver `definition-of-done.md`) para que los límites de módulo no se erosionen con el tiempo, ya que el compilador no impone la separación tan estrictamente como procesos separados lo harían.

## Preguntas abiertas

- ~~¿Se debe reforzar la regla de dependencia entre módulos con un chequeo automatizado (p. ej. ArchUnit) en CI?~~ **Resuelta el 18-nov-2026 por el ADR-0015:** ArchUnit (ARCH-01..10) corre como paso bloqueante de cada build.

## Validación previa a la aprobación (20-nov-2026)

- Decisión vigente: el único desplegable es el módulo `app` (aplicado en el repositorio; ADR-0026 propuesto) y los límites entre módulos se comprueban en cada build con ArchUnit (ADR-0015, aprobado).
- Es el mismo patrón que opera `../Clavaris` con seis módulos y 126 migraciones: la decisión no es especulativa.
- La pregunta abierta sobre reforzar la regla con un chequeo automatizado queda resuelta por el ADR-0015.
