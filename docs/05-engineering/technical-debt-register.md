# Registro de Deuda Técnica
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | 🟡 En revisión |
| **Versión** | 1.1 — se pagan TD-003, TD-004, TD-006, TD-007 y TD-008, y se endurece la memoria por petición |
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
| S2 Alta | 8 |
| S3 Media | 7 |
| S4 Baja | 6 |
| **Total** | **21** |

- **Abiertas:** 19 · **Planificadas:** 2 · **Resueltas:** 14 · **Aceptadas:** 5.
- **S2 abiertas con disparador próximo** (decidir el siguiente sprint): TD-001, TD-012, TD-018, TD-021 (todas dependen del primer consumidor de `matching`) y TD-005 (primer despliegue).
- **Métricas a seguir en cada revisión:** deuda abierta por severidad, edad de las S2 en días, número de cierres por sprint, aceptadas vencidas.

## 5. Registro de deuda abierta

| ID | Título | Tipo | Sev | Esf | Confianza | Estado | Disparador |
|---|---|---|---|---|---|---|---|
| TD-001 | Los consumidores del sondeador corren dentro de la transacción de reclamo | Arquitectura / Concurrencia | S2 | M | Leída | Abierta | Antes de implementar `embedentity` (paso 3 de `matching`) |
| TD-002 | Un solo hilo para todas las tareas programadas | Concurrencia | S3 | S | Leída | Abierta | Antes del primer lote programado (anonimización, archivado de vacantes) |
| TD-005 | Dimensionamiento de producción sin definir | Operación | S2 | S | Hipótesis | Abierta | Antes del primer despliegue (ADR-0026) |
| TD-009 | Índice sin lector en la bandeja (`idx_outbox_aggregate`) | Rendimiento | S4 | XS | Hipótesis | Abierta | Con tráfico real |
| TD-010 | Tablas sin escritor ni purga: `idempotency_keys` y `audit_log` | Arquitectura | S3 | M | Leída | Abierta | Primer endpoint con `Idempotency-Key` o primera acción auditable |
| TD-011 | Marcado de la bandeja evento por evento | Rendimiento | S4 | S | Leída | Abierta | Con volumen |
| TD-012 | Recuperación vectorial con filtros sin validar | Rendimiento | S2 | M | Hipótesis | Abierta | Antes de `suggestcandidatesforjob` |
| TD-013 | Duplicación estructural en el módulo `cv` | Código | S3 | M | Medida | Abierta | Antes de añadir una quinta colección o repetir el patrón en `job` |
| TD-014 | `Stamp` aplicado a medias | Código | S4 | XS | Medida | Abierta | Oportunista |
| TD-015 | Persistencia JDBC sin ADR y con SQL repetido | Documentación / Código | S3 | S | Medida | Abierta | Antes del módulo `job` |
| TD-016 | Documentación y entrega sin cerrar | Documentación | S2 | L | Medida | Abierta | Antes del primer despliegue |
| TD-017 | Suite de pruebas con demasiados contextos | Pruebas | S3 | M | Medida | Abierta | Cuando `verify` supere 5 min en CI o al sumar `job`/`ats` |
| TD-018 | Orden de eventos entre sondeadores sin prueba de contrato | Concurrencia | S2 | S | Leída | Abierta | Primer consumidor en `matching` |
| TD-019 | Qué conserva la copia del perfil de la postulación al anonimizar | Privacidad | S2 | M | Hipótesis | Abierta | TDD de `snapshotapplicationprofile` |
| TD-020 | Lote de anonimización sin dueño; descubribilidad al apagar la capacidad | Cumplimiento | S2 | L | Leída | Abierta | Antes de tener usuarios reales |
| TD-021 | Redacción de datos personales del texto libre antes del embedding | Privacidad | S2 | M | Leída | Abierta | `embedentity` |
| TD-022 | Calibración y cobertura de ESCO sin datos reales; arnés fuera de CI | Pruebas | S3 | L | Medida | Abierta | Antes de fijar el modelo de embeddings (ADR-0018) |
| TD-023 | Fin de soporte de Spring Boot 4.1 (31-jul-2027) | Plataforma | S3 | L | Medida | Planificada | 2027-04-30 (inicio de la subida) |
| TD-024 | El `Dockerfile` compila con una imagen de Java 26 | Operación | S4 | XS | Leída | Abierta | Oportunista |
| TD-025 | La purga de la bandeja usa `Instant.now()` | Código | S4 | XS | Leída | Abierta | Oportunista |
| TD-026 | Habilidades sin enlace a la taxonomía | Dominio | S4 | L | Medida | Planificada | Sub-paso de enlazado de ADR-0019 |

