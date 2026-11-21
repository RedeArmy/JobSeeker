# PR/FAQ — Working Backwards
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | 🟡 En revisión |
| **Versión** | 1.0 |
| **Fecha** | Agosto 2026 |
| **Documentos relacionados** | Vision Document, PRD-MVP, Market Research & Competitor Analysis |

---

## 1. Propósito del documento

Documento de "Working Backwards" (metodología de Amazon): se escribe el comunicado de prensa del producto terminado antes de construirlo, para forzar claridad sobre el valor entregado al usuario. Se complementa con preguntas frecuentes externas (candidato) e internas (comité técnico/académico).

---

## 2. Comunicado de prensa

### Un score de compatibilidad que por fin explica por qué

**Nueva plataforma elimina las postulaciones a ciegas mostrando, con desglose transparente, qué tan compatible es un candidato con una vacante — y viceversa.**

Ciudad de Guatemala — Los candidatos postulan hoy a vacantes sin saber realmente qué tan bien encajan, y los reclutadores reciben cientos de aplicaciones sin un criterio objetivo para priorizar. El Sistema Inteligente de Análisis de Compatibilidad CV-Vacante resuelve ambos problemas con un **score de compatibilidad bidireccional y explicable**: el candidato ve su compatibilidad con cada vacante que consulta, y el reclutador ve la de cada persona que postula a las suyas — con el desglose completo por dimensión (habilidades, experiencia, formación, ubicación, salario), nunca como una caja negra.

El sistema combina una capa semántica de embeddings con una capa de reglas ponderadas configurables, permitiendo que el score se explique en lenguaje natural y se recalibre sin reescribir el modelo. Además, las empresas de reclutamiento que reenvían candidatos a clientes finales pueden generar, con un clic, un CV con su propia marca a partir de los datos ya cargados del candidato — sin duplicar trabajo ni mantener plantillas manuales.

*"Antes de postular quiero saber si tengo una oportunidad real, no adivinar,"* — candidato de la cohorte de validación USAC.

*"Con cientos de postulaciones por vacante, necesito un criterio objetivo para decidir a quién revisar primero — y poder explicárselo a mi cliente,"* — reclutador de una empresa de outsourcing.

*"La tesis no solo valida un algoritmo de matching, valida que se puede construir con disciplina de ingeniería de nivel productivo dentro del tiempo de un proyecto de graduación,"* — Eder, autor, Ingeniería en Ciencias y Sistemas, USAC.

El sistema está disponible como plataforma web, sin necesidad de instalar nada, y comienza su fase de validación con estudiantes y egresados de la Facultad de Ingeniería de la USAC.

---

## 3. FAQ externas (candidato)

**¿Necesito experiencia previa para usar la plataforma?**
No. El motor de compatibilidad es de propósito general: evalúa perfiles de cualquier nivel de seniority, no solo candidatos sin experiencia (BR-CAND-03).

**¿Cómo se calcula mi score de compatibilidad?**
Combinando una capa semántica (qué tan similar es el contenido de tu CV a la vacante, mediante embeddings) y una capa de reglas ponderadas (habilidades, experiencia, formación, ubicación/modalidad, salario). Siempre puedes ver el desglose completo, nunca solo un número (BR-MATCH-02).

**¿El reclutador ve mi score comparado con el de otros candidatos?**
No directamente — ve tu score individual dentro de su pipeline de la vacante a la que postulaste, junto con los demás postulantes a esa misma vacante, pero no accede a tus scores en vacantes de otros reclutadores (BR-VIS-01 a 03).

**¿Puedo editar mi perfil después de subir mi CV?**
Sí, en cualquier momento. El score se recalcula automáticamente cuando tu perfil cambia (BR-MATCH-03).

**¿Qué pasa con mis datos si dejo de usar la plataforma?**
Puedes solicitar la eliminación de tu perfil y datos asociados (BR-DATA-02).

**¿Cuánto cuesta usar la plataforma?**
Pregunta abierta — el modelo de monetización no está definido en el alcance de esta tesis (ver `project-charter.md`, sección de supuestos).

---

## 4. FAQ internas (comité técnico / académico)

**¿Por qué un monolito modular y no microservicios?**
Ver ADR-0001. Con un desarrollador único y un marco de tiempo de tesis de aproximadamente 5-6 meses, el costo operativo de microservicios (orquestación, observabilidad distribuida, despliegues independientes) no se justifica frente al beneficio; el monolito modular preserva límites de dominio claros sin ese costo.

**¿Por qué un modelo híbrido de scoring y no puramente semántico?**
Ver ADR-0003. Un modelo puramente semántico (solo similitud de embeddings) es difícil de explicar dimensión por dimensión, lo cual viola el requisito de explicabilidad (BR-MATCH-02). El modelo híbrido permite mostrar tanto la afinidad semántica como el desglose estructurado ponderado.

**¿Cómo se mide el éxito de esta tesis?**
Con las métricas de PRD-MVP §3: precisión del score ≥ 0.85 frente a evaluación humana, SUS ≥ 72, NPS ≥ 40, adopción a 30 días ≥ 60%. Estas métricas gobiernan decisiones de NFR según `nfr-quality-attributes.md`.

**¿Por qué Thymeleaf + HTMX y no un framework SPA (React/Vue)?**
Ver ADR-0005. Reduce superficie de complejidad para un desarrollador único, evita duplicar lógica de validación entre backend y frontend, y es suficiente para los flujos de la aplicación (formularios, listados, pipelines) sin sacrificar interactividad gracias a HTMX.

**¿Por qué pgvector y no una base de datos vectorial dedicada?**
Ver ADR-0002. Evita introducir un componente de infraestructura adicional cuando PostgreSQL ya es la base de datos principal del sistema; pgvector cubre el volumen de datos esperado durante la validación (NFR-SCAL-02).

**¿Cómo se garantiza la calidad de ingeniería con un solo desarrollador?**
Mediante prácticas normalmente asociadas a equipos grandes, adoptadas deliberadamente como parte de la contribución de la tesis: documentación previa a la implementación, CI/CD desde el primer sprint, ADRs para decisiones irreversibles, cobertura de pruebas ≥ 80% en lógica de negocio (NFR-MAINT-01), y trazabilidad de requisitos de negocio (BR-XXX) hasta el código.

**¿Qué pasa si Ollama no está disponible en producción?**
El sistema degrada de forma controlada mostrando el último score calculado junto con un aviso, en vez de fallar por completo (NFR-AVAIL-02).

## 5. Preguntas abiertas

- ¿El comité de tesis requiere un formato o audiencia específica adicional para este PR/FAQ (por ejemplo, una versión orientada a posibles inversionistas o aliados de USAC)?
- ¿Existe un modelo de monetización que deba reflejarse en las FAQ externas, o se mantiene fuera de alcance hasta después de la validación?
