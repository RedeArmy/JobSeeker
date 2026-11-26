# ADR-0030: Prioridad por disponibilidad, completitud aplicable y puntaje preliminar en los sugeridos
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | ✅ Aprobado — 25-nov-2026, a solicitud del responsable del proyecto, tras la revisión técnica de la sección final (asistida por IA, mismo mecanismo que los ADR-0017 y ADR-0019) |
| **Versión** | 1.0 |
| **Fecha** | Noviembre 2026 |
| **Documento relacionado** | ADR-0017 (**aclara** sus decisiones 3, 5 y 6; no las contradice), BR-MATCH-01 a 09, BR-CAND-06, BR-VIS-06, `tdd/matching-module.md` §10.2, TD-037 |

---

## Contexto

Al implementar las etapas 2 y 3 de los sugeridos (TD-034) aparecieron tres puntos que el ADR-0017 deja abiertos o formula de un modo que no se puede ejecutar tal cual:

1. **BR-MATCH-07 dice que `OPEN_TO_WORK` «se prioriza»; el ADR-0017 dice «desempate».** Con puntajes continuos un desempate exacto casi nunca ocurre, así que la prioridad no tendría efecto, y un impulso fuerte lo tendría de más.
2. **La completitud del ADR-0017 (decisión 6) no dice sobre qué se mide.** Medida sobre las seis dimensiones, una vacante que pide pocas cosas daría completitud baja a todos y no sugeriría a nadie.
3. **El puntaje no puede calibrarse todavía**: el protocolo de 300 pares necesita datos reales que no existen. Hay que decidir qué se publica mientras tanto.

## Decisión

### 1. `OPEN_TO_WORK` es un desempate con tolerancia continua

Se ordena por puntaje y un candidato `OPEN_TO_WORK` **adelanta a quien le saca menos de `t` puntos** (a diferencia exactamente `t` gana el de mayor puntaje) (`jobseeker.matching.suggestions.tie-tolerance`, por defecto **1**, de 0 a 10). Con 1 es la resolución del porcentaje mostrado: dos candidatos que el reclutador ve con el mismo porcentaje se ordenan por disponibilidad. Un 40 % `OPEN_TO_WORK` nunca supera a un 90 %. El puntaje **nunca** cambia (BR-MATCH-07); la disponibilidad es la que ve ese reclutador, `UNSET` si fue excluido (BR-MATCH-09, BR-VIS-06).

| Alternativa | Por qué no |
|---|---|
| Prioridad estricta (todos los `OPEN_TO_WORK` primero) | Pone un ajuste bajo por encima de uno alto: hunde P@K, la métrica de éxito, y convierte el estado en la señal dominante del ranking |
| Bandas fijas (p. ej. de 5 puntos) | Los cortes crean saltos: 77,4 y 77,6 quedan en bandas distintas y la prioridad aparece o desaparece por décimas. Una tolerancia es transitiva y sin saltos (probado) |
| Sin prioridad | Contradice BR-MATCH-07 |

La tolerancia es configuración y se calibra con datos reales; **0 la desactiva**. Subir de 1 es una decisión de producto, no un cambio de regla.

### 2. La completitud se mide sobre el peso *aplicable*

`completitud = peso de las dimensiones que el candidato declaró ÷ peso de las dimensiones que la vacante define`. Lo que la vacante no pide (`NOT_APPLICABLE`) no cuenta ni arriba ni abajo, porque no es culpa del candidato; lo que sí pide y el candidato no declaró (`NOT_DECLARED`) la baja. El mínimo de 0,5 sigue siendo el de las listas. Si la vacante no define nada medible, la completitud es 1 y el puntaje es solo semántico (con `α = 1`, dicho en el desglose).

| Alternativa | Por qué no |
|---|---|
| Sobre las seis dimensiones (lectura literal) | Una vacante con dos requisitos daría completitud ≤ 0,4 a todos: no sugeriría a nadie |
| Piso de dimensiones por vacante | Complica el contrato sin resolver el caso; la regla aplicable ya lo cubre |

### 3. Hasta calibrar, el puntaje es **preliminar** y se dice

No se fabrica una calibración con etiquetas sintéticas (el piloto del doc 08 es sintético: ajustar sobre él daría un «calibrado» que no lo es). Mientras no existan los parámetros de Platt del protocolo de 300 pares:

- la respuesta lleva `calibrated: false` y la versión del algoritmo, y el coseno se acota a [0, 1] (monótono: el **orden** es válido aunque el valor absoluto no sea una probabilidad);
- `jobseeker_matching_scoring_calibrated{algorithm_version}` vale 0 y arranca un aviso en el registro, para que producción sin calibrar no pase inadvertida y se pueda alertar;
- el puntaje **no** se usa para medir la métrica de precisión (ADR-0017, decisión 5) y la interfaz debe presentarlo como estimación preliminar, no como una probabilidad de éxito;
- el uso queda limitado a un **piloto cerrado** (una cohorte acotada y avisada de que el porcentaje es preliminar) hasta que `calibrated` sea `true`; abrir los sugeridos a usuarios generales exige TD-037. Es más estricto que el ADR-0017, que solo pedía marcarlo, y se adopta porque ahí el porcentaje se lee como una promesa.

| Alternativa | Por qué no |
|---|---|
| Calibrar con el piloto sintético | Etiquetas que no son del dominio: falsa precisión |
| Ocultar el valor y mostrar solo el orden | BR-MATCH-02 exige desglose y el valor orienta; el orden sin explicación sería una caja negra |
| Bloquear los sugeridos hasta calibrar | Bloquea el piloto; ordenar por una función monótona del coseno y de lo estructurado ya es mejor que no tener nada |

## Consecuencias

- **Positivas:** la prioridad tiene efecto sin romper la precisión ni la explicabilidad; las listas funcionan con vacantes de pocos requisitos; y la falta de calibración es visible en la respuesta, en una métrica y en el registro.
- **Costo asumido:** hasta calibrar, el porcentaje absoluto no debe leerse como probabilidad; y 1 punto de tolerancia puede adelantar a quien tiene hasta ~1 punto menos, por diseño.
- **Pendiente:** TD-037 (calibración, controles C1, C2 y C5). La tolerancia y el factor de obligatorio se afinan con el mismo conjunto etiquetado.

## Revisión técnica (25-nov-2026)

Revisión con el rol de SDE III senior, asistida por IA y a petición del responsable del proyecto. **Limitación:** quien revisa es quien redactó el ADR y el código; no sustituye la revisión de una persona ajena al proyecto, que para una tesis es deseable (igual que en el ADR-0017).

**Criterios de aprobación y resultado**

| # | Criterio | Resultado |
|---|---|---|
| A1 | No contradice un ADR aprobado ni una regla de negocio: aclara el ADR-0017 (decisiones 3, 5, 6) y BR-MATCH-07 | ✅ No cambia ninguna decisión previa; BR-MATCH-07 recibe una aclaración de redacción |
| A2 | Cada decisión compara alternativas con su motivo de descarte | ✅ Tres tablas; las bandas fijas se descartaron por un defecto encontrado al probarlas |
| A3 | Lo decidido está implementado como se describe y probado | ✅ Tolerancia (límites, transitividad, 0 la desactiva, llega desde la política), completitud aplicable (propiedades C3 y casos de borde), indicador y aviso de calibración, y de punta a punta con la exclusión |
| A4 | Es configurable donde la decisión se calibrará con datos | ✅ `tie-tolerance` (0 a 10), pesos, `α`, completitud mínima y calibración, todos por configuración y dentro de `algorithm_version` salvo la tolerancia, que solo ordena |
| A5 | Las consecuencias, los costos y lo pendiente están dichos | ✅ El porcentaje absoluto no es una probabilidad hasta calibrar; la tolerancia adelanta hasta casi un punto, por diseño; TD-037 |
| A6 | No introduce dependencias ni excepciones a las reglas del proyecto | ✅ Sin dependencias nuevas; ARCH-04 respetado (se corrigió que `cv` exponía un enum de dominio) |

**Hallazgos de la revisión**

| # | Hallazgo | Gravedad | Tratamiento |
|---|---|---|---|
| R1 | El texto decía «hasta `t` puntos», pero con diferencia exactamente `t` las claves empatan y gana el de mayor puntaje: la regla es «menos de `t`» | Baja | Corregido aquí, en BR-MATCH-07, en el TDD y en el código; añadida una prueba del borde |
| R2 | «Piloto cerrado» era más fuerte que el ADR-0017 sin decirlo | Media | Precisado: es una restricción adoptada a propósito y con su motivo |
| R3 | Con la tolerancia máxima (10) la prioridad deja de ser un desempate en la práctica | Baja | Aceptado: el tope de 10 está documentado y subir de 1 es una decisión de producto con datos |
| R4 | La tolerancia no forma parte de `algorithm_version`, pero cambia el orden | Baja | Aceptado: no altera ningún puntaje; si se quisiera auditar el orden, habría que registrarla con la foto del puntaje (TD-036) |

**Veredicto: aprobado.** La dirección es correcta, está implementada y probada, y los hallazgos eran de redacción o de precisión, ya corregidos.