### Fichas

#### TD-001 — Los consumidores del sondeador corren dentro de la transacción de reclamo

**Arquitectura / Concurrencia** · **S2 Alta** · esfuerzo **M** · confianza **Leída** · **Abierta**

- **Evidencia:** `OutboxPoller.pollOnce` hace `claim` y `dispatch` de hasta `batch-size` (50) eventos dentro de un solo `TransactionTemplate.execute`.
- **Interés (qué cuesta no pagarla):** Hoy nulo (los consumidores son instantáneos). Cuando `matching` llame a Ollama retendrá una conexión del pool (10 por defecto) y los bloqueos de fila durante hasta 50 llamadas seguidas, y un lote lento puede dejar sin servicio a las peticiones.
- **Disparador:** Antes de implementar `embedentity` (paso 3 de `matching`).
- **Remediación propuesta:** (a) El consumidor solo encola trabajo en una tabla propia y un trabajador lo ejecuta fuera de transacción; o (b) reclamo corto con arrendamiento (`PROCESSING` + `lease_until`) y proceso sin transacción abierta. Enmendar ADR-0016.
- **Criterio de cierre:** Prueba de integración con un consumidor lento (≥ 2 s) que demuestre que no mantiene transacción ni conexión del pool abiertas mientras trabaja; ADR-0016 enmendado.
- **Referencias:** `docs/03-architecture/adr/0016-transactional-outbox-for-domain-events.md`

#### TD-002 — Un solo hilo para todas las tareas programadas

**Concurrencia** · **S3 Media** · esfuerzo **S** · confianza **Leída** · **Abierta**

- **Evidencia:** `@EnableScheduling` sin `TaskScheduler` configurado: el sondeo (cada 1 s), la purga y los lotes futuros comparten un hilo.
- **Interés (qué cuesta no pagarla):** Un lote largo retrasa la entrega de eventos de toda la aplicación.
- **Disparador:** Antes del primer lote programado (anonimización, archivado de vacantes).
- **Remediación propuesta:** `ThreadPoolTaskScheduler` con pool dimensionado por tarea, o ejecución en hilos virtuales. `fixedDelay` ya evita que una misma tarea se solape consigo misma.
- **Criterio de cierre:** Prueba que muestre que una tarea de 5 s no retrasa el sondeo.
- **Referencias:** `docs/03-architecture/adr/0016-transactional-outbox-for-domain-events.md`

#### TD-005 — Dimensionamiento de producción sin definir

**Operación** · **S2 Alta** · esfuerzo **S** · confianza **Hipótesis** · **Abierta**

- **Evidencia:** Sin configuración de pool de conexiones, hilos de Tomcat, hilos virtuales ni banderas de JVM más allá de `MaxRAMPercentage=75`. El agrupador de Supabase impone el límite real.
- **Interés (qué cuesta no pagarla):** Con valores por defecto (pool de 10, 200 hilos) las peticiones esperan conexión bajo carga moderada.
- **Disparador:** Antes del primer despliegue (ADR-0026).
- **Remediación propuesta:** Fijar `maximum-pool-size`, `connection-timeout` y `max-lifetime` según el límite del agrupador; decidir `spring.threads.virtual.enabled`; añadir `-XX:+ExitOnOutOfMemoryError`. Documentar en ADR-0026.
- **Criterio de cierre:** Valores en `application-prod.yml` con su razón y una prueba de carga básica sin saturar el pool a la concurrencia objetivo.
- **Referencias:** `docs/03-architecture/adr/0026-deployment-topology-and-disaster-recovery.md`

