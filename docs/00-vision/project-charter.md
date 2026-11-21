# Project Charter
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | 🟡 En revisión |
| **Versión** | 1.0 |
| **Fecha** | Agosto 2026 |
| **Documentos relacionados** | Vision Document, PRD-MVP, Roadmap & Release Plan |

---

## 1. Propósito del documento

Establecer de forma centralizada el patrocinio, los objetivos, el alcance, los criterios de éxito, el marco de tiempo de alto nivel, las restricciones y los interesados del proyecto — como referencia única para no repetir este contexto en cada documento posterior.

## 2. Identificación del proyecto

| Campo | Valor |
|---|---|
| **Nombre del proyecto** | Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones |
| **Tipo** | Proyecto de graduación (tesis) — Ingeniería en Ciencias y Sistemas, USAC |
| **Owner / autor** | Eder — USAC, Ingeniería en Ciencias y Sistemas |
| **Asesor de tesis** | Por confirmar (ver Preguntas abiertas) |
| **Cohorte de validación** | Estudiantes y egresados de la Facultad de Ingeniería, USAC |

## 3. Objetivos del proyecto

| Objetivo | Descripción |
|---|---|
| Académico | Demostrar, mediante un producto funcional, la aplicación de disciplina de ingeniería de nivel productivo (DDD, arquitectura hexagonal, CI/CD, testing, ADRs) dentro de un proyecto de graduación |
| Producto | Construir un motor de compatibilidad CV-vacante bidireccional y explicable, con un módulo complementario de generación de CV de marca para reclutadores — **no solo como entregable de tesis, sino como producto real que sigue operando y aportando valor después de la defensa** (`roadmap-and-release-plan.md` §1) |
| Validación | Medir empíricamente precisión de matching, usabilidad, satisfacción y adopción de forma continua, empezando con usuarios reales desde el cierre de la Ola 1, no en una fase final aislada (ver PRD-MVP §3, `roadmap-and-release-plan.md` §10) |

## 4. Alcance

### 4.1 Dentro de alcance (MVP)

Ver PRD-MVP sección 7 para el detalle funcional por módulo (`auth`, `cv`, `job`, `matching`, `ats`, `tracker`).

### 4.2 Fuera de alcance

Ver PRD-MVP sección 4 (no-objetivos): videoentrevistas, integración con ATS externos, app móvil nativa, prevención avanzada de fraude, soporte multi-idioma.

## 5. Criterios de éxito

Los criterios de éxito del proyecto son las métricas de PRD-MVP §3, reproducidas aquí por ser el criterio de cierre formal del proyecto de tesis:

| Objetivo | Métrica | Meta |
|---|---|---|
| Precisión del matching | Precisión del score vs. evaluación humana | ≥ 0.85 |
| Usabilidad | System Usability Scale (SUS) | ≥ 72 |
| Satisfacción | Net Promoter Score (NPS) | ≥ 40 |
| Adopción | % de usuarios activos a 30 días | ≥ 60% |

## 6. Marco de tiempo de alto nivel

El detalle de olas, milestones y dependencias entre módulos vive en `roadmap-and-release-plan.md`; este charter solo fija el marco general:

- Roadmap de implementación reestructurado en **3 olas de entrega de valor** (no fases académicas secuenciales): Ola 1 — cimiento production-grade usable de extremo a extremo; Ola 2 — screening y ATS completo; Ola 3 — automatización y profundidad. Cada ola entrega algo usable por candidatos y reclutadores reales, no un artefacto interno — ver `roadmap-and-release-plan.md` §2.
- Duración total estimada: **~40 sprints (~9-10 meses)** para el alcance completo (Olas 1-3). Esto excede el marco original de 5-6 meses del protocolo de tesis — la diferencia no es alcance recortado ni fecha arbitraria: es el costo real de construir auth completo desde el inicio (no una versión básica) y de entregar el pipeline de reclutador en dos pasadas para que exista algo usable mucho antes del cierre total (`roadmap-and-release-plan.md` §6).
- **Punto de control recomendado para una eventual defensa académica: cierre de la Ola 2 (~32 sprints, ~7.4 meses)** — ahí el producto ya es íntegro y demostrable (motor de matching + screening + ATS completos). La Ola 3 (automatización, generador de CV, endurecimiento operativo) puede continuar como desarrollo de producto real después de la defensa, sin acoplar su fecha a un requisito de graduación.
- El alcance funcional no se recortó para caber en ninguna estimación anterior; el cronograma se ajustó al alcance real diseñado y se resecuenció para maximizar el valor entregado temprano, decisión adoptada en `roadmap-and-release-plan.md` §11.

