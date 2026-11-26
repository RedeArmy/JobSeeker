# ADR-0031: Persistencia y recálculo del puntaje: desvíos del ADR-0017
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | ✅ Aprobado — 25-nov-2026, a solicitud del responsable del proyecto, tras la revisión técnica de la sección final (asistida por IA, mismo mecanismo que los ADR-0017, ADR-0019 y ADR-0030) |
| **Versión** | 1.0 |
| **Fecha** | Noviembre 2026 |
| **Documento relacionado** | ADR-0017 (**modifica** su decisión 8 y aclara las 9 y 10), ADR-0016, ADR-0030, `12-lectura-por-lotes-y-persistencia-del-puntaje.md` |

---

## Contexto

Al implementar la persistencia del puntaje (TD-036) la decisión 8 del ADR-0017 resultó **inaplicable tal como está escrita**: propone una caché en Redis (`score:v1:{huella}`) cuya huella combina el *hash* del perfil canónico y el de la vacante canónica. Esos textos **excluyen** los datos estructurados (país, modalidad, jornada, reubicación, salario y autorizaciones de trabajo), que sí cambian el puntaje (las habilidades con su nivel sí entran al texto): cambiar el salario no cambiaría la huella y se serviría un puntaje viejo, contra BR-MATCH-03.

## Decisión

1. **No hay caché de puntajes en Redis.** La única fuente es la tabla `compatibility_scores_current` en PostgreSQL. Una huella correcta tendría que incluir las señales estructurales, y obtenerlas es justo el costo que la caché evitaría; lo demás (pasar de señales y un coseno a un porcentaje) es aritmética de milisegundos. Redis sigue siendo solo de límites de tasa y sesiones. No hay invalidación que olvidar.
2. **El recálculo corre dentro del trabajador de la cola** (ADR-0017, decisión 11), tras procesar cada perfil o vacante, sobre los pares **ya guardados** y con un tope por entidad. Si falla, el trabajo se reintenta con el retroceso existente. La métrica de frescura pasa a medir «edición → puntaje actualizado», que es el objetivo del ADR-0017 (decisión 10).
3. **Solo pares con motivo** (decisión 9, sin cambio): `SUGGESTED` (lo vio un reclutador), `CANDIDATE_VIEW` (lo consultó el candidato) y `APPLIED`. El motivo más fuerte se conserva.
4. **El puntaje del candidato se calcula al pedirlo** sobre lo que hay hoy: nunca es viejo. La copia guardada sirve a `ats` y a la auditoría (BR-MATCH-04).
5. **Anonimización:** se vacía lo derivado del perfil (desglose y texto canónico) y se **conservan** el par, el puntaje y la versión (BR-DATA-05; decisión 9).

| Alternativa | Por qué no |
|---|---|
| Caché Redis con huella de los *hashes* canónicos (la del ADR-0017) | Huella ciega a los datos estructurales: puntajes viejos |
| Caché Redis con huella de las señales | Exige leerlas, que es lo caro; ahorra solo aritmética |
| Recalcular con un trabajo aparte en la cola | Más tipos de entidad y otro retroceso, sin ganar nada |

## Consecuencias

- **Positivas:** un solo almacén, sin invalidación; el puntaje del candidato siempre vigente; la frescura queda medida de extremo a extremo.
- **Costo asumido y medido:** cada petición del candidato calcula el puntaje (lectura de señales más aritmética y guardado): **p50 42 ms y p95 75 ms** en esta VM, petición completa con la cadena de seguridad, frente al objetivo de ≤ 2 s (NFR-PERF-01). Si una medición con tráfico real lo contradijera, la caché se reintroduce con una huella **correcta**.
- **El ADR-0017 queda enmendado** en su decisión 8 (ver su cabecera y la nota bajo la decisión).

## Revisión técnica (25-nov-2026)

Revisión con el rol de SDE III senior, asistida por IA y a petición del responsable del proyecto. **Limitación:** quien revisa es quien redactó el ADR y el código; no sustituye la revisión de una persona ajena al proyecto, deseable para una tesis (igual que en los ADR-0017 y ADR-0030).

| # | Criterio | Resultado |
|---|---|---|
| A1 | La premisa es verificable: la huella del ADR-0017 es ciega a los datos estructurales | ✅ Verificado en `CanonicalProfileComposer`: país, modalidad, jornada, reubicación, salario y autorizaciones no entran al texto |
| A2 | Compara alternativas con su motivo de descarte | ✅ Tres alternativas |
| A3 | Lo decidido está implementado y probado | ✅ Recálculo dentro del trabajador (pruebas de la cola), motivo más fuerte, anonimización y e2e de punta a punta |
| A4 | El costo asumido está medido, no supuesto | ✅ p50 42 ms, p95 75 ms (hallazgo R2) |
| A5 | No contradice ADR aprobados salvo el que enmienda | ✅ Solo la decisión 8 del ADR-0017, que era inaplicable; las decisiones 9 y 10 se cumplen |
| A6 | Sin dependencias nuevas ni excepciones a las reglas | ✅ Reduce una pieza de infraestructura (no usa Redis para puntajes) |

| # | Hallazgo | Gravedad | Tratamiento |
|---|---|---|---|
| R1 | El ADR enumeraba mal lo que el texto canónico excluye: decía «nivel de cada habilidad», pero las habilidades **con su nivel sí entran** al texto | Media | Corregido: la lista exacta es país, modalidad, jornada, reubicación, salario y autorizaciones de trabajo. El argumento se sostiene (cambiar el salario no cambia la huella) |
| R2 | «Decenas de milisegundos» era una afirmación sin medir | Media | Medido: p50 42 ms, p95 75 ms; añadido arriba |
| R3 | Sin caché, una ráfaga de peticiones del mismo candidato recalcula cada vez | Baja | Aceptado: el límite de tasa de lectura (120/min por cuenta) acota el costo y cada cálculo cuesta decenas de milisegundos |
| R4 | El puntaje del candidato calculado al pedirlo no se compara con el guardado, que puede quedar atrás hasta el siguiente recálculo | Baja | Aceptado: la fuente de verdad para el candidato es la consulta; el guardado es para `ats` y auditoría, y la migración y el recálculo lo ponen al día |

**Veredicto: aprobado.** La dirección es correcta y está implementada, probada y medida; los hallazgos eran de precisión y de evidencia, ya corregidos.
