# ADR-0006: Thymeleaf + openhtmltopdf vs. LaTeX para generación de CV
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | 🟡 En revisión |
| **Versión** | 1.0 |
| **Fecha** | Agosto 2026 |
| **Documento relacionado** | System Design Document, PRD-MVP §6 |

---

## Contexto

El sistema debe generar dos tipos de documentos PDF: el CV propio del candidato y el CV de marca del reclutador (merge de una plantilla propia con los datos del candidato, BR-CVGEN-01 a 03). Ambos deben producirse de forma confiable, dentro de un stack operable por un desarrollador único.

## Decisión

Se adopta **Thymeleaf (plantillas HTML) + openhtmltopdf** para la generación de ambos tipos de CV en PDF.

## Alternativas consideradas

| Alternativa | Por qué se rechazó |
|---|---|
| LaTeX | Rompe la homogeneidad del stack 100% Java (requeriría invocar un motor de renderizado externo o un binario LaTeX); complica la compatibilidad con GraalVM Native (consideración transversal del stack); introduce riesgo de seguridad/inyección si el contenido del candidato o de la plantilla del reclutador se interpola directamente en comandos LaTeX sin sanitización cuidadosa |
| Generación de PDF con una librería de bajo nivel (p. ej. construcción directa con iText/PDFBox sin capa de plantillas HTML) | Obligaría a definir el layout de cada plantilla de marca de forma programática en vez de declarativa, dificultando que las plantillas de reclutador (BR-CVGEN-01) sean fáciles de crear/mantener |

## Consecuencias

**Positivas**

- Stack 100% Java, sin dependencias externas de otro lenguaje/runtime.
- Las plantillas de marca del reclutador se definen como plantillas Thymeleaf (HTML + CSS), reutilizando el mismo motor de plantillas que el resto del frontend — no se introduce un segundo lenguaje de templating.
- Compatible con la consideración de empaquetado GraalVM Native mencionada como criterio transversal del stack.
- Menor superficie de riesgo de inyección comparado con interpolación directa en LaTeX, siempre que se apliquen las prácticas estándar de escape de Thymeleaf.

**Negativas / trade-offs aceptados**

- openhtmltopdf tiene soporte más limitado de CSS avanzado que un motor de renderizado de navegador completo; plantillas de marca muy complejas visualmente podrían requerir ajustes o simplificación de diseño.
- La fidelidad tipográfica de LaTeX (particularmente relevante en documentos académicos) no es un objetivo de este sistema, ya que los CVs generados son documentos de negocio, no académicos.

## Preguntas abiertas

- ¿Existe un límite de complejidad de diseño (columnas, gráficos, iconografía) que las plantillas de marca de reclutador puedan requerir y que deba validarse contra las capacidades reales de openhtmltopdf antes de construir el generador de CV en la Ola 3 (`roadmap-and-release-plan.md` §5)?
