# Registro de Deuda Técnica
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | 🟡 En revisión |
| **Versión** | 1.4 — se paga TD-031 |
| **Fecha** | 24 de noviembre de 2026 |
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

Corte: 24-nov-2026.

| Severidad | Abiertas o planificadas |
|---|---|
| S1 Crítica | 0 |
| S2 Alta | 4 |
| S3 Media | 9 |
| S4 Baja | 5 |
| **Total** | **18** |

- **Abiertas:** 16 · **Planificadas:** 2 · **Resueltas:** 22 · **Aceptadas:** 6.
- **S2 abiertas con disparador próximo** (decidir el siguiente sprint): TD-012 (antes de `suggestcandidatesforjob`) y TD-005 y TD-016 (primer despliegue), más TD-020 (antes de usuarios reales).
- **Métricas a seguir en cada revisión:** deuda abierta por severidad, edad de las S2 en días, número de cierres por sprint, aceptadas vencidas.

## 5. Registro de deuda abierta

| ID | Título | Tipo | Sev | Esf | Confianza | Estado | Disparador |
|---|---|---|---|---|---|---|---|
| TD-005 | Dimensionamiento de producción sin definir | Operación | S2 | S | Hipótesis | Abierta | Antes del primer despliegue (ADR-0026) |
| TD-010 | Tablas sin escritor ni purga: `idempotency_keys` y `audit_log` | Arquitectura | S3 | M | Leída | Abierta | Primer endpoint con `Idempotency-Key` o primera acción auditable |
| TD-011 | Marcado de la bandeja evento por evento | Rendimiento | S4 | S | Leída | Abierta | Con volumen |
| TD-012 | Recuperación vectorial con filtros sin validar | Rendimiento | S2 | M | Hipótesis | Abierta | Antes de `suggestcandidatesforjob` |
| TD-013 | Duplicación estructural en el módulo `cv` | Código | S3 | M | Medida | Abierta | Antes de añadir una quinta colección o repetir el patrón en `job` |
| TD-014 | `Stamp` aplicado a medias | Código | S4 | XS | Medida | Abierta | Oportunista |
| TD-015 | Persistencia JDBC sin ADR y con SQL repetido | Documentación / Código | S3 | S | Medida | Abierta | Antes del módulo `job` |
| TD-016 | Documentación y entrega sin cerrar | Documentación | S2 | L | Medida | Abierta | Antes del primer despliegue |
| TD-017 | Suite de pruebas con demasiados contextos | Pruebas | S3 | M | Medida | Abierta | Cuando `verify` supere 5 min en CI o al sumar `job`/`ats` |
| TD-020 | Lote de anonimización sin dueño; descubribilidad al apagar la capacidad | Cumplimiento | S2 | L | Leída | Abierta | Antes de tener usuarios reales |
| TD-022 | Calibración y cobertura de ESCO sin datos reales; arnés fuera de CI | Pruebas | S3 | L | Medida | Abierta | Antes de fijar el modelo de embeddings (ADR-0018) |
| TD-027 | Los nombres propios del texto libre no se redactan antes del embedding | Privacidad | S3 | L | Medida | Abierta | Antes de embeber perfiles reales (`embedentity`) |
| TD-028 | El lado de la vacante de `embedentity` no existe: `JobSignals` y `job_embeddings` | Arquitectura | S3 | L | Leída | Abierta | TDD de `job` y primer módulo `job` con código |
| TD-029 | Sin medición de `embedentity` con un Ollama real | Operación | S3 | S | Leída | Abierta | Un Ollama con el modelo vigente disponible (ADR-0018, protocolo de 300 pares) |
| TD-023 | Fin de soporte de Spring Boot 4.1 (31-jul-2027) | Plataforma | S3 | L | Medida | Planificada | 2027-04-30 (inicio de la subida) |
| TD-024 | El `Dockerfile` compila con una imagen de Java 26 | Operación | S4 | XS | Leída | Abierta | Oportunista |
| TD-025 | La purga de la bandeja usa `Instant.now()` | Código | S4 | XS | Leída | Abierta | Oportunista |
| TD-026 | Habilidades sin enlace a la taxonomía | Dominio | S4 | L | Medida | Planificada | Sub-paso de enlazado de ADR-0019 |

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

- **Evidencia:** Las migraciones existen; ningún código las escribe, y `idempotency_keys` no tiene purga.
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

#### TD-012 — Recuperación vectorial con filtros sin validar

**Rendimiento** · **S2 Alta** · esfuerzo **M** · confianza **Hipótesis** · **Abierta**