#### TD-009 — Índice sin lector en la bandeja (`idx_outbox_aggregate`)

**Rendimiento** · **S4 Baja** · esfuerzo **XS** · confianza **Hipótesis** · **Abierta**

- **Evidencia:** Ninguna consulta de `OutboxStore` filtra por agregado; el índice encarece cada alta de la tabla más escrita.
- **Interés (qué cuesta no pagarla):** Amplificación de escritura constante.
- **Disparador:** Con tráfico real.
- **Remediación propuesta:** Confirmar con `pg_stat_user_indexes` y retirar por migración (expand/contract), o documentar el lector previsto.
- **Criterio de cierre:** Migración o decisión registrada de conservarlo.
- **Referencias:** `docs/03-architecture/adr/0027-flyway-migrations-location-versioning-and-ownership.md`

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

#### TD-018 — Orden de eventos entre sondeadores sin prueba de contrato

**Concurrencia** · **S2 Alta** · esfuerzo **S** · confianza **Leída** · **Abierta**

- **Evidencia:** Con `SKIP LOCKED` y varios sondeadores, los eventos de un mismo agregado pueden llegar desordenados; ADR-0016 delega en los consumidores «el posterior gana por versión» y nada lo verifica.
- **Interés (qué cuesta no pagarla):** Un consumidor que aplique la versión 4 después de la 5 deja un estado obsoleto de forma permanente.
- **Disparador:** Primer consumidor en `matching`.
- **Remediación propuesta:** Prueba de contrato reutilizable (llega la v5 antes que la v4: el estado final es la v5) y exigirla a cada consumidor.
- **Criterio de cierre:** La prueba existe y la pasa cada consumidor.
- **Referencias:** `docs/03-architecture/adr/0016-transactional-outbox-for-domain-events.md`

#### TD-019 — Qué conserva la copia del perfil de la postulación al anonimizar

**Privacidad** · **S2 Alta** · esfuerzo **M** · confianza **Hipótesis** · **Abierta**

- **Evidencia:** `cv` borra habilidades, idiomas, autorizaciones, salario y disponibilidad (BR-DATA-05), pero `application_profile_snapshots` probablemente conserva habilidades ligadas a la cuenta.
- **Interés (qué cuesta no pagarla):** Un conjunto de habilidades específicas es casi único y reidentifica junto al `account_id`: el borrado de `cv` protegería menos de lo que parece.
- **Disparador:** TDD de `snapshotapplicationprofile`.
- **Remediación propuesta:** Reducir la copia a lo que el reclutador necesita para su historial, o desligarla de la cuenta al anonimizar.
- **Criterio de cierre:** Decisión registrada (D5 de `matching`) y prueba de anonimización sobre la copia.
- **Referencias:** `docs/03-architecture/tdd/matching-module.md`

#### TD-020 — Lote de anonimización sin dueño; descubribilidad al apagar la capacidad

**Cumplimiento** · **S2 Alta** · esfuerzo **L** · confianza **Leída** · **Abierta**

- **Evidencia:** `anonymizeprofiledata` existe pero nadie lo invoca: el TDD de «Cumplimiento y ciclo de vida de cuenta» no existe, así que el lote, la llamada a Clavaris (BR-DATA-06) y el apagado de la descubribilidad al desactivar la capacidad no tienen dueño.
- **Interés (qué cuesta no pagarla):** Sin el lote no se cumple el derecho al olvido prometido en la política de privacidad.
- **Disparador:** Antes de tener usuarios reales.
- **Remediación propuesta:** Escribir el TDD, decidir el módulo dueño e implementar el lote.
- **Criterio de cierre:** TDD aprobado y lote que invoca `anonymizeprofiledata` y la API de administración de Clavaris.
- **Referencias:** `docs/01-product/business-rules.md` (BR-DATA-04 a 06)

#### TD-021 — Redacción de datos personales del texto libre antes del embedding

**Privacidad** · **S2 Alta** · esfuerzo **M** · confianza **Leída** · **Abierta**

