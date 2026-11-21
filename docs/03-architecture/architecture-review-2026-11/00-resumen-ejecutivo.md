# Architecture Review — Resumen ejecutivo
## JobSeeker: ATS + CRM con motor de compatibilidad CV–vacante bidireccional

| Campo | Valor |
|---|---|
| **Estado** | 🟡 En revisión — propuesta de revisión de arquitectura, **no modifica ADRs bloqueados** (0001–0008, 0010–0013); toda desviación se plantea como ADR nuevo a decidir |
| **Versión** | 1.0 |
| **Fecha** | 13 de noviembre de 2026 |
| **Rol de la revisión** | AI Solutions Architect senior (revisión estilo "design review" de empresa grande) |
| **Alcance** | Los 35 documentos de `docs/`, las reglas del proyecto, el scaffolding de código/infra del repo, el repo hermano `../Clavaris`, e investigación externa de plataformas competidoras, regulación y tecnología |
| **Partes** | `00` este resumen · `01` competencia y requerimientos · `02` HLD y system design · `03` LLD · `04` seguridad, cumplimiento, calidad, riesgos y defectos documentales |

---

## 1. Veredicto

**La documentación es de calidad inusualmente alta para un proyecto de un solo desarrollador** (reglas de negocio con IDs trazables, ADRs con alternativas rechazadas, STRIDE por módulo, NFR en formato estímulo→respuesta, separación disciplinada elegibilidad ≠ score). El **dominio está bien pensado**. Los riesgos reales **no están en el dominio sino en cinco zonas que la documentación aún no diseña**:

1. **El motor de matching está especificado como concepto, no como sistema.** Faltan: calibración del coseno a un porcentaje, política de datos faltantes, taxonomía de skills (35 % del score depende de ella), modelo de embeddings adecuado a español, definición operativa de "precisión ≥ 0.85", y semántica de frescura/consistencia del score.
2. **Superficie de scraping de PII.** Cualquiera puede habilitar la capacidad `RECRUITER` (BR-ACCT-01), publicar una vacante y obtener score + perfil de hasta 10 000 candidatos descubribles por defecto (opt-out). Es la amenaza #1 y la mitigación actual es solo rate limiting.
3. **Capacidades "table stakes" de un ATS ausentes del diseño**: notificaciones/email, búsqueda, almacenamiento de archivos, etapas de pipeline configurables, notas/rechazos con motivo, retiro de postulación, equipos/organizaciones.
4. **Consistencia e infraestructura de ejecución sin decidir**: entrega de eventos (in-process vs outbox), batches con varias instancias, **destino de producción y hospedaje de Ollama indefinidos**, modelo de sesión/CSRF para un frontend SSR.
5. **Línea base tecnológica desactualizada**: Spring Boot 3.4 salió de soporte OSS el 31-dic-2025 (la mayoría de fuentes; una difiere); JJWT es innecesario ahora que Clavaris emite los tokens. No hay código todavía → es el momento más barato para corregirlo.

**Recomendación global:** mantener el alcance de producto y el orden de olas, pero **(a)** insertar un *walking skeleton* end-to-end antes de profundizar módulos, **(b)** añadir a la Ola 1 ≈ 4 sprints de "fundamentos que hoy faltan" (evaluación del matching, notificaciones, archivos, búsqueda, verificación de reclutador), y **(c)** resolver los 12 ADRs propuestos en §5 antes de escribir el primer caso de uso de cada módulo afectado.

---

## 2. Hallazgos priorizados

Severidad: 🔴 bloquea calidad/seguridad del núcleo · 🟠 alto riesgo de retrabajo · 🟡 mejora relevante.

