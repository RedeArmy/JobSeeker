# 01 — Panorama competitivo y análisis de requerimientos

| Campo | Valor |
|---|---|
| **Estado** | 🟡 En revisión |
| **Versión** | 1.0 · 13-nov-2026 |
| **Relacionado** | `00-resumen-ejecutivo.md`, `docs/00-vision/market-research-and-competitor-analysis.md`, `docs/01-product/prd-mvp.md`, `docs/01-product/nfr-quality-attributes.md` |

> Convención de confianza: **A** = fuente primaria o convergente · **B** = fuentes secundarias coherentes · **C** = afirmación de proveedor/marketing, o fuente única/competidor directo · **N/V** = no verificado en esta pasada.

---

## 1. Qué hace el mercado en 2026 (investigación)

### 1.1 Plataformas analizadas

| Plataforma | Capacidad relevante observada | Lado que sirve | Conf. | Lectura para JobSeeker |
|---|---|---|---|---|
| **LinkedIn Hiring Assistant (v1/v2)** | Agente que traduce los requisitos del reclutador en varias búsquedas, evalúa perfiles, arma *shortlist* **con explicación de por qué cada candidato coincide**; matching semántico con embeddings + re-ranking que optimiza engagement; v2 reporta aprendizaje de patrones del reclutador (ubicación, industria, seniority); alcance a más regiones | Reclutador | B/C (cobertura secundaria; una fuente es un competidor) | La **explicación por candidato** ya es estándar de facto → JobSeeker debe igualarla *y* extenderla al candidato (D1). No competir en agentes autónomos |
| **Torre.ai** | "Professional genome"; matching por skills normalizadas: similitud 1 si coincide exacto tras normalizar, y por **grafo de skills** (cifras propias: >130 000 skills, >2 M relaciones) para relacionadas; incluye expectativa salarial, ubicación, idiomas, preferencias; componente de *recommendation weight* (confiabilidad del perfil/referencias); API para empresas; servicios gestionados (Torre Hunt) | Ambos | B (docs públicas del modelo) / C (cifras) | Confirma que **normalización de skills + grafo** es el corazón del matching preciso (refuerza F4). La fórmula final →% **no es pública**: no hay nada que copiar; diseñar la propia con calibración |
| **Indeed Smart Screening / Smart Fit Score** | Filtra y puntúa candidatos contra criterios del empleador *antes* del ATS; cuestionarios IA embebidos en *Indeed Apply*; en México: puntuación de ajuste + resumen del candidato. Cifras de eficiencia (−20 % tiempo de contratación, −54 % solicitudes por contratación) son **autorreportadas** | Reclutador | B/C | Valida el patrón "knockout/criterios + puntaje" y **preguntas de postulación** (ya diseñadas). El puntaje es opaco para el candidato |
| **Workable (AI Recruiting Agent)** | Agente que busca en base de >400 M perfiles, puntúa y rankea postulantes contra la descripción; precio por **créditos** (cada screening cuesta créditos) | Reclutador | C | Muestra que el costo marginal de IA es un tema de negocio: el enfoque local (Ollama) es ventaja de costo |
| **Greenhouse / Lever / Ashby** | Greenhouse: scorecards, sugerencias de scorecard por JD, ranking explicable (fuentes discrepan); Lever: screening IA, resúmenes de entrevistas, señales anti-fraude, CRM de candidatos; Ashby: analítica de pipeline; varios exponen el ATS a asistentes externos vía **MCP** | Reclutador | C (blogs comparativos) | Táctica: **scorecards, motivos de rechazo, analítica y API/MCP** son diferenciadores de ATS maduros. MCP/API abierta = oportunidad barata post-Ola 2 |
| **Manatal** | ATS + scoring con IA para agencias; precio de entrada bajo | Reclutador (agencias) | B/C | Competidor más cercano en el nicho *agencias/outsourcing*. Su flanco débil (según el documento de mercado del repo): sin CV de marca y scoring solo del lado reclutador |
| **Bumeran "Smart Process"** | Ayuda al reclutador a analizar, organizar y priorizar candidatos; meta declarada de −40 % tiempo | Reclutador | B/C | Competidor regional directo con IA; sin evidencia de explicabilidad al candidato |
| **Computrabajo / OCC / Glassdoor** | Sin fuente oficial verificable de matching IA en esta pasada (un proveedor de software menciona "AI-Match" con "−75 % de revisión") | — | **N/V** | Mantener la hipótesis del documento de mercado, pero **marcarla como pendiente de verificación**; prueba de usuario de campo con la cohorte USAC es más valiosa que más búsqueda web |
| **GetOnBoard / Wellfound** | Elegibilidad por "excepciones" (GetOnBoard) y formularios — origen del diseño BR-ELIG | Ambos | **N/V** (solo documentos del repo) | Ya incorporado al dominio |