- **Evidencia:** `cv` entrega las descripciones tal como la persona las escribió; ADR-0017 asigna la redacción a `matching`, que aún no tiene código ni prueba.
- **Interés (qué cuesta no pagarla):** Un correo o teléfono escrito en una descripción llegaría al modelo de embeddings.
- **Disparador:** `embedentity`.
- **Remediación propuesta:** Redactor determinista en `matching` con prueba de que ningún patrón de contacto llega al texto que se embebe.
- **Criterio de cierre:** Prueba de redacción y control correspondiente de ADR-0017 verificado.
- **Referencias:** `docs/03-architecture/adr/0017-matching-v2-pipeline-calibration-and-freshness.md`

#### TD-022 — Calibración y cobertura de ESCO sin datos reales; arnés fuera de CI

**Pruebas** · **S3 Media** · esfuerzo **L** · confianza **Medida** · **Abierta**

- **Evidencia:** El arnés de `scripts/eval` está construido pero no se ha corrido con datos reales; faltan unos 30 CV y ofertas anonimizados y una cohorte etiquetada. Las pruebas del arnés en Python no corren en CI.
- **Interés (qué cuesta no pagarla):** El modelo de embeddings sigue provisional y la métrica de precisión (≥ 0,85) no se puede demostrar.
- **Disparador:** Antes de fijar el modelo de embeddings (ADR-0018).
- **Remediación propuesta:** Conseguir los datos con consentimiento, correr la medición y conectar las pruebas del arnés a CI.
- **Criterio de cierre:** Informe de cobertura y calibración archivado y pruebas del arnés en CI.
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

## 7. Deuda aceptada

| ID | Título | Decisión | Motivo | Se revisa | Condición de salida |
|---|---|---|---|---|---|
| TD-A01 | El limitador de tasa deja pasar las peticiones si Redis no responde | ADR-0011, ADR-0020 | Es la decisión inversa a un control de acceso y se eligió para que el límite no sea lo que tumbe el servicio. Mitigación: métrica `jobseeker_ratelimit_total{outcome="unavailable"}` y aviso cada 30 s. | 2027-02-01 | Alertar sobre esa métrica en Alertmanager. |
| TD-A02 | Excepciones de squawk `constraint-missing-not-valid` en `auth` (`V20261122100000`) y `cv` (`V20261124110000`) | ADR-0027 | Las tablas no existen en ningún entorno desplegado: no hay filas que escanear ni escrituras que bloquear. | Primer despliegue (ADR-0026) | Desde entonces, `NOT VALID` y `VALIDATE` en migraciones separadas. |
| TD-A03 | Habilidades como texto libre | ADR-0019 | Hasta el enlazado a la taxonomía no hay identificador que guardar. | Sub-paso de enlazado | Ver TD-026. |
| TD-A04 | Un `404` al excluir una cuenta revela si ese UUID existe | `docs/03-architecture/tdd/cv-module.md` §10.4 | Los identificadores son UUID aleatorios de 122 bits, no enumerables; «nunca existió» y «ya no vuelve» responden igual. | 2027-02-01 | Revisar si algún identificador deja de ser aleatorio. |
| TD-A05 | Tope de 500 exclusiones como límite blando | `docs/03-architecture/tdd/cv-module.md` §10.4 | Contar e insertar no es atómico; bajo concurrencia podría pasarse por pocas filas sin consecuencia. | Sin vencimiento | Hacerlo estricto solo si el tope pasa a ser un límite de negocio. |

## 8. Historial de este documento

| Versión | Fecha | Cambio |
|---|---|---|
| 1.0 | 2026-11-24 | Registro inicial: 26 fichas abiertas o planificadas de la auditoría, 8 resueltas con su evidencia y 5 aceptadas con fecha de revisión. |
| 1.1 | 2026-11-24 | Se pagan TD-003, TD-004, TD-006, TD-007 y TD-008 (PR #33) y pasan a resueltas como TD-R09 a TD-R13; TD-R14 registra el límite implícito que apareció al pagar TD-004 y su arreglo. Quedan 21 abiertas o planificadas, 14 resueltas y 5 aceptadas. |

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
