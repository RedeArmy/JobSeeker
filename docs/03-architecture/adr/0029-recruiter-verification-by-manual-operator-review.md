# ADR-0029: Verificación de reclutadores por revisión manual de operadores
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | ✅ Aprobado — 25-nov-2026, por el responsable del proyecto |
| **Versión** | 1.0 |
| **Fecha** | Noviembre 2026 |
| **Documento relacionado** | BR-ACCT-10, BR-ACCT-11, BR-JOB-22, REQ-R-10, REQ-R-11, amenaza T-N1, ADR-0022 (por escribir) |

---

## Contexto

Ver candidatos y publicar más vacantes depende de la confianza en la cuenta reclutadora (REQ-R-10): sin ella, cualquiera podría registrarse y recorrer la base de candidatos (amenaza T-N1). Hace falta decidir **quién** concede esa confianza. La verificación por correo corporativo exige un proveedor de correo que aún no existe (ADR-0022).

## Decisión

La verificación es **manual: la decide un operador de la plataforma**, como quedó construido en el PR #41.

1. Una cuenta pasa a `VERIFIED`, `SUSPENDED` o vuelve a `UNVERIFIED` solo por decisión de un operador (BR-ACCT-11), nunca por sí misma.
2. Ser operador es una lista de configuración (`jobseeker.auth.operators.account-ids`, vacía por defecto), no una capacidad que una cuenta pueda concederse. El canal responde `404` a quien no es operador y un operador no decide sobre sí mismo.
3. Cada cambio queda en `audit_log` en la misma transacción, sin datos personales.
4. **Sin `VERIFIED` no hay sugeridos** (REQ-R-10): `suggestcandidatesforjob` niega con `403 RECRUITER_NOT_VERIFIED` o `RECRUITER_SUSPENDED` antes de tocar la vacante, y cuenta los perfiles únicos mostrados contra una cuota por ventana de 24 h (REQ-R-11, TD-R27).

## Alternativas descartadas

| Alternativa | Por qué no ahora |
|---|---|
| Correo corporativo verificado | Necesita proveedor de correo (ADR-0022) y reglas contra dominios gratuitos; queda como vía **automática posterior** que no sustituye la decisión del operador sobre suspender |
| Organizaciones de Clavaris | Mezcla un concepto del proveedor de identidad con una bandera de negocio de JobSeeker (BR-ACCT-01 a BR-ACCT-05) |
| Un rol en la base de datos | Obliga a conceder privilegios por SQL, sin auditoría ni canal |

## Consecuencias

- Con pocos reclutadores la revisión manual alcanza y es el control más fuerte; a escala será un cuello de botella y habrá que añadir la vía automática con su propio ADR.
- Una cuenta nueva solo puede publicar hasta el tope de `UNVERIFIED` (BR-JOB-22) y no ve sugeridos hasta que un operador la verifique.
- Un operador comprometido es un riesgo que acota la auditoría y la lista de configuración; no hay un autoservicio que explotar.
