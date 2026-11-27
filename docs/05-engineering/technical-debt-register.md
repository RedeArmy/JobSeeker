# Registro de Deuda Técnica
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | 🟡 En revisión |
| **Versión** | 1.21 — se pagan TD-015, TD-035, TD-042, TD-043 y TD-044 |
| **Fecha** | 27 de noviembre de 2026 |
| **Dueño del documento** | Responsable del proyecto |
| **Revisión del registro** | Al cerrar cada sprint y antes de cada hito de la hoja de ruta |
| **Documentos relacionados** | `docs/05-engineering/definition-of-done.md`, `docs/05-engineering/test-strategy.md`, `docs/05-engineering/coding-standards.md`, `docs/01-product/roadmap-and-release-plan.md` |

---

## 1. Propósito

Llevar en un solo lugar la deuda técnica **conocida**: qué es, qué evidencia la sostiene, cuánto cuesta no pagarla, cuándo se vuelve urgente y si ya se arregló. Una deuda que no está escrita no se puede priorizar ni cerrar, y una que se cerró sin evidencia vuelve a aparecer.

Este registro **no** reemplaza a los ADR (que fijan decisiones) ni a las notas de PR (que explican un cambio): los enlaza. Tampoco es una lista de deseos; solo entra lo que ya cuesta o costará, con un disparador concreto.

## 2. Cómo se usa

1. **Alta.** Es el criterio #17 de `docs/05-engineering/definition-of-done.md`: toda PR deja registrada o paga la deuda que origina o descubre. Quien detecta deuda (revisión de PR, hallazgo de Sonar o de CI, auditoría, intermitencia de una prueba) abre una ficha con la plantilla de §9 en la misma PR o en la siguiente. Una deuda que se paga en la misma PR que la origina no necesita ficha.
2. **Clasificación.** Cada ficha lleva tipo, severidad, esfuerzo, confianza, interés y **disparador**: el hito o la fecha a partir de los cuales deja de ser aceptable. La severidad se fija por impacto, no por lo incómoda que sea de arreglar.
3. **Revisión.** Al cerrar cada sprint se repasa el tablero de §4: nada de severidad S2 puede pasar su disparador abierto, y las fichas sin movimiento durante tres sprints se revalidan o se descartan.
4. **Pago.** Cada PR que toca un área con deuda abierta de esfuerzo XS en esa área la paga. Cada sprint reserva al menos una partida para deuda S2 o S3.
5. **Cierre.** Una ficha se pasa a §6 solo con **evidencia**: PR, prueba que falla sin el arreglo y, si procede, métrica. «Resuelta» sin criterio de cierre cumplido se reabre.
6. **Aceptación.** Aceptar deuda es una decisión con motivo, dueño y **fecha de vencimiento**; al vencer se revisa. Las aceptadas viven en §7.

## 3. Clasificación

**Severidad** (impacto si no se paga)

| Nivel | Nombre | Criterio | Plazo |
|---|---|---|---|
| S1 | Crítica | Pérdida o exposición de datos, o servicio caído | Antes de fusionar o desplegar |
| S2 | Alta | Bloquea o degrada un hito próximo, o riesgo de privacidad o seguridad no explotable hoy | Antes de su disparador |
| S3 | Media | Coste recurrente de mantenimiento o rendimiento | En 3 sprints o al tocar el área |
| S4 | Baja | Mejora oportunista | Cuando convenga |

**Esfuerzo:** **XS** < 2 horas; **S** < 1 día; **M** 1 a 3 días; **L** 1 a 2 semanas; **XL** > 2 semanas.

**Confianza** (cómo se sabe): **Medida** (se midió o se contó con una herramienta), **Leída** (se deduce del código sin ejecutarlo), **Hipótesis** (se infiere de la documentación o del diseño y hay que validarla).

**Estado:** Abierta · Planificada (tiene sprint o ADR asignado) · En curso · Resuelta · Aceptada · Descartada.

## 4. Tablero

Corte: 27-nov-2026.

| Severidad | Abiertas o planificadas |
|---|---|
| S1 Crítica | 0 |
| S2 Alta | 4 |
| S3 Media | 9 |
| S4 Baja | 8 |
| **Total** | **21** |

- **Abiertas:** 16 · **Planificadas:** 5 · **Resueltas:** 44 · **Aceptadas:** 6.
- **S2 abiertas con disparador próximo** (decidir el siguiente sprint): TD-005 y TD-016 (primer despliegue), más TD-020 (antes de usuarios reales).
- **Métricas a seguir en cada revisión:** deuda abierta por severidad, edad de las S2 en días, número de cierres por sprint, aceptadas vencidas.

## 5. Registro de deuda abierta

| ID | Título | Tipo | Sev | Esf | Confianza | Estado | Disparador |
|---|---|---|---|---|---|---|---|
| TD-005 | Dimensionamiento de producción sin definir | Operación | S2 | S | Hipótesis | Abierta | Antes del primer despliegue (ADR-0026) |
| TD-010 | Tablas sin escritor ni purga: `idempotency_keys` y `audit_log` | Arquitectura | S3 | M | Leída | Abierta | Primer endpoint con `Idempotency-Key` o primera acción auditable |
| TD-011 | Marcado de la bandeja evento por evento | Rendimiento | S4 | S | Leída | Abierta | Con volumen |
| TD-013 | Duplicación estructural en el módulo `cv` | Código | S3 | M | Medida | Abierta | Antes de añadir una quinta colección o repetir el patrón en `job` |
| TD-014 | `Stamp` aplicado a medias | Código | S4 | XS | Medida | Abierta | Oportunista |
| TD-016 | Documentación y entrega sin cerrar | Documentación | S2 | L | Medida | Abierta | Antes del primer despliegue |
| TD-017 | Suite de pruebas con demasiados contextos | Pruebas | S3 | M | Medida | Abierta | Cuando `verify` supere 5 min en CI o al sumar `job`/`ats` |
| TD-020 | Lote de anonimización sin dueño; descubribilidad al apagar la capacidad | Cumplimiento | S2 | L | Leída | Abierta | Antes de tener usuarios reales |
| TD-022 | Calibración y cobertura de ESCO sin datos reales; arnés fuera de CI | Pruebas | S3 | L | Medida | Abierta | Antes de fijar el modelo de embeddings (ADR-0018) |
| TD-027 | Los nombres propios del texto libre no se redactan antes del embedding | Privacidad | S3 | L | Medida | Abierta | Antes de embeber perfiles reales (`embedentity`) |
| TD-029 | La latencia de `embedentity` no está medida: la VM de desarrollo no expone AVX2 | Operación | S3 | S | Leída | Abierta | Hardware con AVX2 disponible (ADR-0026); el instrumento ya existe |
| TD-023 | Fin de soporte de Spring Boot 4.1 (31-jul-2027) | Plataforma | S3 | L | Medida | Planificada | 2027-04-30 (inicio de la subida) |
| TD-024 | El `Dockerfile` compila con una imagen de Java 26 | Operación | S4 | XS | Leída | Abierta | Oportunista |
| TD-025 | La purga de la bandeja usa `Instant.now()` | Código | S4 | XS | Leída | Abierta | Oportunista |
| TD-026 | Habilidades sin enlace a la taxonomía | Dominio | S4 | L | Medida | Planificada | Sub-paso de enlazado de ADR-0019 |
| TD-037 | El puntaje se publica sin calibrar: `α`, pesos y Platt son valores iniciales | Calidad | S2 | M | Leída | Planificada | Pares etiquetados reales (protocolo de 300 pares); las herramientas ya existen |
| TD-038 | Taxonomía de habilidades y experiencia relevante (los aliases ya están) | Dominio | S3 | M | Leída | Planificada | ADR-0019 implementado en `cv` |
| TD-040 | `ats` debe llamar a `snapshotapplicationprofile` al postular y al cambiar de etapa | Dominio | S3 | S | Leída | Planificada | Postulación y pipeline mínimos (`ats`) |
| TD-045 | La migración de versión del algoritmo busca pares con `<>`, sin índice que la sirva | Rendimiento | S4 | XS | Leída | Abierta | Más de ~100 000 pares guardados |
| TD-046 | La espera del cerrojo de vitalidad bloquea hilos de petición hasta 10 s | Concurrencia | S4 | XS | Leída | Abierta | Oportunista; antes si se ven hilos agotados |
| TD-047 | Reemplazar los requisitos de una vacante inserta fila por fila | Rendimiento | S4 | XS | Leída | Abierta | Oportunista |