| # | Sev. | Hallazgo | Evidencia | Acción (parte) |
|---|---|---|---|---|
| F1 | 🔴 | **Scraping de PII vía sugerencias**: `RECRUITER` es auto-habilitable, no hay verificación de reclutador, `discoverable=true` por defecto, y la mitigación es solo rate limit + top-N | BR-ACCT-01, BR-DATA-03, BR-VIS-04, STRIDE §4 | Verificación + cuotas + datos de contacto enmascarados + auditoría de vistas (`04` §2, ADR-0025) |
| F2 | 🔴 | **Coseno ≠ porcentaje**: el coseno de `nomic-embed-text` no está calibrado a 0–100; presentarlo como "%" sin calibrar es engañoso y rompe BR-MATCH-02 en espíritu | ADR-0003, BR-MATCH-01 | Calibración con datos etiquetados (`02` §4, `03` §2) |
| F3 | 🔴 | **"Precisión ≥ 0.85" no es medible tal como está escrita**: sin definición de verdad-terreno, protocolo de etiquetado, ni tamaño de muestra. Con n=100 el IC 95 % de p=0.85 es ±7 pts; con NPS n=30 es ±26 pts | las reglas del proyecto §2, PRD §3 | Plan de evaluación (`01` §6, `04` §6) |
| F4 | 🔴 | **Skills sin taxonomía ni tabla**: 35 % del score depende de skills, pero `candidate_skills`/`job_requirements` están en el ER y **no** en el diccionario de datos; la normalización es pregunta abierta | data-model §3/§4, §6 | ESCO + normalización por embeddings (`02` §5, ADR-0019) |
| F5 | 🟠 | **Embeddings en español**: `nomic-embed-text` v1.x está orientado a inglés; el contenido (Guatemala) es mayormente español con mezcla de inglés técnico. Riesgo de precisión; además existe evidencia de sesgo de nombre en retrieval con embeddings | ADR-0004; Wilson & Caliskan 2024 | Bake-off de modelos + redacción de PII antes de embeber (`02` §4) |
| F6 | 🟠 | **Score "vigente" vivo vs. foto al postular**: el reclutador evalúa el score *actual*; un candidato puede inflar su perfil *después* de postular. Falta un snapshot inmutable de la postulación (y debe incluirse en la anonimización) | domain-model §4, BR-MATCH-03 | `application_profile_snapshots` (`03` §4) |
| F7 | 🟠 | **Histórico append-only por recálculo crece sin control**: cada edición × cada par recalcula una fila nueva (jsonb ~1 KB) | data-model §4.7 | `scores_current` (upsert) + snapshots por evento (`02` §4.4, `03` §4) |
| F8 | 🟠 | **Clave de caché incompleta**: `score:{cv_hash}:{job_hash}` no incluye `algorithm_version` ni hash de pesos → tras calibrar pesos se sirven scores obsoletos | integration-design §4 | Clave con versión+pesos (`03` §2.4) |
| F9 | 🟠 | **Eventos in-process sin entrega garantizada**: un crash entre commit y consumo pierde `ProfileUpdatedEvent` → score obsoleto permanente, contradiciendo BR-MATCH-03 | domain-model §6 | Outbox transaccional (`02` §6, ADR-0016) |
| F10 | 🟠 | **Frontend SSR modelado como API Bearer**: "JWT en header desde el navegador" no aplica a navegación SSR/HTMX; faltan sesión, CSRF y cookies; y NFR-SEC-07 (fuerza bruta de login) ya no es del dominio de JobSeeker (el login vive en Clavaris) | security-architecture §2, api-contract §3 | Dos superficies: `/app/**` sesión+CSRF, `/api/v1/**` Bearer (`04` §1, ADR-0020) |
| F11 | 🟠 | **Producción sin destino ni Ollama definido**: Supabase cubre solo la BD; no hay hospedaje del monolito, de Ollama (CPU/GPU), de Redis ni de la observabilidad | integration-design §5, security §4 | ADR-0026 topología y DR (`02` §7) |
| F12 | 🟠 | **Contradicción de despliegue**: `docker-compose` y las reglas del proyecto hablan de "Dockerfile por módulo desplegable" y scrape "de cada módulo", pero ADR-0001/0010 definen **un** desplegable, un spec OpenAPI y un `/actuator/prometheus`. El `pom.xml` no tiene módulo `app` | docker-compose.yml, pom.xml | Módulo `app` de arranque único (`02` §3) |
| F13 | 🟠 | **Anonimización incompleta**: no cubre `application_answers.answer_value` (texto libre), archivos `FILE_UPLOAD`, `follow_up_items`, PDFs de CV de marca generados ni snapshots | data-model §4.2/4.4, ADR-0013 | Inventario de PII completo (`04` §3) |
| F14 | 🟠 | **Notificaciones inexistentes en la arquitectura** pese a que BR-TRACK-02, BR-FUP-04, BR-ELIG-09 las exigen; ningún canal (email/in-app) ni proveedor | tracker-module, domain events | `NotificationPort` + outbox (`02` §6, ADR-0022) |
| F15 | 🟠 | **Sin modelo de organización/equipo** aunque el persona central es la agencia de outsourcing; añadirlo después obliga a reescribir todos los chequeos de propiedad `recruiter_account_id` | PRD §5, BR-CAND-05 nota | `organization_id` nullable desde la Ola 1 (`03` §4, ADR-0024) |
| F16 | 🟠 | **Línea base**: Boot 3.4 sin soporte OSS (31-dic-2025); Boot 4.0 GA (30-nov-2025); Spring AI 2.0 reportado GA (jun-2026); JJWT 0.12.6 sobra con Resource Server | las reglas del proyecto §3 | ADR-0014 (`04` §7) |
| F17 | 🟡 | **Riesgo de GraalVM Native** con openhtmltopdf/PDFBox/Hibernate/Spring AI (reflection, fuentes) | las reglas del proyecto §3 | No comprometer native en MVP; JVM + AppCDS (`02` §7) |
| F18 | 🟡 | **`.env.example` conserva `JWT_SECRET` HS256 / ADR-0009 retirado** | `.env.example` | Limpiar (`04` §8) |
| F19 | 🟡 | **Riesgo Clavaris sobrestimado en las reglas del proyecto §11**: Clavaris tiene Auth Code + PKCE operativo, rate limiting, rotación con detección de reuso. Lo pendiente es el *contrato* (scopes, registro de cliente, webhooks/eventos de cuenta, API admin de borrado) | la documentación de estado de Clavaris §11 | Spike de integración Sprint 1 (`02` §7) |
| F20 | 🟡 | **Pipeline ATS "completo" es delgado** frente al mercado: sin notas, motivos de rechazo, etapas configurables, acciones masivas, scorecards, analítica | PRD §7.5 | Backlog priorizado (`01` §5) |
| F21 | 🟡 | **Faltan NFR**: RTO/RPO, accesibilidad (WCAG 2.2 AA), percentiles (p95), seguridad de la cadena de suministro, costo, retención | NFR doc | `04` §5 |
| F22 | 🟡 | **~50 preguntas abiertas** dispersas en 15 documentos sin dueño ni fecha | todos | Decisiones recomendadas por defecto (`04` §9) |