### 1.2 Tendencias transversales

| Tendencia | Evidencia | Implicación |
|---|---|---|
| **IA con explicación "por qué coincide"** pasa de diferenciador a requisito | LinkedIn, Indeed (resumen + puntaje), Greenhouse | BR-MATCH-02 está bien alineado; falta la forma **accionable** (D1) |
| **Agentes** (sourcing, outreach, scheduling) | LinkedIn HA2, Workable, Lever | Fuera de alcance realista; anotar como "no-objetivo explícito" en PRD §4 para evitar scope creep |
| **Interoperabilidad (MCP/API)** | Greenhouse, Ashby, Workable, Pinpoint conectan a asistentes externos | API versionada ya está en el diseño (`/api/v1`); publicar OpenAPI público es casi gratis (ADR-0010) |
| **Ghosting / *ghost jobs*** | Encuesta 2026 reporta *ghost jobs* afectando a ~67 % de candidatos; ghosting post-entrevista 61 % (blogs; verificar metodología) | D2: señales de actividad, SLA, cierre automático por inactividad |
| **Regulación de IA en reclutamiento** | EU AI Act (reclutamiento = alto riesgo; plazo movido a 2-dic-2027 por *Digital Omnibus*, fuentes discrepan en el detalle); NYC LL144 (auditoría de sesgo anual, vigente desde jul-2023); Colorado (norma reescrita; una fuente reporta obligaciones desde 1-ene-2027) | D3: diseñar *como si* aplicara; es contribución académica y ventaja comercial |
| **Sesgo en matching con embeddings** | Wilson & Caliskan (AIES 2024): modelos de embeddings favorecieron nombres asociados a blancos en ~85 % de casos y a mujeres en ~11 %; afectaron más a hombres negros | Redactar nombre/foto/pronombres antes de embeber + prueba contrafactual en CI (`02` §4.3) |

---

## 2. Matriz de capacidades: JobSeeker (diseñado) vs. mercado

Leyenda: ✅ diseñado · 🟡 parcial/ambiguo · ❌ ausente en el diseño · ➖ no aplica/no perseguir.

### 2.1 Lado candidato

| Capacidad | Mercado (típico) | JobSeeker hoy | Brecha |
|---|---|---|---|
| Perfil estructurado + carga de CV | Estándar | ✅ (parsing sin especificar) | Elegir parser (Tika/PDFBox + confirmación del candidato); OCR para escaneados |
| Score de compatibilidad por vacante | Torre, Indeed (MX) | ✅ explicable | Falta calibración (F2) |
| **Qué-pasaría-si / hoja de ruta de brecha** | Casi nadie | ❌ | **D1** — requisito nuevo REQ-C-01 |
| Alertas de vacantes / búsquedas guardadas | Estándar | ❌ | REQ-C-02 |
| Búsqueda y filtros de vacantes | Estándar | 🟡 (se menciona, no se diseña) | ADR-0023 |
| Postulación rápida con prellenado | Estándar | ✅ (BR-APPQ-05) | — |
| Retirar postulación | Estándar | ❌ | REQ-C-03 |
| Seguimiento de estado + notificaciones | Estándar | 🟡 (sin canal) | ADR-0022 |
| Feedback/motivo de rechazo al candidato | Greenhouse/Lever (opcional) | ❌ | REQ-C-04 (motivo codificado; D2/D3) |
| Control de privacidad (descubribilidad, exclusiones, exportar/borrar) | LinkedIn | ✅ | Falta **exportación de datos** (portabilidad) REQ-C-05 |
| Notificación "tu perfil apareció en N búsquedas" | LinkedIn | 🟡 (pregunta abierta) | Recomendado sí (resuelve BR-DATA-03 opt-out ético) |
| Múltiples versiones de CV | Estándar | ❌ | Post-MVP |