### Fichas

#### TD-005 — Dimensionamiento de producción sin definir

**Operación** · **S2 Alta** · esfuerzo **S** · confianza **Hipótesis** · **Abierta**

- **Evidencia:** Sin configuración de pool de conexiones, hilos de Tomcat, hilos virtuales ni banderas de JVM más allá de `MaxRAMPercentage=75`. El agrupador de Supabase impone el límite real.
- **Interés (qué cuesta no pagarla):** Con valores por defecto (pool de 10, 200 hilos) las peticiones esperan conexión bajo carga moderada.
- **Disparador:** Antes del primer despliegue (ADR-0026).
- **Remediación propuesta:** Fijar `maximum-pool-size`, `connection-timeout` y `max-lifetime` según el límite del agrupador; decidir `spring.threads.virtual.enabled`; añadir `-XX:+ExitOnOutOfMemoryError`. Documentar en ADR-0026.
- **Criterio de cierre:** Valores en `application-prod.yml` con su razón y una prueba de carga básica sin saturar el pool a la concurrencia objetivo.
- **Referencias:** `docs/03-architecture/adr/0026-deployment-topology-and-disaster-recovery.md`

#### TD-010 — Tablas sin escritor ni purga: `idempotency_keys` y `audit_log`

**Arquitectura** · **S3 Media** · esfuerzo **M** · confianza **Leída** · **Abierta**