- **Evidencia:** `nearestDiscoverable` filtra por modelo y descubribilidad sobre un índice HNSW parcial; exclusión y cuenta activa se aplicarán después. Con `ef_search` por defecto (40) puede devolver menos filas que `limit`.
- **Interés (qué cuesta no pagarla):** Sugeridos incompletos o con recall bajo sin que nada lo avise.
- **Disparador:** Antes de `suggestcandidatesforjob`.
- **Remediación propuesta:** `EXPLAIN` con volumen realista, `hnsw.iterative_scan`, `ef_search` configurable y prueba de recall con el arnés de `scripts/eval`.
- **Criterio de cierre:** Informe de recall@k y p95 con volumen realista, según ADR-0017.
- **Referencias:** `docs/03-architecture/adr/0017-matching-v2-pipeline-calibration-and-freshness.md`

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

#### TD-015 — Persistencia JDBC sin ADR y con SQL repetido

**Documentación / Código** · **S3 Media** · esfuerzo **S** · confianza **Medida** · **Abierta**

- **Evidencia:** La documentación de arquitectura declara Spring Data JPA / Hibernate; los cuatro módulos usan `JdbcClient` y no hay JPA en las dependencias. La decisión no tiene ADR. `JdbcCandidateProfileRepository` repite la lista de columnas 4 veces y dos bloques de enlace casi idénticos.
- **Interés (qué cuesta no pagarla):** Quien llegue creerá que hay ORM; el SQL repetido invita a que una columna nueva se olvide en uno de los sitios.
- **Disparador:** Antes del módulo `job`.
- **Remediación propuesta:** ADR «JDBC explícito» y actualizar la documentación que cita JPA; constantes de columnas y una función `params(profile)`.
- **Criterio de cierre:** ADR aprobado y documentación coherente; columnas definidas una vez por repositorio.
- **Referencias:** `docs/03-architecture/adr/0014-baseline-spring-boot-4-and-dependency-hygiene.md`

#### TD-016 — Documentación y entrega sin cerrar

**Documentación** · **S2 Alta** · esfuerzo **L** · confianza **Medida** · **Abierta**

- **Evidencia:** 35 documentos «En revisión»; ADR-0021 a 0025 sin escribir; ADR-0026 sin aprobar (bloqueado por datos del responsable); `cd-staging.yml` es un marcador inerte.
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

#### TD-028 — El lado de la vacante de `embedentity` no existe: `JobSignals` y `job_embeddings`

**Arquitectura** · **S3 Media** · esfuerzo **L** · confianza **Leída** · **Abierta**

- **Evidencia:** El sub-paso 3 de `matching` se hizo para perfiles; `job-module` no tiene código ni lectura pública, así que no hay de dónde construir `JobSignals` ni qué embeber. `EmbeddingStorePort` ya tiene los métodos de vacante, sin uso.
- **Interés (qué cuesta no pagarla):** Sin vector de vacante no hay score ni sugeridos: el motor solo funciona de un lado.
- **Disparador:** TDD de `job` y primer módulo `job` con código.
- **Remediación propuesta:** Diseñar la lectura pública de `job` en su TDD, y añadir el consumidor, el caso de uso y la cola de vacantes con el mismo patrón que perfiles (nivel disparado, lápida no aplica).
- **Criterio de cierre:** Una vacante publicada recibe su vector de punta a punta con una prueba equivalente a la de perfiles.
- **Referencias:** `docs/03-architecture/tdd/matching-module.md`

#### TD-029 — Sin medición de `embedentity` con un Ollama real

**Operación** · **S3 Media** · esfuerzo **S** · confianza **Leída** · **Abierta**

- **Evidencia:** Las pruebas usan un servidor de prueba: se desconoce la carga en frío, el efecto de `keep_alive` y `num_ctx`, y el tiempo real por perfil. Las métricas ya existen para observarlo.
- **Interés (qué cuesta no pagarla):** El objetivo de frescura p95 ≤ 120 s está instrumentado pero no demostrado con el modelo real.
- **Disparador:** Un Ollama con el modelo vigente disponible (ADR-0018, protocolo de 300 pares).
- **Remediación propuesta:** Correr el flujo contra Ollama con el modelo vigente, archivar la medición y ajustar `keep-alive`, `num-ctx` y el tamaño de lote con ella.
- **Criterio de cierre:** Una medición archivada con el modelo vigente y el p95 observado frente al objetivo.
- **Referencias:** `docs/03-architecture/adr/0018-embedding-model-selection-and-versioning.md`

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