### 2.2 Lado reclutador

| Capacidad | Mercado | JobSeeker hoy | Brecha |
|---|---|---|---|
| Ciclo de vida de vacante + programación | LinkedIn | ✅ (muy completo) | — |
| Pipeline ordenado por score + desglose | Manatal, Workable | ✅ | — |
| **Etapas de pipeline configurables** | Estándar (Greenhouse/Lever) | ❌ (`applications.status` enum) | REQ-R-01 |
| Notas/comentarios internos y menciones | Estándar | ❌ | REQ-R-02 |
| **Motivo de rechazo codificado** | Estándar | ❌ | REQ-R-03 (también habilita auditoría de equidad) |
| Scorecards de evaluación | Greenhouse | ❌ | REQ-R-04 (Ola 3+) |
| Acciones masivas (mover/rechazar/mensaje) | Estándar | ❌ | REQ-R-05 |
| Plantillas de email / mensajería al candidato | Estándar | ❌ (fuera de alcance explícito) | Mínimo: plantillas de notificación por cambio de etapa (REQ-R-06) |
| Equipos / colaboración / roles dentro de agencia | Estándar | ❌ | REQ-R-07 (ADR-0024) |
| Búsqueda/ sugerencia de talento con explicación | LinkedIn Recruiter | ✅ | Controles anti-scraping (F1) |
| Talent pools / búsquedas guardadas | Lever/Manatal | ❌ | REQ-R-08 (Ola 3) |
| Analítica del embudo (tiempo en etapa, fuentes) | Ashby | ❌ | REQ-R-09 (Ola 3) |
| CV de marca para reventa | — | ✅ **único** | D5 |
| Verificación de reclutador/empresa | LinkedIn, Indeed | ❌ | REQ-R-10 (F1) |
| Exportación/API/MCP | Greenhouse/Ashby | 🟡 (API versionada) | Documentar API pública; MCP post-Ola 2 |
| Deduplicación de candidatos | Estándar | 🟡 (BR-TRACK-01 solo misma vacante) | Mismo candidato = una cuenta (Clavaris); sin trabajo extra |
| Calendario/entrevistas | Estándar | ➖ | No perseguir (declarar no-objetivo) |

---

## 3. Auditoría de requerimientos existentes

### 3.1 Funcionales (PRD §7)

| Aspecto | Evaluación |
|---|---|
| Trazabilidad | **Excelente**: BR-IDs enlazados a casos, eventos y esquema |
| Cobertura de flujos felices | Muy buena. Flujos de **error/borde** (re-embedding falla, archivo infectado, reclutador suspendido a mitad de vacante) casi no están especificados |
| Ambigüedades abiertas con impacto en el diseño | Sí — ver `04` §9 (se recomienda una decisión por defecto para cada una) |
| Requisitos implícitos no escritos | Notificaciones, búsqueda, archivos, sesión web/CSRF, auditoría, moderación/abuso, soporte (¿cómo reporta un usuario un abuso?), consentimiento/Términos en registro (parcialmente en Ola 1 "Cumplimiento") |
| Conflictos entre reglas | (1) BR-DATA-03 opt-out vs. privacidad por defecto; (2) BR-MATCH-03 "nunca obsoleto" vs. realidad asíncrona → definir SLO de frescura; (3) BR-ELIG-08 *fail-closed* vs. completitud de perfil → mostrar al candidato qué completar *antes* de intentar postular (ya parcialmente en el rechazo); (4) BR-ACCT-01 habilitar `RECRUITER` sin verificación vs. BR-VIS-04 exposición de PII |

### 3.2 No funcionales (NFR doc)

