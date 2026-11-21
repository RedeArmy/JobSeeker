# Acceptance Criteria
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | 🟡 En revisión |
| **Versión** | 1.0 |
| **Fecha** | Agosto 2026 |
| **Documentos relacionados** | Definition of Done, Business Rules, NFR & Quality Attributes |

---

## 1. Propósito del documento

Fijar el formato estándar de criterios de aceptación por feature/historia de usuario, y proveer un ejemplo completo resuelto para la funcionalidad núcleo del sistema (cálculo de score de compatibilidad), como referencia para escribir los criterios de las demás features durante la implementación.

## 2. Formato estándar

Cada historia de usuario documenta sus criterios de aceptación en formato **Given/When/Then** (Gherkin), uno por escenario relevante, incluyendo explícitamente:

- El **ID de regla de negocio** que el escenario valida, cuando aplica (BR-XXX-NN).
- El **ID de NFR** relevante, cuando el criterio es de calidad y no solo funcional (NFR-XXX-NN).
- Escenarios de **camino feliz** y de **casos límite/error**, no solo el flujo exitoso.

```
Historia: <descripción breve en formato "Como <rol>, quiero <acción>, para <beneficio>">

Escenario: <nombre del escenario> (<BR-XXX-NN o NFR-XXX-NN si aplica>)
  Dado <precondición>
  Cuando <acción>
  Entonces <resultado esperado>
```

## 3. Ejemplo resuelto: cálculo de score de compatibilidad

**Historia:** Como candidato, quiero ver mi score de compatibilidad al consultar una vacante, para decidir si me conviene postular.

### Escenario 1 — Camino feliz, cache miss (BR-MATCH-01, BR-MATCH-02)

```
Dado que tengo un perfil con al menos nombre, contacto y CV cargado (BR-CAND-01)
Y la vacante "Desarrollador Backend Java" tiene título, descripción y requisitos estructurados (BR-JOB-02)
Y no existe un score cacheado para este par candidato-vacante
Cuando consulto la vacante "Desarrollador Backend Java"
Entonces el sistema calcula un score combinando la capa semántica y la capa estructurada ponderada (BR-MATCH-01)
Y el score se muestra en un tiempo ≤ 5 segundos (NFR-PERF-01, sin cache)
Y veo un desglose por dimensión (habilidades, experiencia, formación, ubicación, salario) (BR-MATCH-02)
Y el score queda registrado con su versión de algoritmo vigente (BR-MATCH-04)
```

### Escenario 2 — Camino feliz, cache hit (NFR-PERF-01)

```
Dado que ya existe un score cacheado en Redis para este par candidato-vacante
Cuando consulto nuevamente la misma vacante sin haber modificado mi perfil
Entonces el score se muestra en un tiempo ≤ 2 segundos (NFR-PERF-01, con cache)
Y el desglose mostrado es idéntico al calculado previamente
```

### Escenario 3 — Recalculo automático ante cambio de perfil (BR-MATCH-03)

```
Dado que ya consulté previamente el score de una vacante
Cuando actualizo mi perfil (por ejemplo, agrego una nueva habilidad)
Y vuelvo a consultar la misma vacante
Entonces el sistema recalcula el score automáticamente, sin que yo deba solicitarlo explícitamente (BR-MATCH-03)
Y el score mostrado refleja los datos actualizados de mi perfil
```

### Escenario 4 — Perfil incompleto (BR-CAND-01, caso de error)

```
Dado que mi perfil no tiene un CV cargado
Cuando intento postular a una vacante
Entonces el sistema rechaza la postulación
Y me indica qué dato mínimo falta (BR-CAND-01), sin exponer detalles técnicos internos (formato de error de api-contract-overview.md §6)
```

### Escenario 5 — Visibilidad asimétrica del score (BR-VIS-01)

```
Dado que soy candidato y nunca he consultado ni postulado a la vacante "Analista de Datos"
Cuando intento acceder directamente al score de esa vacante (por ejemplo, manipulando la URL)
Entonces el sistema deniega el acceso, aunque mi perfil exista y sea válido (BR-VIS-01)
```

### Escenario 6 — Aislamiento entre reclutadores (BR-VIS-03)

```
Dado que soy un reclutador y no soy dueño de la vacante "Analista de Datos" publicada por otro reclutador
Cuando intento acceder al score de un candidato que postuló a esa vacante
Entonces el sistema deniega el acceso a nivel de autorización, no solo ocultando el elemento en la interfaz (NFR-SEC-03, BR-VIS-03)
```

### Escenario 7 — Sugerencia de candidato sin postulación (BR-MATCH-06, BR-VIS-04)

```
Dado que soy reclutador y publiqué la vacante "Desarrollador Backend Java"
Y existe un candidato con perfil descubierto (discoverable = true) que nunca postuló ni consultó esa vacante
Y el score de compatibilidad calculado entre ese candidato y la vacante es alto
Cuando abro la vista de candidatos sugeridos de mi vacante
Entonces veo a ese candidato listado como sugerido (BR-MATCH-06)
Y puedo ver su score y desglose completo por dimensión, igual que si hubiera postulado (BR-VIS-04)
Y el listado se renderiza en ≤ 3 segundos (NFR-PERF-04)
```

### Escenario 8 — Candidato que desactivó su descubribilidad (BR-DATA-03, caso de error/exclusión)

```
Dado que un candidato tiene un score de compatibilidad alto con mi vacante
Pero ese candidato desactivó la descubribilidad de su perfil (discoverable = false)
Y ese candidato no ha postulado a mi vacante
Cuando abro la vista de candidatos sugeridos de mi vacante
Entonces ese candidato NO aparece en el listado de sugeridos (BR-DATA-03)
```