## 7. Restricciones

| Restricción | Impacto |
|---|---|
| Desarrollador único | Condiciona decisiones de arquitectura (ver ADR-0001, monolito modular en vez de microservicios) y de proceso (ver `git-workflow.md`, flujo de PR/self-review para solo dev) |
| Marco de tiempo de tesis (~5-6 meses) original vs. ~7.4 meses hasta el cierre de la Ola 2 | No permite iteraciones extensas de calibración del modelo de matching antes del punto de control académico; la calibración de pesos se planifica como configurable, no hardcodeada (BR-MATCH-05), y continúa después del punto de control, con tráfico real (Ola 3, `roadmap-and-release-plan.md` §5) |
| Sostenibilidad operativa post-tesis | Al ser un producto real, no un entregable de un solo uso, requiere una respuesta a quién opera y financia la infraestructura (Supabase, cómputo de Ollama) después de la defensa — ver pregunta abierta en la sección 10 |
| Cohorte inicial limitada a USAC | Los primeros resultados de precisión/SUS/NPS/adopción (medidos desde el cierre de la Ola 1, sección 6) son representativos de esta cohorte; la medición continua (`roadmap-and-release-plan.md` §10) permite ampliar la base de usuarios reales más allá de USAC sin esperar a un ciclo de validación cerrado |
| Infraestructura de IA local (Ollama) | El rendimiento y la disponibilidad de los flujos de matching dependen de recursos de cómputo local, no de un servicio gestionado con SLA (ver NFR-AVAIL-02) |

## 8. Interesados (stakeholders)

| Interesado | Rol | Interés principal |
|---|---|---|
| Eder (autor) | Desarrollador único / Product Owner | Entregar un producto funcional y una tesis aprobada |
| Asesor de tesis | Supervisión académica | Rigor metodológico, cumplimiento del protocolo de tesis |
| Comité de tesis / revisores | Evaluación final | Validez de la contribución académica y técnica |
| Cohorte de validación (candidatos USAC) | Usuario final — candidato | Compatibilidad confiable y explicable con vacantes reales o simuladas |
| Reclutadores participantes en la validación | Usuario final — reclutador | Priorización objetiva de candidatos, generación de CV de marca |

## 9. Supuestos

- La cohorte inicial (estudiantes/egresados USAC) está disponible como usuarios reales desde el cierre de la Ola 1 (`roadmap-and-release-plan.md` §3), no en una fase de validación aislada al final.
- Se cuenta con acceso a recursos de cómputo suficientes para correr Ollama localmente durante desarrollo y operación continua — incluyendo, más allá del periodo de tesis, un plan de cómputo/hosting sostenible (ver sección 10).
- El comité de tesis acepta la documentación en el formato y estructura definidos en este repositorio (`docs/`) como evidencia del proceso, con el cierre de la Ola 2 como punto de control demostrable si se requiere una fecha de defensa fija.

## 10. Preguntas abiertas

- ¿Quién es formalmente el asesor de tesis y quiénes integran el comité evaluador? (Necesario para completar la sección 2 y 8 con nombres reales.)
- ¿Existe un presupuesto o restricción de infraestructura (por ejemplo, costos de Supabase, hosting y cómputo de Ollama en producción) que deba documentarse como restricción adicional? Se vuelve más urgente al tratarse de un producto que debe seguir operando después de la tesis, no solo hasta la defensa.
- ¿El protocolo de tesis define fechas fijas de entrega/defensa que deban anclarse al cierre de la Ola 2 (~32 sprints, sección 6), o hay flexibilidad para anclarla en otro punto de control?
- ¿Quién opera, mantiene y financia el producto después de la defensa académica — el autor de forma independiente, USAC, o un modelo de continuidad aún no definido? (relacionado con la pregunta de monetización ya abierta en `vision-document.md` §9)
- ¿Requiere el comité evaluador un trámite formal de extensión de plazo dado que el marco hasta el cierre de la Ola 2 (~7.4 meses) supera el estimado original de 5-6 meses, o el protocolo ya contempla margen suficiente sin trámite adicional?