- **Evidencia:** `audit_log` ya tiene escritor (`JdbcAuditLog`, usado por la revisión de reclutadores desde el PR #41) pero ninguna retención. `idempotency_keys` sigue sin escritor ni purga. Revisado el 27-nov-2026.
- **Interés (qué cuesta no pagarla):** Esquema sin uso que hay que migrar y mantener; sin purga crecerá sin límite cuando se use.
- **Disparador:** Primer endpoint con `Idempotency-Key` o primera acción auditable.
- **Remediación propuesta:** Implementar el escritor y la retención con su caso de uso, o posponer la migración hasta que se necesite.
- **Criterio de cierre:** Caso de uso con escritor, retención y prueba.
- **Referencias:** `docs/03-architecture/adr/0011-configurable-rate-limiting.md`

#### TD-011 — Marcado de la bandeja evento por evento

**Rendimiento** · **S4 Baja** · esfuerzo **S** · confianza **Leída** · **Abierta**

- **Evidencia:** Un `UPDATE` por evento procesado dentro del lote.
- **Interés (qué cuesta no pagarla):** Hasta 50 sentencias por pasada.
- **Disparador:** Con volumen.
- **Remediación propuesta:** `UPDATE ... WHERE id = ANY(:ids)` para los procesados.
- **Criterio de cierre:** Pasada de 50 eventos con una sentencia de marcado.
- **Referencias:** `docs/03-architecture/adr/0016-transactional-outbox-for-domain-events.md`

#### TD-013 — Duplicación estructural en el módulo `cv`

**Código** · **S3 Media** · esfuerzo **M** · confianza **Medida** · **Abierta**

- **Evidencia:** Cuatro `Manage*Service` de ~100 líneas con 56 de diferencia tras renombrar; `ProfileCollectionsController` (327) y `ProfileScreenController` (341) repiten el patrón cuatro veces; `ProfileChange` (176) y `CandidateChange` (229) repiten fallar, publicar y versionar.
- **Interés (qué cuesta no pagarla):** Cada cambio de regla se hace 4–6 veces; el riesgo de divergencia crece con cada colección.
- **Disparador:** Antes de añadir una quinta colección o repetir el patrón en `job`.
- **Remediación propuesta:** Colección genérica con estrategia (fábrica, validador, almacén) y base común para los coordinadores.
- **Criterio de cierre:** ≥ 600 líneas menos, las mismas pruebas verdes sin cambiar su contenido y ArchUnit verde.
- **Referencias:** `docs/03-architecture/tdd/cv-module.md`

#### TD-014 — `Stamp` aplicado a medias

**Código** · **S4 Baja** · esfuerzo **XS** · confianza **Medida** · **Abierta**

- **Evidencia:** `ProfileChange`, `SaveProfessionalProfileService`, `CandidateChange` y `ManageAvailabilityExclusionsService` conservan `Clock` y `Supplier<UUID>` sueltos.
- **Interés (qué cuesta no pagarla):** Dos convenciones para lo mismo.
- **Disparador:** Oportunista.
- **Remediación propuesta:** Unificar con `Stamp`.
- **Criterio de cierre:** Ningún constructor de servicio de `cv` recibe `Supplier<UUID>` suelto.
- **Referencias:** `cv-module/.../application/support/Stamp.java`

#### TD-016 — Documentación y entrega sin cerrar

**Documentación** · **S2 Alta** · esfuerzo **L** · confianza **Medida** · **Abierta**

- **Evidencia:** 35 documentos «En revisión»; ADR-0021 aprobado (26-nov-2026) y 0022 a 0025 sin escribir; ADR-0026 sin aprobar (bloqueado por datos del responsable); `cd-staging.yml` es un marcador inerte.
- **Interés (qué cuesta no pagarla):** Sin ADR de almacenamiento de archivos, notificaciones, búsqueda, organizaciones e IA responsable no se pueden construir esas funciones; sin despliegue no hay validación real.
- **Disparador:** Antes del primer despliegue.
- **Remediación propuesta:** Escribir y aprobar los ADR pendientes, resolver el bloqueo de ADR-0026 y activar el CD.
- **Criterio de cierre:** Los cinco ADR escritos y el 0026 aprobado; CD ejecutando contra staging.
- **Referencias:** `docs/03-architecture/adr/0026-deployment-topology-and-disaster-recovery.md`

#### TD-017 — Suite de pruebas con demasiados contextos

**Pruebas** · **S3 Media** · esfuerzo **M** · confianza **Medida** · **Abierta**

- **Evidencia:** 25 clases `@SpringBootTest`, 13 con su propio `@DynamicPropertySource` (un contexto cada una); un Postgres por módulo; sin paralelismo. CI ≈ 3 min 15 s; local ≈ 8 min.
- **Interés (qué cuesta no pagarla):** Cada módulo nuevo suma minutos y más intermitencias (hubo tres, todas por el sondeador real).
- **Disparador:** Cuando `verify` supere 5 min en CI o al sumar `job`/`ats`.
- **Remediación propuesta:** Base común con el emisor de pruebas compartido, reutilizar contenedores y paralelizar las pruebas puras.
- **Criterio de cierre:** Número de contextos distintos medido (objetivo ≤ 5) y tiempo de `verify` en CI registrado.
- **Referencias:** `docs/05-engineering/test-strategy.md`

#### TD-020 — Lote de anonimización sin dueño; descubribilidad al apagar la capacidad

**Cumplimiento** · **S2 Alta** · esfuerzo **L** · confianza **Leída** · **Abierta**

- **Evidencia:** `anonymizeprofiledata` existe pero nadie lo invoca: el TDD de «Cumplimiento y ciclo de vida de cuenta» no existe, así que el lote, la llamada a Clavaris (BR-DATA-06) y el apagado de la descubribilidad al desactivar la capacidad no tienen dueño.
- **Interés (qué cuesta no pagarla):** Sin el lote no se cumple el derecho al olvido prometido en la política de privacidad.
- **Disparador:** Antes de tener usuarios reales.
- **Remediación propuesta:** Escribir el TDD, decidir el módulo dueño e implementar el lote.
- **Criterio de cierre:** TDD aprobado y lote que invoca `anonymizeprofiledata` y la API de administración de Clavaris.
- **Referencias:** `docs/01-product/business-rules.md` (BR-DATA-04 a 06)

#### TD-022 — Calibración y cobertura de ESCO sin datos reales; arnés fuera de CI

**Pruebas** · **S3 Media** · esfuerzo **L** · confianza **Medida** · **Abierta**

- **Evidencia:** El arnés de `scripts/eval` está construido pero no se ha corrido con datos reales; faltan unos 30 CV y ofertas anonimizados y una cohorte etiquetada. Las pruebas del arnés en Python no corren en CI.
- **Interés (qué cuesta no pagarla):** El modelo de embeddings sigue provisional y la métrica de precisión (≥ 0,85) no se puede demostrar.
- **Disparador:** Antes de fijar el modelo de embeddings (ADR-0018).
- **Remediación propuesta:** Conseguir los datos con consentimiento, correr la medición y conectar las pruebas del arnés a CI.
- **Criterio de cierre:** Informe de cobertura y calibración archivado y pruebas del arnés en CI.
- **Referencias:** `docs/03-architecture/adr/0018-embedding-model-selection-and-versioning.md`

#### TD-027 — Los nombres propios del texto libre no se redactan antes del embedding

**Privacidad** · **S3 Media** · esfuerzo **L** · confianza **Medida** · **Abierta**

- **Evidencia:** `PiiRedactor` quita correos, enlaces, usuarios y cifras de contacto, y una prueba fija que «Trabajé con Ana López» sigue intacto: reconocer nombres exige un modelo de lenguaje, no una expresión regular.
- **Interés (qué cuesta no pagarla):** Un nombre escrito en una descripción llega al servicio de embeddings. Es Ollama local por defecto (ADR-0004), así que no sale del entorno propio; sube de gravedad si algún caso usa la API externa.
- **Disparador:** Antes de embeber perfiles reales (`embedentity`).
- **Remediación propuesta:** Reconocimiento de entidades con un modelo local sobre las descripciones, o excluir las descripciones de la representación canónica si la medición de ADR-0017 muestra que no aportan.
- **Criterio de cierre:** Decisión registrada con medición del aporte de las descripciones al puntaje, y prueba de nombres si se mantienen.
- **Referencias:** `docs/03-architecture/adr/0017-matching-v2-pipeline-calibration-and-freshness.md`

#### TD-029 — La latencia de `embedentity` no está medida: la VM de desarrollo no expone AVX2

**Operación** · **S3 Media** · esfuerzo **S** · confianza **Leída** · **Abierta**

- **Evidencia:** El contrato del cliente ya se validó contra un Ollama real (TD-R25) y el instrumento de medición existe y está probado (`scripts/eval/embedding_latency.py`, TD-R28), pero esta VM oculta AVX/AVX2 (verificado de nuevo el 25-nov-2026): `llama.cpp` corre con SSE y un embedding de ~25 tokens tarda de 4 a 25 s. Un perfil de ~400 tokens son ~80 s más 30 s de ventana: ~110 s, al límite del objetivo de 120 s.
- **Interés (qué cuesta no pagarla):** El objetivo de frescura p95 ≤ 120 s está instrumentado y su contrato validado, pero no demostrado; y si ADR-0026 elige un huésped que oculta AVX2, el embedding será unas cien veces más lento sin que nada lo avise.
- **Disparador:** Hardware con AVX2 disponible (ADR-0026).
- **Remediación propuesta:** Ejecutar `python3 -I scripts/eval/embedding_latency.py --repeats 20 --out latencia.json` en ese hardware (se niega a medir sin AVX2), archivar la tabla en el doc 08 §8 y comprobar con `--num-thread` si fijarlo por debajo de la cuota de CPU estabiliza los tiempos. Procedimiento en `11-medicion-recuperacion-vectorial-y-latencia-de-embeddings.md` §3.3.
- **Criterio de cierre:** Una medición archivada en hardware con AVX2 con el modelo vigente, y el p95 observado frente al objetivo.
- **Referencias:** `docs/03-architecture/architecture-review-2026-11/08-medicion-ollama-y-bakeoff-embeddings.md` §8; `11-medicion-recuperacion-vectorial-y-latencia-de-embeddings.md`

#### TD-023 — Fin de soporte de Spring Boot 4.1 (31-jul-2027)

**Plataforma** · **S3 Media** · esfuerzo **L** · confianza **Medida** · **Planificada**

- **Evidencia:** ADR-0014 fija Boot 4.1.x y advierte que su soporte de código abierto termina el 31-jul-2027.
- **Interés (qué cuesta no pagarla):** Sin parches de seguridad después de esa fecha.
- **Disparador:** 2027-04-30 (inicio de la subida).
- **Remediación propuesta:** Subida de versión menor con la suite completa y el arnés como red.
- **Criterio de cierre:** Aplicación en la última versión menor soportada.
- **Referencias:** `docs/03-architecture/adr/0014-baseline-spring-boot-4-and-dependency-hygiene.md`

#### TD-024 — El `Dockerfile` compila con una imagen de Java 26

**Operación** · **S4 Baja** · esfuerzo **XS** · confianza **Leída** · **Abierta**

- **Evidencia:** Etapa de construcción `maven:3.9-eclipse-temurin-26`, ejecución en Java 25; una comprobación de bytecode evita el desajuste.
- **Interés (qué cuesta no pagarla):** Dos versiones de JDK en la misma cadena.
- **Disparador:** Oportunista.
- **Remediación propuesta:** Usar la imagen de Java 25 en ambas etapas.
- **Criterio de cierre:** Ambas etapas en Java 25 y la comprobación de bytecode intacta.
- **Referencias:** `docs/03-architecture/adr/0014-baseline-spring-boot-4-and-dependency-hygiene.md`

#### TD-025 — La purga de la bandeja usa `Instant.now()`

**Código** · **S4 Baja** · esfuerzo **XS** · confianza **Leída** · **Abierta**

- **Evidencia:** `OutboxScheduler.purge` lee el reloj del sistema directamente. (Desde el 27-nov-2026, `markRetry` ya usa el reloj de la base, como el reclamo; la purga sigue igual.)
- **Interés (qué cuesta no pagarla):** No se puede probar el corte de retención sin esperar.
- **Disparador:** Oportunista.
- **Remediación propuesta:** Inyectar un `Clock`.
- **Criterio de cierre:** Prueba de la retención con reloj fijo.
- **Referencias:** `docs/03-architecture/adr/0016-transactional-outbox-for-domain-events.md`

#### TD-026 — Habilidades sin enlace a la taxonomía

**Dominio** · **S4 Baja** · esfuerzo **L** · confianza **Medida** · **Planificada**

- **Evidencia:** `candidate_skills` guarda solo el texto escrito; `skill_id` no existe todavía.
- **Interés (qué cuesta no pagarla):** La dimensión de habilidades de `matching` compara por embedding en lugar de por identificador.
- **Disparador:** Sub-paso de enlazado de ADR-0019.
- **Remediación propuesta:** Enlazado en tres pasos de ADR-0019 con sus controles D1–D4.
- **Criterio de cierre:** Controles D1–D4 verificados.
- **Referencias:** `docs/03-architecture/adr/0019-skills-taxonomy-and-normalization.md`

#### TD-037 — El puntaje se publica sin calibrar: `α`, pesos y Platt son valores iniciales

**Calidad** · **S2 Alta** · esfuerzo **M** · confianza **Leída** · **Planificada**

- **Evidencia:** El puntaje es **preliminar** (ADR-0030): la respuesta lleva `calibrated: false`, `jobseeker_matching_scoring_calibrated` vale 0 y arranca un aviso en el registro; los pesos son los de `03-lld.md` §2.3, `α = 0,30` y la completitud mínima 0,5 son los iniciales. **Las herramientas para cerrarlo ya existen y están probadas** (`scripts/eval/matching_eval.py`: `calibrate`, `emit-config`, que se niega si no se cumple el protocolo, y `retrieval-recall` para el control C1); lo que falta son **datos reales**. Con embeddings reales el coseno vacante-candidato va de 0,17 a 0,85, así que sin calibrar el semántico comprime el rango.
- **Interés (qué cuesta no pagarla):** La métrica de éxito «precisión ≥ 0,85» no puede medirse con un puntaje sin calibrar, y el orden de los sugeridos puede estar sesgado por la compresión del coseno.
- **Disparador:** Pares etiquetados reales (protocolo de 300 pares del ADR-0018).
- **Remediación propuesta:** Etiquetar los pares con dos personas independientes (κ ≥ 0,6); `calibrate` para ver el ajuste fuera de pliegue; `retrieval-recall` (C1, ≥ 0,95); fijar el umbral contrafactual (C5); `emit-config` y pegar las dos propiedades.
- **Criterio de cierre:** Parámetros ajustados y archivados, precisión con intervalo de confianza sobre la reserva, y `calibrated: true` en producción.
- **Referencias:** ADR-0017 decisiones 5 y 12; ADR-0030; `12-lectura-por-lotes-y-persistencia-del-puntaje.md` §5; `scripts/eval/README.md`

#### TD-038 — Taxonomía de habilidades y experiencia relevante (los aliases ya están)

**Dominio** · **S3 Media** · esfuerzo **M** · confianza **Leída** · **Planificada**

- **Evidencia:** Los **aliases** de habilidades inequívocos ya se comparan como la misma (36 grupos: «JS» y «JavaScript», «k8s» y «Kubernetes»; TD-R32). Falta la taxonomía del ADR-0019: enlazar cada habilidad a un concepto (ESCO, con atribución obligatoria) y el escalón de embeddings calibrado. La experiencia sigue sumando **todos** los años declarados, no los relevantes al puesto; una heurística léxica se descartó por penalizar a quien escribe en otro idioma.
- **Interés (qué cuesta no pagarla):** Sinónimos fuera de los grupos siguen sin coincidir y los puntajes de experiencia se inflan para quien tiene años en otra área.
- **Disparador:** ADR-0019 implementado en `cv` (descarga del *dataset* con su licencia e importación).
- **Remediación propuesta:** Enlazar habilidades a conceptos y comparar por identificador (los aliases pasan a ser las habilidades locales sembradas); ponderar la experiencia por su afinidad con la vacante, medida con embeddings, y validarlo contra el conjunto etiquetado.
- **Criterio de cierre:** Sinónimos conocidos cubren el mismo requisito y la experiencia relevante sustituye al total, medido en el conjunto dorado.
- **Referencias:** ADR-0019; `12-lectura-por-lotes-y-persistencia-del-puntaje.md` §4

#### TD-040 — `ats` debe llamar a `snapshotapplicationprofile` al postular y al cambiar de etapa

**Dominio** · **S3 Media** · esfuerzo **S** · confianza **Leída** · **Planificada**

- **Evidencia:** `snapshotapplicationprofile` está construido y probado (foto del puntaje y copia inmutable del perfil canónico, idempotente por postulación), pero `ats` aún no existe, así que nadie lo invoca.
- **Interés (qué cuesta no pagarla):** Sin la llamada, el reclutador evaluaría el perfil que el candidato edite después y no el que presentó (ADR-0017, F6), y no habría historial del puntaje por etapa.
- **Disparador:** Postulación y pipeline mínimos (`ats`).
- **Remediación propuesta:** Que el caso de uso de postular llame a `snapshotapplicationprofile` con `APPLIED` y el de cambio de etapa con `STAGE_CHANGED`, dentro de la misma transacción de la postulación, pasando el identificador opaco de la postulación.
- **Criterio de cierre:** Una postulación de punta a punta deja su copia del perfil y la foto del puntaje, probado en un e2e de `ats`.
- **Referencias:** `docs/03-architecture/tdd/matching-module.md` §4 y §10.4

#### TD-045 — La migración de versión del algoritmo busca pares con `<>`, sin índice que la sirva

**Rendimiento** · **S4 Baja** · esfuerzo **XS** · confianza **Leída** · **Abierta**

- **Evidencia:** `JdbcScoreStore.staleVersion` filtra `algorithm_version <> :version`. El índice `idx_csc_version (algorithm_version, computed_at)` no sirve a una desigualdad, así que cada pasada del mantenimiento recorre la tabla entera.
- **Interés (qué cuesta no pagarla):** Despreciable con miles de pares; con cientos de miles, cada pasada cuesta un recorrido completo, aunque no quede nada por migrar.
- **Disparador:** Más de ~100 000 pares guardados.
- **Remediación propuesta:** Índice parcial sobre los pares sin anonimizar ordenado por `computed_at` y una consulta por rango, o guardar un número de versión creciente y filtrar con `<`.
- **Criterio de cierre:** `EXPLAIN` sin recorrido secuencial con la tabla poblada.
- **Referencias:** ADR-0031; `JdbcScoreStore`

#### TD-046 — La espera del cerrojo de vitalidad bloquea hilos de petición hasta 10 s

**Concurrencia** · **S4 Baja** · esfuerzo **XS** · confianza **Leída** · **Abierta**

- **Evidencia:** `LivenessStore.acquireLock` sondea Redis cada 50 ms hasta `lockWait` (10 s por defecto) en el hilo de la petición. Si no lo obtiene, `LivenessCoordinator` deja pasar la petición igualmente (`lock_agotado`), así que esperar solo sirve para ver el estado ya renovado.
- **Interés (qué cuesta no pagarla):** Mientras una sesión renueva su token, cada petición paralela de esa sesión (HTMX dispara varias) ocupa un hilo de Tomcat hasta que Clavaris responde, hasta 10 s si va lento.
- **Disparador:** Oportunista; antes si se ven hilos agotados.
- **Remediación propuesta:** Bajar `lock-wait` a unos cientos de milisegundos: quien no obtiene el cerrojo sigue con el estado que ya tenía, que está dentro del margen.
- **Criterio de cierre:** Prueba con renovación lenta en la que las peticiones paralelas de la sesión terminan sin esperarla.
- **Referencias:** ADR-0020, regla 12; `LivenessStore`

#### TD-047 — Reemplazar los requisitos de una vacante inserta fila por fila

**Rendimiento** · **S4 Baja** · esfuerzo **XS** · confianza **Leída** · **Abierta**

- **Evidencia:** `JdbcJobRequirementsRepository.replace` hace un `INSERT` por requisito, el patrón que `cv` ya pagó con una sentencia `unnest` (TD-R11).
- **Interés (qué cuesta no pagarla):** Unas decenas de viajes a la base por edición de requisitos; poco, pero dentro de la transacción de la edición.
- **Disparador:** Oportunista.
- **Remediación propuesta:** Una sola sentencia con `unnest` sobre arreglos paralelos, como en `cv`.
- **Criterio de cierre:** Reemplazo de un conjunto con una sentencia, medido en una prueba de conteo.
- **Referencias:** TD-R11

## 6. Deuda resuelta

| ID | Título | Tipo | Sev | Fecha | PR o decisión | Cómo se resolvió |
|---|---|---|---|---|---|---|
| TD-R01 | Intermitencias por el sondeador real que robaba filas a las pruebas | Pruebas | S3 | 2026-11-18 a 2026-11-24 | #21, #31 | Anotación `@WithoutOutboxScheduler` y una prueba guarda por clase que lo verifica. Aplicada a `OutboxPollerIntegrationTest`, `ClavarisWebhookIntegrationTest` y `MigrationsIntegrationTest`. |
| TD-R02 | Un nivel de habilidad ausente se leía como 0 (`wasNull` tras otra lectura) | Código | S2 | 2026-11-23 | #28 | Corregido el orden de lectura; pruebas contra Postgres real y de extremo a extremo. |
| TD-R03 | Importes de distinta escala eran datos distintos y un reenvío subía la versión | Código | S2 | 2026-11-23 | #28 | Escala fija de dos decimales en `SalaryExpectation`; pruebas a nivel de dominio, JDBC y API. |
| TD-R04 | Ayudantes de entrada web copiados en cuatro controladores | Código | S3 | 2026-11-24 | #29 | Extraídos a `WebInputs`. |
| TD-R05 | La configuración de `cv` importaba un tipo de `auth` (violación de ARCH-04) | Arquitectura | S2 | 2026-11-23 | #24 | Adaptador registrado como componente; la configuración solo ve el puerto de `cv`. |
| TD-R06 | `nomic-embed-text` como modelo por defecto medido peor que una línea base léxica | Arquitectura | S2 | 2026-11-18 | ADR-0018 | Modelo provisional `qwen3-embedding:0.6b` y calibración obligatoria por modelo. |
| TD-R07 | La anonimización conservaba habilidades, salario y disponibilidad | Privacidad | S2 | 2026-11-24 | #31 | Se anulan o borran; restricción `CHECK` en la base de datos; BR-DATA-05 y política de privacidad (borrador) actualizados. |
| TD-R08 | Migraciones rechazadas por el lint (`smallint`, `drop not null`) | Operación | S4 | 2026-11-24 | #28, #31 | `integer` en el nivel de habilidad y excepción documentada con su razón en la migración de anonimización. |
| TD-R09 | La comprobación de capacidad se repetía al reemplazar un conjunto (antes TD-003) | Rendimiento | S4 | 2026-11-24 | #33 | Se comprueba una sola vez, antes de validar los datos; prueba que cuenta una llamada por operación. |
| TD-R10 | La cuenta se resolvía varias veces por petición (antes TD-004) | Rendimiento | S3 | 2026-11-24 | #33 | `RequestScopedActorResolution` recuerda la resolución solo dentro del ámbito que abre `ActorFilter`. Lectura del perfil de candidato: de 6 a 5 consultas; reemplazo de un conjunto: de 3 resoluciones de cuenta a 1. Prueba de extremo a extremo que cuenta las llamadas reales. |
| TD-R11 | Inserciones fila por fila al reemplazar conjuntos (antes TD-006) | Rendimiento | S3 | 2026-11-24 | #33 | Una sentencia `unnest` por conjunto; 100 habilidades cuestan 2 sentencias en vez de 101. Prueba que cuenta las sentencias que llegan al controlador JDBC. |
| TD-R12 | Monitor global en el limitador de tasa (antes TD-007) | Concurrencia | S3 | 2026-11-24 | #33 | La ruta caliente lee un `AtomicReference` sin monitor; solo la conexión inicial y su reintento sincronizan. Prueba que falla si se restituye el `synchronized`. |
| TD-R13 | Registro INFO en cada lectura de perfil (antes TD-008) | Operación | S4 | 2026-11-24 | #33 | Las dos lecturas registran en DEBUG; la métrica sigue contando cada una. |
| TD-R14 | La memoria por petición de TD-004 tenía límites implícitos: dependía de qué petición hubiera enlazada al hilo y de que cada caso de uso futuro se acordara de invalidarla (hallazgo al pagar TD-004) | Concurrencia | S2 | 2026-11-24 | #33 | El ámbito es explícito (lo abre y cierra `ActorFilter`; sin él no se recuerda nada) y toda escritura de `accounts` avisa por `AccountChangeObserver` desde el adaptador que escribe. Una prueba estructural falla si aparece un escritor de `accounts` que no avise. Decisión: no se recuerdan los rechazos (no ahorran nada y «sin aprovisionar» rompería la carrera del primer acceso). |
| TD-R15 | Los consumidores del sondeador corrían dentro de la transacción de reclamo (antes TD-001) | Arquitectura | S2 | 2026-11-24 | #34 | Reclamo corto con arrendamiento y despacho sin transacción ni bloqueos de fila; un evento que mata el proceso en bucle acaba en FAILED. ADR-0016 enmendado. Pruebas contra Postgres real y verificación restaurando el comportamiento anterior. |
| TD-R16 | La promesa de orden por agregado de ADR-0016 no se cumplía (antes TD-018) | Concurrencia | S2 | 2026-11-24 | #34 | El reclamo toma como mucho un evento por agregado, el de menor versión, y ninguno si hay otro en vuelo; un reintento en espera no bloquea a los siguientes. Prueba con cuatro instancias y contrato de nombres de los consumidores. |
| TD-R17 | Qué conserva la copia del perfil de la postulación al anonimizar (antes TD-019) | Privacidad | S2 | 2026-11-24 | #34 | Decisión D5 resuelta: la copia pierde `canonical_text` y `canonical_hash`; las fotos del score nunca guardan texto del candidato. En el TDD de `matching` y en el modelo de datos, con su criterio de aceptación. |
| TD-R18 | Redacción de datos personales del texto libre antes del embedding (antes TD-021) | Privacidad | S2 | 2026-11-24 | #34 | `PiiRedactor` y un `EmbeddableText` que solo se construye redactado; `EmbeddingClientPort` solo acepta ese tipo, así que el compilador lo garantiza. Queda TD-027 para los nombres propios. |
| TD-R19 | Índice `idx_outbox_aggregate` sin lector (antes TD-009) | Rendimiento | S4 | 2026-11-24 | #34 | Descartada: el nuevo reclamo lo usa para comprobar si un agregado tiene un evento en vuelo. |
| TD-R20 | Un solo hilo para todas las tareas programadas (antes TD-002) | Concurrencia | S3 | 2026-11-24 | #35 | Planificador de cuatro hilos configurable (`SCHEDULER_POOL_SIZE`). Prueba de que una tarea lenta no retrasa a otra; lo necesitaba el trabajador de embeddings, que llama a Ollama durante segundos. |
| TD-R21 | Sin métricas de frescura de los embeddings (parte de TD-029) | Operación | S3 | 2026-11-24 | #35 | Histograma edición→vector con el objetivo de 120 s como cubo, edad del trabajo listo más viejo y profundidad por estado; tres alertas probadas con `promtool` y tres paneles. La medición con un Ollama real sigue abierta en TD-029. |
| TD-R22 | Cualquier reclutador podía publicar sin límite (antes TD-030, parte) | Seguridad | S2 | 2026-11-24 | #38 | Verificación de la cuenta (`UNVERIFIED`, `VERIFIED`, `SUSPENDED`) en `auth`, topes de vacantes publicadas por confianza (2 y 100 por defecto) y puerta al publicar o reabrir, con candado por reclutador contra la carrera. 25 pruebas de punta a punta, una repetida en 25 rondas; sin el candado falla en la ronda 4. Lo que queda (quién verifica y las sugerencias) es TD-032. |
| TD-R23 | Reglas de formato repetidas en `cv` y a punto de repetirse en `job` (antes TD-031) | Código | S4 | 2026-11-24 | #36 | `FormatRules` y `SalaryRules` en `common`, con pruebas propias; se borró `CandidateRules` de `cv` y la validación del salario quedó en un solo lugar, para que `job` las use en vez de copiarlas. Las pruebas existentes de `cv` pasan sin cambios. De paso, una moneda inexistente ya no deja el salario marcado como utilizable. |
| TD-R24 | El lado de la vacante de `embedentity` no existía (antes TD-028) | Arquitectura | S3 | 2026-11-25 | #39 | `EmbedJob` con su cola compartida con los perfiles, `JobSignals` y su lectura anticorrupción sobre `getjobsignals`, el consumidor de los siete eventos de `job`, las copias `published` y `recruiting_stage` (BR-MATCH-08) y una migración que admite vacantes en la cola. Solo una vacante publicada se embebe; cerrar conserva el vector y reabrir no llama al modelo. Nueve escenarios de punta a punta desde la API de `job`. |
| TD-R25 | Sin validar el contrato del cliente de embeddings con un Ollama real (parte de TD-029) | Operación | S3 | 2026-11-25 | #40 | `OllamaEmbeddingClientRealTest`, repetible con `OLLAMA_REAL_URL` y omitida por defecto, comprueba contra Ollama 0.40.17: dimensión 1024, norma 1, el 400 de `truncate=false` que el cliente trata como rechazo, que otra dimensión de columna se rechaza, que un servidor caído es no disponible y una sanidad semántica. Cambiar `truncate` a `true` la hace fallar. Quedó anotado que cambiar `num_ctx` o `num_thread` recarga el modelo. |
| TD-R26 | Nadie verificaba a los reclutadores (antes TD-032, parte) | Seguridad | S2 | 2026-11-25 | #41 | Canal `PUT /api/v1/admin/recruiters/{id}/verification` solo para operadores (lista de configuración, vacía por defecto; `404` para quien no lo es), sin decidir sobre uno mismo y con cada cambio en `audit_log` en la misma transacción, sin datos personales. Verificar sube el tope de publicación de TD-030: probado de punta a punta. La vía automática por correo queda para cuando haya proveedor de correo; las sugerencias que exijan `VERIFIED` son TD-033. |
| TD-R27 | Las sugerencias no exigían un reclutador verificado ni cuota de perfiles vistos (antes TD-033) | Seguridad | S2 | 2026-11-25 | #42 | `suggestcandidatesforjob` niega con `403` a quien no sea `VERIFIED` (antes de tocar la vacante), exige ser dueño de una vacante publicada y cuenta como visto cada perfil único mostrado (200 por 24 h, configurable, `429 QUOTA_EXCEEDED`), bajo un candado por reclutador y en la misma transacción que `profile_view_log`; la cuota vive en la base y no en Redis, así no hay un camino en que una caída la deje pasar. Endpoint con su propio límite de tasa (10/min). Probado de punta a punta; la respuesta no lleva similitud ni datos personales. |
| TD-R28 | Recuperación vectorial con filtros sin validar ni aplicar (antes TD-012) | Rendimiento | S2 | 2026-11-25 | #43 | `nearestDiscoverable` fija `hnsw.iterative_scan = relaxed_order` y `ef_search` no menor que la página, local a la transacción y con reordenado exterior; `ef_search` configurable (100). Medido con la consulta real y el HNSW forzado (comprobado por `EXPLAIN`): con un 90 % de vectores de otro modelo, sin la regla una página de 50 devuelve 3,6 filas (recall 0,07) y con ella 50 filas con recall ≈ 0,95; sin coste de latencia medible (p50 ≈ 30 ms). Prueba de regresión y medición repetible en `11-medicion-recuperacion-vectorial-y-latencia-de-embeddings.md`. |
| TD-R29 | Los sugeridos solo devolvían el orden: sin puntaje, prioridad, exclusión ni tarjeta (antes TD-034) | Dominio | S2 | 2026-11-25 | #44 | Puntaje explicable de seis dimensiones más la afinidad semántica con `α` visible, completitud mínima, `algorithm_version` por *hash* de pesos y calibración y marca de no calibrado; desempate `OPEN_TO_WORK` con una tolerancia de un punto, configurable (BR-MATCH-07, ADR-0030), que respeta la exclusión (BR-MATCH-09) preguntándola a `cv`; tarjeta enmascarada con el titular redactado (REQ-C-07). Propiedades probadas (límites, idempotencia, monotonía y C3) y de punta a punta con la exclusión, la completitud y sin datos personales ni coseno crudo en la respuesta. Lo que queda fuera: TD-036 a TD-039. |
| TD-R30 | Puntuar 200 recuperados leía sus perfiles uno a uno (antes TD-039) | Rendimiento | S3 | 2026-11-25 | #45 | Lectura por lotes en `auth` (`ResolveActorsUseCase`), `cv` (`GetProfileSnapshotsUseCase`, `GetVisibleAvailabilitiesUseCase`) y `matching`; la individual es el caso de una cuenta. Medido con 200 candidatos: **p50 1.246 ms a 118 ms**, de ≈ 2.400 consultas a ≈ 20 fijas; probado que el número de sentencias no crece con los perfiles y que ningún adaptador de producción olvida el método por lotes (`BatchReadsAreOverriddenTest`). |
| TD-R31 | El puntaje no se persistía, cacheaba ni recalculaba, y el candidato no veía el suyo (antes TD-036) | Dominio | S3 | 2026-11-25 | #46 | Tablas del puntaje vigente, fotos y copia del perfil canónico; casos de uso `calculatecompatibilityscore`, `recomputescoresforentity`, `explainscore` (con «qué pasaría si»), `snapshotapplicationprofile` y `migratealgorithmversion`; endpoints del candidato con su visibilidad y «actualizando…»; anonimización que conserva el par y el puntaje. Sin caché en Redis (ADR-0031, aprobado). Lo que depende de `ats` queda en TD-040. |
| TD-R32 | Habilidades por texto normalizado sin alias (parte de TD-038) | Dominio | S3 | 2026-11-25 | #47 | 36 grupos de alias inequívocos («JS» = «JavaScript», «k8s» = «Kubernetes»); una prueba garantiza que ningún nombre está en dos grupos y otra que lo que solo se parece («Spring» y «Spring Boot») no se une. El código del algoritmo pasa a 2 para que la migración recalcule los puntajes guardados. La taxonomía y la experiencia relevante siguen en TD-038. |
| TD-R40 | Persistencia JDBC sin ADR y con SQL repetido (antes TD-015) | Documentación / Código | S3 | 2026-11-27 | Rama `fix/proxy-retention-and-upload-transactions` | ADR-0032 (aprobado el 27-nov-2026) registra JDBC explícito con `JdbcClient` y sus reglas; se corrigen `test-strategy.md`, `coding-standards.md`, `data-model.md`, `domain-model.md` y el comentario de `app/pom.xml`, que describían JPA; `JdbcCandidateProfileRepository` lleva sus columnas en una constante y los parámetros comunes en `detailsParams`. |
| TD-R41 | `profile_view_log` crecía sin retención (antes TD-035) | Operación | S3 | 2026-11-27 | Rama `fix/proxy-retention-and-upload-transactions` | `purgeexpiredprofileviews`: 90 días configurables, nunca menos que la ventana de la cuota; lote diario por tandas de 5 000 sin transacción propia; índice `idx_pvl_viewed_at` creado `CONCURRENTLY`. Pruebas de la política, de las tandas y del borrado contra Postgres. |
| TD-R42 | Sin cabeceras de *proxy* de confianza (antes TD-042) | Seguridad / Operación | S2 | 2026-11-27 | Rama `fix/proxy-retention-and-upload-transactions` | `forward-headers-strategy: native` con `internal-proxies` desde `TRUSTED_PROXIES`, vacía por defecto (no confía en nadie). Prueba con servidor real: detrás del proxy cada cliente tiene su límite y su esquema; quien no es el proxy no puede falsificarlos; y Tomcat toma la lista vacía como ninguna. |
| TD-R43 | Carrera entre anonimizar un perfil y guardar su vector (antes TD-043) | Privacidad | S3 | 2026-11-27 | Rama `fix/proxy-retention-and-upload-transactions` | La guarda lee la cuenta `FOR SHARE` en la misma sentencia y espera a la anonimización en curso. Prueba que intercala ambas operaciones y falla con la guarda anterior. |
| TD-R44 | Las subidas retenían una conexión durante el antivirus y el almacén (antes TD-044) | Rendimiento | S3 | 2026-11-27 | Rama `fix/proxy-retention-and-upload-transactions` | El CV y la foto se escanean, procesan y suben sin transacción; solo el alta va en una transacción corta (`TransactionOperations`). Pruebas que registran qué ocurre dentro y fuera de ella. |
| TD-R34 | Las marcas de la bandeja no comprobaban el arrendamiento y un lote lento lo dejaba vencer en vuelo | Concurrencia | S2 | 2026-11-27 | #53 | Marcas con el intento como testigo de propiedad (solo escribe quien tiene el arrendamiento vigente) y, pasado el 80 % del arrendamiento, las filas sin despachar se devuelven con su intento. Probado contra Postgres. |
| TD-R35 | La cola de embeddings tenía el mismo fallo: lote de 10 llamadas de hasta 60 s con arrendamiento de 5 min | Concurrencia | S2 | 2026-11-27 | #53 | Mismo testigo de propiedad en `complete`, `retryAt` y `fail`; `release` devuelve el intento; el trabajador no empieza un trabajo fuera del margen. |
| TD-R36 | Guardar puntajes podía bloquearse mutuamente y un cálculo antiguo pisaba uno reciente | Concurrencia | S3 | 2026-11-27 | #53 | Filas ordenadas por par antes del *upsert*; las columnas del puntaje se quedan con el cálculo más reciente y el motivo se sigue combinando. La sentencia es una constante entera, sin partes armadas al ejecutar (lo exigió el análisis de seguridad de Sonar). Prueba con escrituras cruzadas concurrentes. |
| TD-R37 | Recalcular un perfil leía cada vacante por separado (hasta 400 consultas por edición) | Rendimiento | S3 | 2026-11-27 | #53 | `GetJobSignalsBatchUseCase` en `job` y `readAll` en `matching`, con métodos por defecto sustituidos en los adaptadores reales (cubiertos por `BatchReadsAreOverriddenTest`). Dos consultas en total. |
| TD-R38 | El registro de perfiles vistos insertaba fila por fila con el candado del reclutador tomado | Rendimiento | S4 | 2026-11-27 | #53 | Una sentencia `unnest` para todos los mostrados. |
| TD-R39 | La foto se decodificaba a resolución completa: hasta ~160 MB de montículo por subida | Operación | S2 | 2026-11-27 | #53 | Decodificación submuestreada con `ImageReadParam` hasta el doble del lado final; probado con una imagen de 4000 × 3000. |
| TD-R33 | Un objeto del almacén podía quedar huérfano si el alta del CV fallaba al confirmar (antes TD-041) | Datos | S4 | 2026-11-26 | #51 | `reconcileorphancvfiles`, cada 6 h: lista los objetos `cv/` por páginas y borra los que no tienen versión viva, también los que dejó un borrado por anonimización que agotó sus reintentos. No juzga objetos de menos de 24 h, nunca borra una clave de formato ajeno y se frena sin borrar nada si una pasada halla más de 500. Probado contra PostgreSQL y SeaweedFS reales. |

## 7. Deuda aceptada

| ID | Título | Decisión | Motivo | Se revisa | Condición de salida |
|---|---|---|---|---|---|
| TD-A01 | El limitador de tasa deja pasar las peticiones si Redis no responde | ADR-0011, ADR-0020 | Es la decisión inversa a un control de acceso y se eligió para que el límite no sea lo que tumbe el servicio. Mitigación: métrica `jobseeker_ratelimit_total{outcome="unavailable"}` y aviso cada 30 s. | 2027-02-01 | Alertar sobre esa métrica en Alertmanager. |
| TD-A02 | Excepciones de squawk `constraint-missing-not-valid` en `auth` (`V20261122100000`) y `cv` (`V20261124110000`) | ADR-0027 | Las tablas no existen en ningún entorno desplegado: no hay filas que escanear ni escrituras que bloquear. | Primer despliegue (ADR-0026) | Desde entonces, `NOT VALID` y `VALIDATE` en migraciones separadas. |
| TD-A03 | Habilidades como texto libre | ADR-0019 | Hasta el enlazado a la taxonomía no hay identificador que guardar. | Sub-paso de enlazado | Ver TD-026. |
| TD-A04 | Un `404` al excluir una cuenta revela si ese UUID existe | `docs/03-architecture/tdd/cv-module.md` §10.4 | Los identificadores son UUID aleatorios de 122 bits, no enumerables; «nunca existió» y «ya no vuelve» responden igual. | 2027-02-01 | Revisar si algún identificador deja de ser aleatorio. |
| TD-A06 | El reclamo de la bandeja está serializado por un bloqueo de aviso global y una fila en vuelo se reentrega al vencer el arrendamiento | ADR-0016 (enmienda 24-nov-2026) | El bloqueo dura milisegundos y hace correcto el orden por agregado; la reentrega tras un fallo del marcado es redundancia, no pérdida, porque los consumidores son idempotentes. Desde TD-R34 una fila solo se reentrega si un único despacho supera el 20 % del arrendamiento que se deja de margen, y el dueño anterior ya no puede escribir sobre ella. | 2027-02-01 | Si el volumen hace del bloqueo un cuello de botella, particionar su clave por rango de agregado. |
| TD-A05 | Tope de 500 exclusiones como límite blando | `docs/03-architecture/tdd/cv-module.md` §10.4 | Contar e insertar no es atómico; bajo concurrencia podría pasarse por pocas filas sin consecuencia. | Sin vencimiento | Hacerlo estricto solo si el tope pasa a ser un límite de negocio. |

## 8. Historial de este documento

| Versión | Fecha | Cambio |
|---|---|---|
| 1.0 | 2026-11-24 | Registro inicial: 26 fichas abiertas o planificadas de la auditoría, 8 resueltas con su evidencia y 5 aceptadas con fecha de revisión. |
| 1.1 | 2026-11-24 | Se pagan TD-003, TD-004, TD-006, TD-007 y TD-008 (PR #33) y pasan a resueltas como TD-R09 a TD-R13; TD-R14 registra el límite implícito que apareció al pagar TD-004 y su arreglo. Quedan 18 abiertas o planificadas, 21 resueltas y 6 aceptadas. |
| 1.2 | 2026-11-24 | Se pagan TD-001, TD-018, TD-019 y TD-021 (PR #34) y pasan a resueltas como TD-R15 a TD-R18; TD-009 se descarta (TD-R19) porque el nuevo reclamo usa su índice. Entran TD-027 (nombres propios sin redactar) y TD-A06 (bloqueo de reclamo). Quedan 18 abiertas o planificadas, 21 resueltas y 6 aceptadas. |
| 1.3 | 2026-11-24 | Se paga TD-002 (PR #35, TD-R20) porque el trabajador de embeddings lo exigía. Entran TD-028 (el lado de la vacante de `embedentity`) y TD-029 (medición con un Ollama real); las métricas de frescura de TD-029 se pagan en el mismo PR (TD-R21). Quedan 18 abiertas o planificadas, 21 resueltas y 6 aceptadas. |
| 1.4 | 2026-11-24 | Se paga TD-031 (reglas de formato repetidas en `cv` y a punto de repetirse en `job`, PR #36, TD-R23): se encontró y se pagó en el mismo cambio. Quedan 18 abiertas o planificadas, 22 resueltas y 6 aceptadas. |
| 1.5 | 2026-11-24 | Entra TD-030 (verificación de reclutador y cuotas antes de publicar, D1 del TDD de `job`), que dejó el primer corte de `job` (PR #37). Quedan 19 abiertas o planificadas, 22 resueltas y 6 aceptadas. |
| 1.6 | 2026-11-24 | Se paga la parte de TD-030 que se podía cerrar (puerta de publicar con topes por confianza, PR #38, TD-R22). Lo que queda pasa a TD-032: nadie verifica a los reclutadores y las sugerencias aún no exigen la verificación. Quedan 19 abiertas o planificadas, 23 resueltas y 6 aceptadas. |
| 1.7 | 2026-11-25 | Se paga el canal de verificación de TD-032 (PR #41, TD-R26): un operador verifica, suspende o devuelve a «sin verificar», con auditoría. Entra TD-033: las sugerencias, cuando existan, deben exigir `VERIFIED` y la cuota diaria. Quedan 19 abiertas o planificadas, 24 resueltas y 6 aceptadas. |
| 1.8 | 2026-11-25 | Se paga TD-028 (PR #39, TD-R24): el lado de la vacante de `embedentity`, desbloqueado por `getjobsignals` del #37. Quedan 18 abiertas o planificadas, 25 resueltas y 6 aceptadas. |
| 1.9 | 2026-11-25 | Se paga la parte de contrato de TD-029 (PR #40, TD-R25): el cliente se validó contra un Ollama real. TD-029 sigue abierta con su alcance reducido a la latencia en hardware con AVX2. Quedan 18 abiertas o planificadas, 26 resueltas y 6 aceptadas. |
| 1.10 | 2026-11-25 | Se paga TD-033 (PR #42, TD-R27): los sugeridos exigen un reclutador verificado y cuentan una cuota de perfiles únicos. Se confirma la verificación manual por operadores (ADR-0029). Entran TD-034 (puntaje, prioridad, exclusión y lectura enmascarada) y TD-035 (retención de `profile_view_log`); TD-012 ve cumplido su disparador. Quedan 19 abiertas o planificadas, 27 resueltas y 6 aceptadas. |
| 1.11 | 2026-11-25 | Se paga TD-012 (PR #43, TD-R28): la regla de escaneo iterativo ya estaba medida pero no aplicada; ahora se aplica, se mide con la consulta real y la protege una prueba de regresión. TD-029 sigue abierta: la VM no tiene AVX2, pero el instrumento de medición ya existe y está probado. Quedan 18 abiertas o planificadas, 28 resueltas y 6 aceptadas. |
| 1.12 | 2026-11-25 | Se paga TD-034 (PR #44, TD-R29): puntaje explicable, prioridad por disponibilidad, exclusión y tarjeta enmascarada en los sugeridos. Entran TD-036 (persistencia, caché, recálculo y puntaje del candidato), TD-037 (calibración), TD-038 (taxonomía de habilidades y experiencia relevante) y TD-039 (costo de leer 200 perfiles uno a uno). El ADR-0030 formaliza el desempate por tolerancia, la completitud aplicable y el puntaje preliminar, con un indicador de calibración. Quedan 21 abiertas o planificadas, 29 resueltas y 6 aceptadas. |
| 1.13 | 2026-11-25 | Se paga TD-039 (PR #45, TD-R30): lectura por lotes en `auth`, `cv` y `matching`, medida con 200 candidatos (p50 de 1.246 ms a 118 ms) y protegida por una prueba de arquitectura. Se documenta la regla en `coding-standards.md` §8.1. Quedan 20 abiertas o planificadas, 30 resueltas y 6 aceptadas. |
| 1.14 | 2026-11-25 | Se paga TD-036 (PR #46, TD-R31): puntaje guardado solo para pares con motivo, recalculado dentro del trabajador de la cola, visible al candidato con su «qué pasaría si», migración de versión con fotos y anonimización que conserva el par y el puntaje. El ADR-0031 enmienda la decisión 8 del ADR-0017: sin caché en Redis. Entra TD-040 (`ats` debe llamar a `snapshotapplicationprofile`). Quedan 20 abiertas o planificadas, 31 resueltas y 6 aceptadas. |
| 1.15 | 2026-11-25 | Se paga la parte de aliases de TD-038 (PR #47, TD-R32): 36 grupos de alias inequívocos y el código del algoritmo pasa a 2. TD-037 sigue abierta con las herramientas listas (`emit-config`, `retrieval-recall`), a la espera de pares etiquetados reales; TD-038 sigue abierta por la taxonomía y la experiencia relevante. Quedan 20 abiertas o planificadas, 32 resueltas y 6 aceptadas. |
| 1.16 | 2026-11-26 | TD-016 actualiza su evidencia: el ADR-0021 (almacenamiento de archivos y escaneo de malware) queda aprobado y quedan por escribir los ADR-0022 a 0025. Sin cambios en los recuentos. |
| 1.17 | 2026-11-26 | Subida, consulta y descarga del CV (ADR-0021, sub-paso 7 de `cv`) y borrado físico de sus archivos al anonimizar. Entra TD-041 (objetos huérfanos si un alta falla al confirmar). Quedan 21 abiertas o planificadas, 32 resueltas y 6 aceptadas. |
| 1.21 | 2026-11-27 | Se pagan las cinco deudas implementables de mayor prioridad: TD-042 (S2, *proxy* de confianza), TD-015 (ADR-0032 y SQL repetido; su disparador ya había pasado), TD-043 (carrera de anonimización), TD-035 (retención de vistos) y TD-044 (subidas sin transacción larga). Pasan a resueltas como TD-R40 a TD-R44. El responsable aprueba el ADR-0032 y el plazo de 90 días del registro de vistos (`tdd/matching-module.md` §10.6), que también recoge el borrador de la política de privacidad. Quedan 21 abiertas o planificadas (4 S2, 9 S3, 8 S4), 44 resueltas y 6 aceptadas. |
| 1.20 | 2026-11-27 | Fusionado el PR #53: TD-R34 a TD-R39 citan el PR en lugar de la rama, y TD-R36 anota que el *upsert* de puntajes es una constante entera. Sin cambios en el tablero: 26 abiertas o planificadas, 39 resueltas y 6 aceptadas. |
| 1.19 | 2026-11-27 | Revisión completa del código como SDE III. Se pagan seis deudas encontradas en ella (TD-R34 a TD-R39): marcas sin testigo de propiedad y lotes que vencían en vuelo en la bandeja y en la cola de embeddings, *upsert* de puntajes propenso a bloqueos mutuos y a escrituras perdidas, lectura de vacantes una a una al recalcular, registro de vistos fila por fila y decodificación de fotos a resolución completa. Entran TD-042 a TD-047. Se corrigen el tablero, que contaba 21 abiertas o planificadas cuando había 20 (12 S3 en vez de 11) y 29 resueltas en vez de 33, la fecha de la cabecera y la evidencia de TD-010, TD-025 y TD-A06. Quedan 26 abiertas o planificadas, 39 resueltas y 6 aceptadas. |
| 1.18 | 2026-11-26 | Se paga TD-041 (PR #51, TD-R33): reconciliación periódica de CV huérfanos con espera de 24 h y freno ante borrados masivos. Quedan 20 abiertas o planificadas, 33 resueltas y 6 aceptadas. |

## 9. Plantilla de ficha

```markdown
#### TD-NNN — Título en una línea

**Tipo** · **S? Nombre** · esfuerzo **?** · confianza **?** · **Estado**

- **Evidencia:** qué se observó y dónde (archivo, consulta, medición).
- **Interés (qué cuesta no pagarla):** a quién y cuánto afecta, y cómo crece.
- **Disparador:** el hito o la fecha desde la cual deja de ser aceptable.
- **Remediación propuesta:** el arreglo y sus alternativas.
- **Criterio de cierre:** una comprobación verificable (prueba, métrica o decisión registrada).
- **Referencias:** ADR, TDD o PR.
```

Al cerrarla se mueve a §6 con la fecha, el PR y una frase de cómo se resolvió. Al aceptarla se mueve a §7 con motivo, fecha de revisión y condición de salida.