| Atributo | Estado | Brecha / corrección |
|---|---|---|
| Rendimiento | Escenarios claros | **Sin percentil** (usar p95/p99 explícitos); NFR-PERF-01 mezcla caché y recomputo — separar SLO de lectura vs. SLO de frescura |
| Escalabilidad | 100 usuarios / 10 000 perfiles / 1 000 vacantes | Correcta para validación; **sin escenario de crecimiento** (10× y 100×) ni punto de ruptura conocido (ver `02` §8) |
| Disponibilidad | 99 % (≈ 7,2 h/mes de presupuesto de error) | **Sin RTO/RPO**, sin restauración probada, sin DR (F11) |
| Seguridad | NFR-SEC-01..07 | NFR-SEC-07 (login) pasa a Clavaris; faltan CSRF/sesión, cabeceras (CSP), escaneo de dependencias/SBOM, secretos rotables, pruebas de autorización automatizadas |
| Usabilidad | SUS ≥ 72, comprensión del score | **Accesibilidad (WCAG 2.2 AA) ausente**; HTMX requiere degradación progresiva y manejo de foco/ARIA live |
| Mantenibilidad | Cobertura ≥ 80 %, linter | Añadir verificación automática de límites hexagonales/modulares (ADR-0015) y mutation testing en dominio |
| Observabilidad | Muy buena | Falta **analítica de producto** para las métricas de tesis (NPS, SUS, adopción a 30 días: ¿qué es "usuario activo"?) y SLIs de la cola de recálculo |
| Privacidad/retención | BR-DATA-01..06 | Sin política de retención de logs, histórico de scores, vistas de perfil, archivos |
| Costo | Ausente | NFR de costo mensual objetivo (hosting + GPU/CPU) — decisivo para sostener el producto tras la tesis |
| Equidad/IA responsable | Ausente | NFR nuevo: disparidad contrafactual ≤ umbral en CI (ver §6.3) |

---

## 4. Requerimientos nuevos o modificados (propuesta)

Prioridad MoSCoW. "Ola" = dónde encajar. Esfuerzo en sprints (orden de magnitud, un desarrollador).

### 4.1 Candidato

| ID | Requerimiento | Prio | Ola | Esf. | Razón |
|---|---|---|---|---|---|
| REQ-C-01 | **Explicación accionable**: el desglose incluye, por dimensión, "qué te falta" y un cálculo "qué-pasaría-si" (p. ej. agregar skill X o declarar idioma Y) con delta estimado; solo sobre datos que el candidato puede declarar verazmente | Should | 1 (básico) / 2 | 1 | D1; reusa `ScoreBreakdown` |
| REQ-C-02 | Alertas de vacantes: búsquedas guardadas con envío en digest (email/in-app), tope de frecuencia | Should | 2 | 1 | Retención (meta 60 % a 30 días) |
| REQ-C-03 | Retirar una postulación (estado `WITHDRAWN`), respetando BR-TRACK-01 (permite repostular solo si la vacante lo permite) | Must | 1 | 0.2 | Derecho básico; evita zombis en pipeline |
| REQ-C-04 | Recepción de motivo de cierre codificado cuando el reclutador rechaza (plantilla neutra, opcional por vacante) | Should | 2 | 0.5 | D2/D3 |
| REQ-C-05 | Exportación de datos propios (JSON/PDF) y registro de consentimientos | Must | 1 (Cumplimiento) | 0.5 | Portabilidad; prepara ley GT/GDPR |
| REQ-C-06 | Resumen periódico de visibilidad: "tu perfil apareció en N sugerencias" (sin identificar al reclutador salvo si contacta) | Should | 2 | 0.3 | Corrige el sesgo ético del opt-out (BR-DATA-03) |
| REQ-C-07 | **Solicitud de contacto**: el reclutador no ve email/teléfono de un sugerido no postulado; envía una solicitud que el candidato acepta/ignora | Must | 1–2 | 0.7 | Resuelve la pregunta abierta de STRIDE §5; reduce valor del scraping |

### 4.2 Reclutador / organización