- **Evidencia:** `OutboxScheduler.purge` lee el reloj del sistema directamente.
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
| TD-R23 | Reglas de formato repetidas en `cv` y a punto de repetirse en `job` (antes TD-031) | Código | S4 | 2026-11-24 | #36 | `FormatRules` y `SalaryRules` en `common`, con pruebas propias; se borró `CandidateRules` de `cv` y la validación del salario quedó en un solo lugar, para que `job` las use en vez de copiarlas. Las pruebas existentes de `cv` pasan sin cambios. De paso, una moneda inexistente ya no deja el salario marcado como utilizable. |

## 7. Deuda aceptada

| ID | Título | Decisión | Motivo | Se revisa | Condición de salida |
|---|---|---|---|---|---|
| TD-A01 | El limitador de tasa deja pasar las peticiones si Redis no responde | ADR-0011, ADR-0020 | Es la decisión inversa a un control de acceso y se eligió para que el límite no sea lo que tumbe el servicio. Mitigación: métrica `jobseeker_ratelimit_total{outcome="unavailable"}` y aviso cada 30 s. | 2027-02-01 | Alertar sobre esa métrica en Alertmanager. |
| TD-A02 | Excepciones de squawk `constraint-missing-not-valid` en `auth` (`V20261122100000`) y `cv` (`V20261124110000`) | ADR-0027 | Las tablas no existen en ningún entorno desplegado: no hay filas que escanear ni escrituras que bloquear. | Primer despliegue (ADR-0026) | Desde entonces, `NOT VALID` y `VALIDATE` en migraciones separadas. |
| TD-A03 | Habilidades como texto libre | ADR-0019 | Hasta el enlazado a la taxonomía no hay identificador que guardar. | Sub-paso de enlazado | Ver TD-026. |
| TD-A04 | Un `404` al excluir una cuenta revela si ese UUID existe | `docs/03-architecture/tdd/cv-module.md` §10.4 | Los identificadores son UUID aleatorios de 122 bits, no enumerables; «nunca existió» y «ya no vuelve» responden igual. | 2027-02-01 | Revisar si algún identificador deja de ser aleatorio. |
| TD-A06 | El reclamo de la bandeja está serializado por un bloqueo de aviso global y una fila en vuelo se reentrega al vencer el arrendamiento | ADR-0016 (enmienda 24-nov-2026) | El bloqueo dura milisegundos y hace correcto el orden por agregado; la reentrega tras un fallo del marcado es redundancia, no pérdida, porque los consumidores son idempotentes. | 2027-02-01 | Si el volumen hace del bloqueo un cuello de botella, particionar su clave por rango de agregado. |
| TD-A05 | Tope de 500 exclusiones como límite blando | `docs/03-architecture/tdd/cv-module.md` §10.4 | Contar e insertar no es atómico; bajo concurrencia podría pasarse por pocas filas sin consecuencia. | Sin vencimiento | Hacerlo estricto solo si el tope pasa a ser un límite de negocio. |

## 8. Historial de este documento

| Versión | Fecha | Cambio |
|---|---|---|
| 1.0 | 2026-11-24 | Registro inicial: 26 fichas abiertas o planificadas de la auditoría, 8 resueltas con su evidencia y 5 aceptadas con fecha de revisión. |
| 1.1 | 2026-11-24 | Se pagan TD-003, TD-004, TD-006, TD-007 y TD-008 (PR #33) y pasan a resueltas como TD-R09 a TD-R13; TD-R14 registra el límite implícito que apareció al pagar TD-004 y su arreglo. Quedan 18 abiertas o planificadas, 21 resueltas y 6 aceptadas. |
| 1.2 | 2026-11-24 | Se pagan TD-001, TD-018, TD-019 y TD-021 (PR #34) y pasan a resueltas como TD-R15 a TD-R18; TD-009 se descarta (TD-R19) porque el nuevo reclamo usa su índice. Entran TD-027 (nombres propios sin redactar) y TD-A06 (bloqueo de reclamo). Quedan 18 abiertas o planificadas, 21 resueltas y 6 aceptadas. |
| 1.3 | 2026-11-24 | Se paga TD-002 (PR #35, TD-R20) porque el trabajador de embeddings lo exigía. Entran TD-028 (el lado de la vacante de `embedentity`) y TD-029 (medición con un Ollama real); las métricas de frescura de TD-029 se pagan en el mismo PR (TD-R21). Quedan 18 abiertas o planificadas, 21 resueltas y 6 aceptadas. |
| 1.4 | 2026-11-24 | Se paga TD-031 (reglas de formato repetidas en `cv` y a punto de repetirse en `job`, PR #36, TD-R23): se encontró y se pagó en el mismo cambio. Quedan 18 abiertas o planificadas, 22 resueltas y 6 aceptadas. |

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
