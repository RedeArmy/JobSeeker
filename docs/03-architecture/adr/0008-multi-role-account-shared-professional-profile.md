# ADR-0008: Cuenta multi-rol y núcleo de perfil profesional compartido entre candidato y reclutador
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | 🟡 En revisión |
| **Versión** | 1.0 |
| **Fecha** | Agosto 2026 |
| **Documento relacionado** | Business Rules (BR-ACCT-01 a 07), Domain Model, Data Model |

---

## Contexto

El sistema modelaba `accounts.role` como un valor único (`CANDIDATE` o `RECRUITER`, BR-JOB-01). El negocio requiere que una misma persona pueda tener ambas capacidades: un reclutador puede, en algún momento, quedarse sin trabajo o querer cambiar de puesto, y necesita poder postular como candidato sin crear una segunda cuenta ni perder su identidad profesional (experiencia, formación, certificaciones, proyectos) ya cargada.

Adicionalmente, tanto el perfil de candidato como el nuevo perfil de reclutador comparten un núcleo estructural evidente — experiencia laboral, formación académica, certificaciones, proyectos — mientras que difieren en los datos orientados a búsqueda de empleo (skills para matching, expectativa salarial, disposición a reubicarse, elegibilidad) que solo tienen sentido para el candidato.

## Decisión

1. **Cuenta multi-rol**: `accounts` deja de tener un `role` único y pasa a tener dos capacidades independientes y no excluyentes, `is_candidate_enabled` e `is_recruiter_enabled` (al menos una debe estar activa). Habilitar una capacidad no depende de ni afecta a la otra.

2. **`ProfessionalProfile` compartido**: se extrae un agregado `ProfessionalProfile`, 1:1 con `Account`, que contiene el núcleo de identidad profesional — nombre, titular (headline), biografía, foto, experiencia laboral, formación académica, certificaciones y proyectos (laborales o personales). Se crea automáticamente la primera vez que la cuenta habilita cualquiera de sus dos capacidades, y se **reutiliza** — nunca se duplica — al habilitar la segunda.

3. **`CandidateProfile` y `RecruiterProfile`** se convierten en agregados más delgados, cada uno construido sobre el `ProfessionalProfile` de la cuenta, y añaden únicamente los datos propios de su rol (ver BR-ACCT-03).

## Alternativas consideradas

| Alternativa | Por qué se rechazó |
|---|---|
| Rol único pero cambiable (switch candidato ↔ reclutador) | No permite actuar como ambos simultáneamente (p. ej. seguir con vacantes publicadas mientras se busca empleo); el caso de negocio descrito (reclutador que pierde su puesto) puede necesitar ambas cosas a la vez |
| Cuentas completamente separadas por rol | Obliga a crear una segunda identidad para la misma persona, duplicando credenciales y — más grave — duplicando manualmente experiencia, formación, certificaciones y proyectos ya cargados; contradice directamente el pedido de negocio |
| Perfiles totalmente independientes y duplicados (sin `ProfessionalProfile` compartido), cada uno con su propia copia de experiencia/formación | Fuerza a la persona a mantener dos historiales profesionales sincronizados manualmente; cualquier actualización de una experiencia laboral tendría que repetirse en ambos perfiles, con alto riesgo de inconsistencia |

## Consecuencias

**Positivas**

- Una persona con ambas capacidades carga su historial profesional una sola vez; editarlo actualiza automáticamente lo que ve cualquier capacidad de la cuenta (BR-ACCT-07).
- `RecruiterProfile` permanece deliberadamente ligero (sin datos de búsqueda de empleo), cumpliendo el pedido explícito del negocio de un perfil "más profesional, no tanta información".
- El aislamiento de comportamiento (BR-ACCT-04) evita que la doble capacidad otorgue ventajas indebidas — quien recluta y también es candidato se evalúa exactamente igual que cualquier otro candidato.

**Negativas / trade-offs aceptados**

- Cambio de esquema no trivial: `accounts.role` (columna única) se reemplaza por dos booleanos, y `full_name`/experiencia/formación migran de `candidate_profiles` a la nueva tabla `professional_profiles`. Aceptable porque el proyecto está en fase de documentación, antes de implementación (las reglas del proyecto §11) — este es el momento de menor costo para el cambio.
- El diseño de claims de JWT (`security-architecture.md`) debe revisarse para representar un conjunto de roles por cuenta en vez de un valor único — pendiente como pregunta abierta, no resuelto en este ADR.
- Toda regla de negocio que asumía "el reclutador" y "el candidato" como identidades mutuamente excluyentes requiere una relectura para confirmar que sigue siendo correcta bajo capacidades no excluyentes (BR-ACCT-04 formaliza esto como invariante general, en vez de revisar rule por rule).

## Preguntas abiertas

- ¿Cómo representa el JWT las capacidades de una cuenta multi-rol — un claim de array de roles, o un token distinto por "rol activo" que el usuario selecciona al iniciar sesión?
- ¿El embedding de `candidate_profiles` (usado por `matching-module`) se recalcula a partir de `raw_cv_text` (archivo cargado) o de los datos estructurados de `ProfessionalProfile` (experiencia, formación, certificaciones, proyectos), ahora que este último es la fuente compartida del historial profesional?