---

## 3. Dónde JobSeeker puede **sobresalir** (síntesis de la investigación)

El mercado converge en "IA para filtrar/puntuar" (Indeed Smart Screening, Workable, Manatal, LinkedIn Hiring Assistant) pero **casi todo es del lado del reclutador y opaco**. La brecha defendible, ya intuida en el documento de mercado, se afina así:

| Diferenciador | Por qué es defendible | Costo |
|---|---|---|
| **D1. Explicabilidad accionable para el candidato** ("qué-pasaría-si": añadir skill X o completar dato Y ⇒ +N puntos; brecha por dimensión) | LinkedIn/Indeed muestran *por qué coincide* al reclutador; casi nadie da al candidato una hoja de ruta. Es barato porque el desglose ya es estructurado | Bajo (reusa el motor) |
| **D2. Transparencia de proceso** (SLA de respuesta por vacante, "último movimiento del reclutador", cierre automático de vacantes inactivas) | Ghosting y *ghost jobs* afectan a la mayoría de candidatos según encuestas sectoriales; BR-JOB-06 ya archiva, falta señal de actividad | Bajo |
| **D3. Equidad verificable como característica** (auditoría contrafactual publicada, redacción de PII antes de embeber, motivos de rechazo codificados) | EU AI Act (alto riesgo en reclutamiento), NYC LL144 y la literatura de sesgo en embeddings hacen de esto un requisito creciente; en LATAM casi nadie lo ofrece | Medio |
| **D4. LATAM/español primero** (embeddings multilingües evaluados en español, salarios regionales, WhatsApp/email, contexto USAC) | Plataformas globales optimizan inglés; Computrabajo/Bumeran dominan volumen pero sin explicabilidad | Medio |
| **D5. CV de marca para outsourcing** (ya diseñado) | Ningún competidor revisado lo cubre | Ya en roadmap (Ola 3) |
| **D6. Bucle de calibración con feedback del reclutador** (👍/👎 por coincidencia → etiquetas → recalibración de pesos) | Convierte uso real en el dataset de evaluación que la tesis necesita (y es contribución académica) | Medio |

**Lo que *no* conviene perseguir** (coste/beneficio malo para un desarrollador): agente autónomo de sourcing/outreach tipo Hiring Assistant, programación de entrevistas con calendarios, evaluación por video, base de datos externa de 400 M de perfiles, app móvil nativa.

---

## 4. Impacto en el roadmap (sin recortar alcance de producto)