| ID | Requerimiento | Prio | Ola | Esf. |
|---|---|---|---|---|
| REQ-R-01 | Etapas de pipeline configurables por vacante/organización (plantilla por defecto: Nuevo → Revisión → Entrevista → Oferta → Contratado/Rechazado/Retirado) | Must | 2 | 1 |
| REQ-R-02 | Notas internas y menciones por postulación | Should | 2 | 0.7 |
| REQ-R-03 | Motivo de rechazo codificado + nota interna (habilita auditoría de equidad) | Must | 2 | 0.5 |
| REQ-R-04 | Scorecards con criterios y calificación por entrevistador | Could | 3+ | 1.5 |
| REQ-R-05 | Acciones masivas (mover/rechazar/enviar notificación) con tope y confirmación | Should | 2 | 0.7 |
| REQ-R-06 | Plantillas de notificación por cambio de etapa (editable por reclutador, plantilla por defecto neutra) | Should | 2 | 0.5 |
| REQ-R-07 | **Organización/equipo**: miembros con rol (propietario/reclutador/lector), vacantes y plantillas de marca por organización, exclusiones por organización (resuelve la limitación BR-CAND-05) | Should | 2–3 (esquema en 1) | 2 |
| REQ-R-08 | Búsquedas guardadas y *talent pools* | Could | 3 | 1 |
| REQ-R-09 | Analítica del embudo (tiempo por etapa, conversión, origen: postulado/sugerido/invitado) | Could | 3 | 1 |
| REQ-R-10 | **Verificación de reclutador**: email corporativo verificado (no dominios gratuitos) o validación manual; estado `UNVERIFIED/VERIFIED`; sin verificación: sin sugerencias y máx. N vacantes | Must | 1 | 0.5 |
| REQ-R-11 | Auditoría de vistas de perfil por reclutador + cuota diaria de perfiles únicos vistos vía sugerencias | Must | 1 | 0.5 |
| REQ-R-12 | Cierre/aviso automático de vacantes `PUBLISHED` sin actividad del reclutador durante N días (resuelve preguntas abiertas PRD §9: recordatorio y expiración de `HIRING`) | Should | 3 | 0.3 |
| REQ-R-13 | Feedback sobre coincidencias (👍/👎 + motivo) que alimenta el conjunto de calibración | Should | 1–2 | 0.5 |

### 4.3 Plataforma / transversales

| ID | Requerimiento | Prio | Ola |
|---|---|---|---|
| REQ-P-01 | Notificaciones in-app + email con outbox, preferencias y *digest* (ADR-0022) | Must | 1 |
| REQ-P-02 | Almacenamiento de archivos (Supabase Storage S3-compatible; MinIO en dev), URLs firmadas de corta vida, escaneo ClamAV, límite de tamaño/MIME (ADR-0021) | Must | 1 |
| REQ-P-03 | Búsqueda de vacantes: `tsvector` con configuración `spanish` + `pg_trgm` + fusión con similitud vectorial (RRF) (ADR-0023) | Must | 1 |
| REQ-P-04 | Auditoría inmutable (quién/qué/cuándo) de acciones sensibles: invitaciones, transiciones, vistas de PII, borrados | Must | 1 |
| REQ-P-05 | Moderación y denuncia de abuso (vacante fraudulenta, perfil falso) con cola de revisión mínima | Should | 2 |
| REQ-P-06 | Accesibilidad WCAG 2.2 AA verificada en CI (axe) para flujos críticos | Must | 1 |
| REQ-P-07 | Analítica de producto auto-hospedada (eventos mínimos sin PII) y encuestas SUS/NPS in-app | Must | 1 |
| REQ-P-08 | Consentimiento versionado (Términos/Privacidad aceptados + fecha/versión) | Must | 1 |
| REQ-P-09 | OpenAPI público documentado y estable (`/api/v1`), con claves de API opcionales post-Ola 2 | Could | 3 |
| REQ-P-10 | Feature flags (decidir librería: Spring profiles son insuficientes para flags runtime; recomendar **FF4J** o tabla propia `feature_flags` simple + caché) | Should | 1 |

### 4.4 No-objetivos a declarar explícitamente (evitan scope creep)

Agentes autónomos de sourcing/outreach, programación de entrevistas, video/voz, marketplace de reclutadores externos, evaluaciones psicométricas, scraping de otras fuentes, app móvil nativa (PRD ya cubre varios).

---

## 5. Priorización y trade-offs (qué se paga y qué se gana)

