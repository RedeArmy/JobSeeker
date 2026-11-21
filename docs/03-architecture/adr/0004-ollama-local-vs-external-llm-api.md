# ADR-0004: Ollama local vs. proveedor externo de LLM (estrategia de reparto)
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | ✅ Aprobado — 20-nov-2026, por el responsable del proyecto, tras la validación descrita al final de este documento |
| **Versión** | 1.1 — aprobado el 20-nov-2026 tras la validación completa |
| **Fecha** | Agosto 2026 · aprobado el 20-nov-2026 |
| **Documento relacionado** | System Design Document, Integration Design |

---

> **Enmendado por ADR-0018 (18-nov-2026):** `nomic-embed-text` v1.5 **deja de ser el modelo de embeddings por defecto** (el piloto lo midió significativamente peor que una línea base léxica y que cuatro alternativas). El valor provisional es `qwen3-embedding:0.6b`, versionado y calibrado por modelo, a confirmar con el protocolo de 300 pares. El resto de este ADR —Ollama como ruta por defecto, Claude API solo de forma selectiva y `llama3.2` para generación— **no cambia**. Donde este texto dice `nomic-embed-text`, léase «el modelo de embeddings vigente según ADR-0018».

---

## Contexto

El motor de matching requiere embeddings (capa semántica, BR-MATCH-01) y, potencialmente, generación de texto para casos de valor selectivo (p. ej. resúmenes o explicaciones enriquecidas del desglose de score). El sistema debe operar dentro de las restricciones de un proyecto de tesis: costo de infraestructura acotado, sin dependencia de un proveedor externo para su operación básica, y con disponibilidad razonable durante la validación con la cohorte USAC.

## Decisión

Se adopta **Ollama local como ruta por defecto** para toda la funcionalidad de IA/NLP del sistema: `nomic-embed-text` para embeddings y `llama3.2` para generación. Un **proveedor externo de LLM de propósito general** queda reservado únicamente para **casos selectivos de alto valor**, evaluados caso por caso, y nunca como dependencia obligatoria para el flujo principal de cálculo de score — el sistema debe poder operar completamente sin él.

## Alternativas consideradas

| Alternativa | Por qué se rechazó |
|---|---|
| Proveedor externo de LLM como ruta por defecto para toda la IA del sistema | Introduce costo recurrente por uso y una dependencia de disponibilidad/latencia de un tercero para el flujo crítico de cálculo de score, lo cual contradice NFR-AVAIL-02 (el sistema debe degradar de forma controlada, no depender de un servicio externo con SLA propio) |
| Solo Ollama local, sin ninguna opción externa | Válido como alternativa más simple, pero se descarta cerrar la puerta por completo: ciertos casos de alto valor (p. ej. generación de lenguaje más sofisticada en escenarios puntuales) pueden beneficiarse de un modelo externo más capaz, siempre que sea opcional y no bloqueante |

## Consecuencias

**Positivas**

- Sin costo recurrente por uso de API externa en el flujo principal; el costo de cómputo de IA se limita a la infraestructura local donde corre Ollama.
- El sistema no depende de la disponibilidad de un tercero para su funcionalidad core, alineado con NFR-AVAIL-02 (degradación controlada ante falla de Ollama, no ante falla de un proveedor externo).
- Deja abierta la puerta a mejorar casos puntuales de alto valor con un modelo externo más capaz, sin comprometer la operación general del sistema.

**Negativas / trade-offs aceptados**

- La calidad de embeddings y generación está acotada a lo que Ollama y los modelos locales (`nomic-embed-text`, `llama3.2`) pueden ofrecer, que puede ser inferior a modelos externos de última generación en casos complejos.
- Requiere infraestructura de cómputo local suficiente para correr Ollama con latencia aceptable (NFR-PERF-01), lo cual es una restricción operativa a gestionar durante desarrollo y validación.

## Preguntas abiertas

- ¿Cuáles son, concretamente, los "casos selectivos de alto valor" que justificarían usar un proveedor externo de LLM? Esta decisión no define criterios de activación; deben especificarse antes de implementar esa ruta.
- ¿Qué proveedor externo específico se usaría, bajo qué modelo de costos, y qué garantías de manejo de datos/PII debe cumplir antes de integrarse (ver `threat-model-stride.md` y `security-architecture.md`)?

## Validación previa a la aprobación (20-nov-2026)

- La enmienda del ADR-0018 (modelo de embeddings) ya está incorporada y el texto de decisión sigue siendo válido: Ollama local por defecto.
- Spring AI 2.0.2 con Ollama verificado en el entorno (`09-…` §7); `truncate=false` y `keep_alive` globales sí se envían, `num_ctx` solo por llamada.
- Condición: la ruta con proveedor externo no existe todavía y necesitará su propio ADR (criterios de activación, proveedor, tratamiento de PII) antes de implementarse; por eso las dos preguntas abiertas no bloquean esta decisión.