| Cambio | Ola | Δ sprints | Justificación |
|---|---|---|---|
| **Sprint 0 / walking skeleton**: login Clavaris → perfil mínimo → vacante → score *stub* → postular, con CI/CD, Flyway, Actuator/Prometheus y despliegue a staging | 1 | +0.5 neto (el resto se absorbe en "Infra transversal") | Reduce el riesgo de integración que hoy se descubriría en el sprint 15 |
| Arnés de evaluación del matching (dataset dorado, métricas P@K/NDCG, prueba contrafactual de equidad en CI) | 1 | +1 | Sin esto "precisión ≥ 0.85" no puede demostrarse (F3) |
| Notificaciones (email + in-app) con outbox | 1 | +1 | F14; sin ellas el tracker no cumple BR-TRACK-02 |
| Almacenamiento de archivos + antivirus + URLs firmadas | 1 | +0.5 | CV upload es requisito de la Ola 1 (BR-CAND-01) |
| Búsqueda de vacantes (FTS español + híbrida con vector) | 1 | +0.5 | Hoy no está diseñada |
| Verificación de reclutador + cuotas de sugerencias | 1 | +0.5 | F1 |
| **Subtotal Ola 1** | | **≈ +4** (19 → ~23) | |
| Compensación propuesta: diferir a Ola 2 el efecto visible de `AvailabilityExclusion` para cuentas *candidato* (sin canal hoy) y `RecruitingStage` automático | 1→2 | −1 | Funcionalidad sin canal de uso en Ola 1 |
| ATS: etapas configurables, notas, motivos de rechazo, acciones masivas | 2 | +2 | F20 (ya cabe parcialmente en "ATS completo", 3 sprints) |
| `Organization`/equipos (reutilizando el modelo de Clavaris) | 2–3 | +2 | F15; el *esquema* (`organization_id` nullable) va en Ola 1 sin costo |

Total revisado ≈ **45–47 sprints** (40 + 4 − 1 + 2 + 2; los +2 de ATS y los +2 de organizaciones son los candidatos naturales a recorte, ver gates del roadmap §11); **checkpoint de defensa** sigue siendo el cierre de Ola 2. Si hay fecha fija de defensa, el recorte debe aplicar primero a ATS "nice-to-have" (acciones masivas, analítica), **nunca** al arnés de evaluación: sin él, la tesis no puede demostrar su métrica principal.

---

## 5. ADRs propuestos (numeración desde 0014; 0009 permanece retirado)

| ADR | Título | Decide | Urgencia |
|---|---|---|---|
| 0014 | Línea base de versiones (Boot, Spring AI, Java, quitar JJWT) | F16 | Antes del primer commit de código |
| 0015 | Verificación de límites modulares (Spring Modulith y/o ArchUnit) | Automatiza la regla de dependencia del DoD | Sprint 0 |
| 0016 | Entrega de eventos: outbox transaccional + consumidores idempotentes | F9 | Antes de `matching` |
| 0017 | Matching v2: pipeline en 3 etapas, calibración, datos faltantes, frescura | F2, F7, F8 | Antes de `matching` |
| 0018 | Modelo de embeddings, versionado y bake-off en español | F5 | Antes de `matching` |
| 0019 | Taxonomía de skills (ESCO) y normalización | F4 | Antes de `cv`/`matching` |
| 0020 | Superficies de autenticación: sesión+CSRF (SSR) vs Bearer (API) | F10 | Sprint 1 |
| 0021 | Almacenamiento de archivos y escaneo de malware | integration §6 | Antes de CV upload |
| 0022 | Notificaciones (in-app + email) | F14 | Antes de `tracker` |
| 0023 | Búsqueda: PostgreSQL FTS + pg_trgm + híbrida con vector | gap | Antes de `job` listados |
| 0024 | Organizaciones/equipos y propiedad de recursos | F15 | Esquema en Ola 1 |
| 0025 | IA responsable: equidad, minimización, controles anti-scraping | F1, D3 | Antes de sugerencias |
| 0026 | Topología de despliegue, hospedaje de Ollama, backup/DR | F11 | Antes de staging |

---

## 6. Qué se verificó y con qué confianza

- **Alta confianza** (leído directamente): los 35 documentos de `docs/`, las reglas del proyecto, `pom.xml`, `docker-compose.yml`, `.env.example`, estructura del repo, la documentación de estado de Clavaris.
- **Confianza media** (fuentes convergentes): fechas de Spring Boot, enmienda del EU AI Act (deadline de reclutamiento movido de ago-2026 a 2-dic-2027 vía *Digital Omnibus*; las fuentes discrepan en la fecha exacta de entrada en vigor), pgvector 0.8 (iterative scan), estado del proyecto de ley de datos de Guatemala (en comisión; los números de iniciativa varían por fuente).
- **Confianza baja / afirmaciones de proveedor**: funciones de LinkedIn Hiring Assistant 2, Workable, Greenhouse, Lever, Ashby (blogs de comparación, algunos de competidores directos), métricas de Indeed (autorreportadas), Bumeran Smart Process, cifras de Torre (marketing propio). Computrabajo, Glassdoor y GetOnBoard: **no se obtuvo fuente oficial verificable** en esta pasada; lo que se dice de GetOnBoard proviene de los propios documentos del repo.
- **No verificado**: velocidades de Ollama en el hardware objetivo (marcadas como *supuestos a medir*), calidad en español de cada modelo de embeddings sobre datos propios, términos de licencia de Lightcast.
- Detalle de fuentes: `04-seguridad-cumplimiento-calidad-riesgos.md` §10.