| Decisión | Se gana | Se paga | Recomendación |
|---|---|---|---|
| Añadir REQ-C-01 (qué-pasaría-si) | D1, utilidad real para candidato USAC junior | ~1 sprint | **Sí**, versión mínima en Ola 1 (brecha textual), delta numérico en Ola 2 |
| Añadir REQ-R-07 (organizaciones) en Ola 1 completa | Evita retrabajo | ~2 sprints antes de tener usuarios | **No completo**: solo columna `organization_id` nullable + puerto `OwnershipPolicy` (§`03` §6) |
| Verificación de reclutador manual | Seguridad PII | Operación humana (el desarrollador) | Heurística automática (dominio no gratuito) + aprobación manual solo si la cola es pequeña |
| Contacto enmascarado hasta aceptación | Privacidad, anti-scraping | Un paso más para el reclutador | **Sí** — alineado con LinkedIn InMail como patrón conocido |
| Diferir scorecards/analítica | Foco | Menor paridad con Greenhouse | Diferir; no son diferenciadores de la tesis |

---

## 6. Plan de evaluación del matching (convertir métricas en evidencia)

### 6.1 Por qué hace falta

"Precisión ≥ 0.85" no define: (a) ¿precisión de qué?, (b) ¿contra qué verdad-terreno?, (c) ¿con qué muestra? Sin esto el resultado de la tesis es refutable.

### 6.2 Protocolo propuesto

| Elemento | Propuesta |
|---|---|
| **Unidad de evaluación** | Par (candidato, vacante) + decisión humana de relevancia en escala ordinal 0–3 (no apto / parcial / bueno / excelente) |
| **Métrica primaria** | *Precision@K* de las K=10 sugerencias por vacante, considerando relevante ≥ 2; **NDCG@10** como secundaria (sensible al orden) |
| **Definición de "0.85"** | P@10 ≥ 0.85 sobre el conjunto de prueba retenido (*held-out*); reportar IC 95 % |
| **Etiquetadores** | ≥ 3 personas con experiencia de contratación (reclutadores/ docentes), doble etiquetado; **κ de Cohen/Fleiss ≥ 0.6** como condición de validez de las etiquetas |
| **Tamaño de muestra** | IC 95 % de una proporción p=0.85: ±7 pts con n=100, ±4 pts con n=300, ±3 pts con n=500 → **objetivo mínimo n=300 pares etiquetados** (≥ 30 vacantes × 10) |
| **Particiones** | 60 % calibración (pesos, temperatura de la sigmoide), 20 % validación (selección de modelo/α), 20 % prueba **intocable** hasta el reporte final |
| **Líneas base** | (i) BM25/coincidencia de keywords; (ii) solo semántica; (iii) solo estructurada; (iv) híbrido calibrado. Demuestra ADR-0003 empíricamente |
| **Ablación** | Con/sin normalización de skills; con/sin redacción de PII; modelos de embeddings candidatos (ADR-0018) |
| **Datos** | Reales (cohorte USAC con consentimiento) + sintéticos *solo* para pruebas de equidad y carga, nunca para la métrica de precisión |
| **Regresión** | El dataset dorado corre en CI nocturno; un cambio de `algorithm_version` no se promueve si P@10 o NDCG@10 caen > 2 pts o si falla la prueba contrafactual |

### 6.3 Equidad (prueba contrafactual)

Para cada par (CV, vacante) del conjunto de prueba, generar variantes que cambien **solo** señales protegidas o proxy (nombre asociado a género/etnia, foto, pronombres, universidad pública vs. privada, nombre de ciudad). Medir `Δscore` y `Δrank`. **Criterio de aceptación**: |media Δscore| ≤ 1 punto y ninguna dirección significativa (prueba pareada, α=0.01). Es defensa directa contra el sesgo documentado para embeddings; además aporta un entregable replicable a la tesis.

### 6.4 Métricas de adopción/usabilidad: rigor estadístico

- **NPS ≥ 40**: con n=30, ±26 pts (95 %); con n=100, ±14 pts. Reportar siempre con IC y n; no concluir "cumplido" si el IC incluye < 40.
- **SUS ≥ 72**: n≥20 es lo habitual (la SUS converge rápido), pero reportar desviación.
- **Adopción 30 días ≥ 60 %**: definir "activo" (p. ej. ≥ 1 sesión con acción sustantiva: postular, editar perfil, mover candidato) y el denominador (registrados con onboarding completo). Instrumentar desde el día 1 (REQ-P-07).