### Escenario 9 — Priorización por "Open to Work" sin alterar el score (BR-CAND-04, BR-MATCH-07)

```
Dado que dos candidatos, A y B, tienen el mismo score de compatibilidad (78%) con mi vacante
Y ninguno de los dos ha postulado
Y el candidato A declaró su estado como "Open to Work" y el candidato B no declaró estado (UNSET)
Cuando abro la vista de candidatos sugeridos de mi vacante
Entonces el candidato A aparece antes que el candidato B en el listado (BR-MATCH-07)
Y el score mostrado para ambos sigue siendo 78% — el estado de disponibilidad no modificó el cálculo (BR-MATCH-01)
```

### Escenario 10 — Vacante en "Analizando CV" pausa nuevas sugerencias (BR-JOB-04, BR-MATCH-08)

```
Dado que mi vacante "Desarrollador Backend Java" está en etapa "Contratando" (HIRING)
Y cambio su etapa a "Analizando CV" (REVIEWING)
Cuando un nuevo candidato compatible (que no había sido sugerido antes) actualiza su perfil
Entonces ese candidato NO aparece como nueva sugerencia para esta vacante mientras esté en REVIEWING (BR-MATCH-08)
Y los candidatos que ya postularon siguen visibles y gestionables en el pipeline sin cambios (BR-VIS-02)
```

### Escenario 11 — Candidato oculta su estado a una cuenta reclutadora específica (BR-CAND-05, BR-VIS-06)

```
Dado que soy candidato con estado "Open to Work"
Y agrego a la cuenta del reclutador "Empresa X" a mi lista de exclusión de disponibilidad
Cuando el reclutador de "Empresa X" consulta mi perfil, ya sea porque postulé o porque aparezco como sugerido
Entonces ve mi score y desglose con normalidad
Pero NO ve mi estado "Open to Work" (BR-VIS-06) — se muestra como si no hubiera declarado estado
Y ningún otro reclutador que no esté en mi lista de exclusión ve afectada su vista de mi estado
```

### Escenario 11b — Candidato agrega otra cuenta candidato a su lista de exclusión (BR-CAND-05, límite de alcance conocido)

```
Dado que soy candidato con estado "Open to Work"
Y agrego la cuenta de otro candidato, "Juan Pérez" (un excompañero de trabajo), a mi lista de exclusión de disponibilidad
Entonces la exclusión se guarda correctamente (BR-CAND-05 no restringe por rol de la cuenta agregada)
Pero, dado que hoy no existe ningún caso de uso donde un candidato consulte el perfil de otro candidato, esta exclusión no tiene un canal visible donde surta efecto — queda registrada para el momento en que exista tal canal, o si "Juan Pérez" pasa a operar también como reclutador
```

### Escenario 12 — Postular a un reclutador excluido sigue funcionando (BR-CAND-06, caso crítico de no regresión)

```
Dado que tengo a la cuenta del reclutador "Empresa X" en mi lista de exclusión de disponibilidad
Cuando postulo a una vacante publicada por "Empresa X"
Entonces mi postulación se registra con normalidad (BR-TRACK-01/02 sin cambios)
Y el reclutador de "Empresa X" ve mi score y desglose completo como cualquier otro postulante (BR-VIS-02)
Y mi estado de disponibilidad permanece oculto solo para "Empresa X" (BR-VIS-06), sin afectar el resultado de la postulación
```

### Escenario 13 — La exclusión también suprime el boost de ranking, no solo el badge (BR-MATCH-09)

```
Dado que tengo estado "Open to Work" y a la cuenta del reclutador "Empresa X" en mi lista de exclusión
Y no he postulado a la vacante de "Empresa X"
Cuando el reclutador de "Empresa X" abre la vista de candidatos sugeridos
Entonces mi posición en el listado es la misma que tendría con estado UNSET (BR-MATCH-09) — no recibo el boost de prioridad de BR-MATCH-07 frente a ese reclutador específico
```

## 3.1 Alcance de este ejemplo frente al resto del sistema

El ejemplo resuelto de la sección 3 cubre únicamente el cálculo de score (BR-MATCH, BR-VIS, BR-CAND-04/05, BR-JOB-04). El resto de grupos de reglas ya definidas en `business-rules.md` — elegibilidad e invitación (BR-ELIG-01 a 13), ciclo de vida y temporizador de vacante (BR-JOB-03, 06 a 20), preguntas de postulación y follow-up (BR-APPQ, BR-FUP) y cuenta multi-rol (BR-ACCT-01 a 08) — no tienen todavía su propio conjunto de escenarios en este documento; se escriben siguiendo el mismo formato (sección 2) cuando se implemente cada historia correspondiente, no se anticipan aquí para no duplicar el detalle ya cubierto por las justificaciones de `business-rules.md`.

## 4. Cuándo un criterio de aceptación implica una regla de negocio nueva

Si al escribir un escenario surge una regla que no tiene ID en `business-rules.md`, no se inventa el ID silenciosamente: se documenta el escenario igual, marcando explícitamente `(regla nueva propuesta, pendiente de agregar a business-rules.md)` en vez de asignarle un `BR-XXX-NN` no ratificado, y se reporta como pregunta abierta en el PR correspondiente (ver `definition-of-done.md` criterio #11).

## 5. Preguntas abiertas

- ¿Los criterios de aceptación se mantienen en este documento a modo de ejemplo únicamente, o se espera que cada historia de usuario tenga su propio archivo/sección dentro del tablero de gestión del proyecto (fuera de este repositorio)?
