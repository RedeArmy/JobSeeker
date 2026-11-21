# ADR-0013: Eliminación de cuenta — periodo de gracia + anonimización, no borrado inmediato
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | 🟡 En revisión |
| **Versión** | 1.0 |
| **Fecha** | Agosto 2026 |
| **Documento relacionado** | Business Rules (BR-DATA-02 a 06), Security Architecture, Domain Model |

---

## Contexto

`security-architecture.md` §10 dejaba abierta la pregunta desde hace varias rondas: "si la eliminación es inmediata y en cascada... o se retienen registros anonimizados". Con la intención explícita de tener usuarios reales desde el cierre de la Ola 1 (`roadmap-and-release-plan.md`), esta pregunta deja de ser teórica — necesita una respuesta concreta antes de que el sistema reciba datos reales.

Dos restricciones compiten:

1. El titular de los datos (BR-DATA-02) tiene derecho a que sus datos personales dejen de existir cuando lo pida.
2. Un reclutador que ya evaluó a ese candidato (postulación real, score calculado, decisión de pipeline tomada) tiene un interés legítimo en no perder su propio historial de decisión por una acción de un tercero — el mismo principio que ya rige el resto del sistema (BR-JOB-09: eliminar una vacante nunca borra el historial de postulaciones que generó).

Además, la identidad real (email, contraseña, redes sociales vinculadas) ya no vive en JobSeeker — vive en Clavaris (ADR-0008 extendido por la conversación de diseño de Clavaris) — por lo que "borrar los datos" implica dos sistemas, no uno.

## Decisión

**Periodo de gracia + anonimización, nunca borrado físico inmediato, con cascada explícita a Clavaris:**

1. Al solicitar la eliminación, la cuenta entra en estado suspendido por `accountDeletionGracePeriodDays` (configurable, no hardcodeado — por defecto 30 días). Volver a iniciar sesión durante ese periodo cancela la solicitud sin pérdida de datos.
2. Vencido el periodo sin cancelación, un proceso programado (mismo patrón ya establecido para `job-module`, `system-design-document.md` §4.6) **anonimiza** — no borra físicamente — los campos de PII directa: nombre, contacto, texto de CV, foto, y regenera/descarta el embedding derivado de esos datos.
3. Los registros que dependen de la cuenta (`Application`, `CompatibilityScore`, historial de pipeline) **se conservan**, referenciando el perfil ya anonimizado — preserva la integridad del historial del reclutador (BR-DATA-05).
4. El mismo proceso llama a la API de administración de Clavaris para eliminar la identidad real (email, credencial, identidades sociales) — sin esto, el borrado en JobSeeker sería cosmético: la PII más sensible (email, forma de autenticarse) seguiría existiendo en el proveedor de identidad.

## Alternativas consideradas

| Alternativa | Por qué se rechazó |
|---|---|
| Borrado físico inmediato y en cascada (incluyendo `applications`/`compatibility_scores`) | Rompe el historial del reclutador de forma retroactiva por una acción que no depende de él — inconsistente con BR-JOB-09, y further un reclutador podría perder evidencia de un proceso de contratación en curso o reciente sin aviso |
| Retención indefinida sin política de borrado | No satisface BR-DATA-02 en ningún plazo definido — un titular que pide el borrado de sus datos no puede quedar esperando indefinidamente |
| Borrado inmediato sin periodo de gracia (anonimizar al instante de la solicitud) | Sin ventana de arrepentimiento, un borrado accidental o una cuenta comprometida que un atacante marca para eliminación como sabotaje es irreversible de inmediato |
| Anonimizar solo en JobSeeker, sin tocar Clavaris | Deja la PII más sensible (email, mecanismo de login) intacta en el proveedor de identidad — un borrado que no cubre todos los sistemas no es un borrado real |

## Consecuencias

**Positivas**

- Resuelve la pregunta abierta de `security-architecture.md` §10 con una decisión consistente con el resto del sistema (nunca perder historial silenciosamente, BR-JOB-09/BR-MATCH-04), no una excepción ad-hoc.
- El periodo de gracia es una salvaguarda de UX real (recuperación de borrados accidentales) sin comprometer el derecho del titular — solo lo difiere de forma razonable y visible.
- La cascada a Clavaris hace el borrado genuino, no cosmético.

**Negativas / trade-offs aceptados**

- Requiere un nuevo proceso programado (mismo patrón que los batches de `job-module`) y una integración saliente hacia Clavaris — superficie operativa adicional.
- "Anonimizado" debe definirse con precisión técnica (qué constituye PII directa vs. datos derivados que pueden quedar) para no dejar reidentificación posible por combinación de campos residuales — pendiente de especificación exacta a nivel de columna (ver Preguntas abiertas).

## Preguntas abiertas

- ¿`accountDeletionGracePeriodDays` = 30 días es el valor correcto para esta jurisdicción/producto, o debe calibrarse?
- ¿Qué pasa si la cuenta tiene ambas capacidades (candidato y reclutador, ADR-0008) y solo se pide eliminar una? ¿Se permite eliminar una capacidad sin eliminar la cuenta completa, dado que comparten `ProfessionalProfile`?
- Especificación exacta columna por columna de qué se anonimiza (`data-model.md` §4.19).
- ¿El candidato puede solicitar una copia de sus datos antes de pedir la eliminación (portabilidad), o queda fuera del alcance del MVP?
- **Importante, pendiente de revisar cuando Clavaris tenga más de un producto cliente:** este ADR asume que la identidad en Clavaris es 1:1 con la cuenta de JobSeeker, por lo que "eliminar mi cuenta de JobSeeker" puede cascadear a "eliminar mi identidad en Clavaris" sin ambigüedad. Si en el futuro la misma identidad de Clavaris se usa para iniciar sesión en JobSeeker *y* en otro producto (SSO real entre productos propios, no solo reutilización del software), esta cascada deja de ser correcta — eliminaría el acceso a productos que la persona nunca pidió abandonar. En ese escenario, la acción correcta sería "desvincular JobSeeker de mi identidad" (revocar solo los tokens/consentimiento de este cliente OAuth), no eliminar la cuenta en Clavaris. No se resuelve aquí porque hoy JobSeeker es el único cliente de Clavaris.
- ¿La cascada hacia Clavaris (BR-DATA-06) es una llamada síncrona dentro del mismo batch, o asíncrona con reintento si Clavaris no responde?
