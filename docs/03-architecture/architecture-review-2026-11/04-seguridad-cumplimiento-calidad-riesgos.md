# 04 — Seguridad, cumplimiento, calidad, riesgos y defectos documentales

| Campo | Valor |
|---|---|
| **Estado** | 🟡 En revisión |
| **Versión** | 1.0 · 13-nov-2026 |
| **Relacionado** | `docs/04-security/*`, `docs/01-product/nfr-quality-attributes.md`, `00-resumen-ejecutivo.md` |

---

## 1. Seguridad de aplicación: lo que falta por ser un frontend SSR

La documentación modela el acceso como "JWT Bearer desde el navegador". Para una app **Thymeleaf + HTMX** (ADR-0005) eso no es lo que ocurre: el navegador navega con cookies. Hay que diseñar **dos superficies** (ADR-0020):

| Superficie | Rutas | Autenticación | Protecciones obligatorias |
|---|---|---|---|
| **Web SSR/HTMX** | `/app/**` | Spring Security **OAuth2 Login** contra Clavaris (Authorization Code + PKCE); sesión del lado del servidor | Cookie `__Host-` `HttpOnly; Secure; SameSite=Lax`; rotación del ID de sesión al autenticar; **CSRF** activo (token enviado a HTMX vía `hx-headers`); sesión en Redis (Spring Session) con expiración inactiva ≤ 30 min y absoluta ≤ 8 h; logout RP-initiated (+ back-channel si Clavaris lo soporta) |
| **API JSON** | `/api/v1/**` | **Resource Server** (Bearer RS256 validado por JWKS de Clavaris) | Sin cookies ⇒ CSRF no aplica; CORS **cerrado** por defecto; clientes futuros (móvil, integraciones) con scopes |

Otros controles que el documento de seguridad no cubre:

| Control | Detalle |
|---|---|
| **Cabeceras** | CSP estricta (sin `unsafe-inline`; configurar HTMX sin `eval` y sin estilos inline), `Strict-Transport-Security`, `X-Content-Type-Options`, `Referrer-Policy`, `Permissions-Policy`, `frame-ancestors 'none'` |
| **XSS en Thymeleaf** | Usar solo `th:text` (escape por defecto); prohibir `th:utext` con contenido de usuario; descripciones de vacante en texto/markdown saneado (OWASP Java HTML Sanitizer) |
| **Inyección** | JPA/consultas parametrizadas; revisar cualquier SQL nativo (consultas `pgvector`, FTS) |
| **Subida de archivos** | Validación por *magic bytes*, tamaño, escaneo ClamAV, nombre aleatorio, almacenamiento fuera del *webroot*, descarga solo vía URL firmada de vida corta, `Content-Disposition: attachment`, sin ejecución de contenido |
| **SSRF** | URLs de usuario (`credential_url`, `projects.url`, `profile_photo_url`) **nunca** se descargan en servidor; si se genera miniatura o preview, usar *fetcher* con lista de denegación de redes internas |
| **Generación de PDF** (openhtmltopdf) | Plantillas de reclutador son contenido controlado por usuario ⇒ riesgo de SSRF/lectura de archivos vía `<img src>`/`@font-face`/CSS `url()`: **deshabilitar resolución de recursos externos y `file:`**, permitir solo activos subidos y referenciados por ID, límite de tiempo/memoria por render, ejecutar en `ExecutorService` acotado |
| **Plantillas de marca** | `branding_config` jsonb validado contra esquema cerrado (colores, logo por ID, secciones); **nunca** HTML libre del reclutador |
| **Deserialización/jsonb** | `criteria` y `answer_value` → tipos cerrados (LLD §3.2), tamaño máximo, profundidad máxima |
| **Enumeración** | 404 (no 403) para recursos ajenos (ya en el contrato); mismos tiempos de respuesta para existencia/no existencia |
| **Secretos** | Rotación documentada; `CLAUDE_API_KEY` solo cargada si el *flag* del adaptador externo está activo; escaneo de secretos en CI (gitleaks) |
| **Cadena de suministro** | Dependabot/Renovate, OWASP Dependency-Check, firma/escaneo de imagen (Trivy), SBOM CycloneDX, GitHub Actions con permisos mínimos y *pin* por SHA |
| **Observabilidad segura** | Prometheus/Grafana/Alertmanager **no expuestos** a Internet (red interna + túnel/VPN o auth); `/actuator/*` solo `health` y `prometheus` en red interna |
| **Swagger UI** | Autenticada en producción (ya decidido) |

## 2. Amenazas nuevas o reevaluadas (adiciones a STRIDE)

| ID | Categoría | Amenaza | Prob. | Imp. | Prio | Mitigación |
|---|---|---|---|---|---|---|
| T-N1 | Info. Disclosure / DoS | **Reclutador falso** habilita `RECRUITER`, publica una vacante señuelo y raspa PII de candidatos descubribles | **Alta** | Alto | **Crítica** | REQ-R-10 (verificación), cuotas (REQ-R-11), contacto enmascarado (REQ-C-07), vacante `PUBLISHED` ≥ 24 h, alerta por patrón, auditoría |
| T-N2 | Tampering | Candidato **modifica su perfil después de postular** para inflar su puntaje | Media | Medio | Alta | Snapshot inmutable (LLD §4.1) |
| T-N3 | Info. Disclosure | **Sesgo/discriminación algorítmica** (nombres, institución, ciudad como proxy) | Media | Alto (reputacional/legal) | Alta | Canonicalización sin identificadores, prueba contrafactual en CI, motivos de rechazo codificados, **sin penalización por sobrecalificación** |
| T-N4 | Elevation | **CSRF** sobre acciones HTMX (aplicar, invitar, cambiar etapa) | Media | Alto | Alta | CSRF activo + SameSite=Lax |
| T-N5 | Tampering | Plantilla de CV de marca con recursos externos → SSRF/exfiltración al renderizar | Media | Alto | Alta | Ver §1 |
| T-N6 | Repudiation | Disputa sobre qué se mostró al reclutador (qué versión del perfil/score) | Media | Medio | Media | Snapshots + `audit_log` + `profile_view_log` |
| T-N7 | DoS | **Cola de recálculo inundada** por ediciones repetidas/automatizadas | Media | Medio | Media | Debounce, cuota por cuenta, límite de tamaño de texto canónico |
| T-N8 | Info. Disclosure | **PII en prompts a LLM externo** (Claude API) | Baja | Alto | Media | Flag, canonicalización sin identificadores, contrato de datos con el proveedor, registro de cada envío (sin contenido) |
| T-N9 | Spoofing | **Confusión de cuentas multi-rol** al cambiar de vista en la UI | Baja | Alto | Media | Contexto de capacidad **explícito en cada caso de uso**, no en estado de sesión de UI |
| T-N10 | Info. Disclosure | Archivos de respuestas/CV accesibles por URL adivinable | Baja | Alto | Media | Claves aleatorias + URL firmada ≤ 5 min |
| T-N11 | DoS | *Prompt/embedding poisoning*: perfiles que insertan texto oculto/repetido para inflar similitud (keyword stuffing, texto en blanco, instrucciones) | Media | Medio | Media | Canonicalización limita campos y longitud; deduplicación de tokens; se **embebe texto estructurado**, nunca el PDF crudo |
| T-N12 | Tampering | Edición concurrente de la vacante por varios miembros de una organización | Baja | Bajo | Baja | `@Version` + 409 |

## 3. Privacidad: inventario de PII y alcance real de la anonimización (F13)

BR-DATA-05/ADR-0013 anonimizan perfil y filas hijas, pero hay PII que el diseño actual **no cubre**. Inventario completo propuesto:

| Dato | Dónde vive | Tratamiento al anonimizar | ¿Cubierto hoy? |
|---|---|---|---|
| Nombre, titular, bio, foto | `professional_profiles` | Placeholder / `NULL`; borrar archivo de foto | ✅ (foto: archivo no mencionado) |
| Experiencia/formación/certificaciones/proyectos | tablas hijas | Borrado físico | ✅ |
| Email/teléfono de contacto, texto de CV, embedding | `candidate_profiles` | `NULL` | ✅ |
| **Archivo de CV original** | Storage | Borrado físico del objeto | ❌ |
| **Embedding de la vacante propia** (reclutador) | `job_postings` | Se conserva (no es PII del candidato) | n/a |
| `application_answers.answer_value` (texto libre, puede contener PII) | `ats` | **Redactar** (`NULL`/"[eliminado]") para preguntas `TEXT`; conservar solo respuestas estructuradas no identificantes | ❌ |
| **Archivos `FILE_UPLOAD`** (cover letter, etc.) | Storage | Borrado físico | ❌ |
| `application_follow_up_items.answer_value` | `ats` | Igual que `answer_value` | ❌ |
| **`application_profile_snapshots`** (nuevo) | `ats` | Reducir a datos no identificantes (skills normalizadas, años, nivel) o borrar; el score ya persistido conserva el desglose | Nuevo |
| **`compatibility_score_snapshots.breakdown`** (evidencias pueden citar texto) | `matching` | El desglose referencia *skill_id*/categorías, **no** texto libre; revisar que `Evidence` no almacene fragmentos de CV | Nuevo |
| PDFs de CV de marca ya generados (`branded_cv_exports`) | Storage | Borrado físico y `expires_at` por defecto | ❌ |
| `notifications` y correos enviados | BD / proveedor de email | Borrar/anonimizar; solicitar supresión al proveedor | ❌ |
| `profile_view_log`, `audit_log` | BD | Conservar con `accountId` seudonimizado (interés legítimo de seguridad); sin contenido | Nuevo |
| Logs de aplicación/Prometheus | Infra | Sin PII por diseño (BR-DATA-01); retención ≤ 30 d | ✅ por diseño |
| Identidad en Clavaris | Clavaris | API admin, cascada asíncrona con reintento | ✅ (diseño) |
| **Copias de seguridad** | Supabase/`pg_dump` | Documentar que los respaldos conservan el dato hasta rotar (≤ 35 d típico, verificar plan); reaplicar anonimización tras una restauración | ❌ |

**Coordinación (cierra la pregunta "¿en qué módulo vive?")**: `auth-module` **orquesta** el ciclo de vida de la cuenta (es dueño de la relación con Clavaris) y publica `AccountAnonymizationRequested`; **cada módulo** implementa un puerto `AnonymizePersonalData` para *sus* datos (`cv`, `ats`, `tracker`, `matching`, `job`); `auth` marca la cuenta `ANONYMIZED` solo cuando todos confirman (saga basada en outbox). Así ningún módulo toca tablas ajenas.

**Descubribilidad por defecto (BR-DATA-03).** Es una decisión de negocio legítima, pero ética y legalmente frágil: *privacy by default* sugeriría opt-in. Mitigación propuesta, sin cambiar la regla: (1) **elección explícita en el onboarding** (el valor por defecto se muestra y se puede cambiar antes de continuar; se registra el consentimiento con versión/fecha), (2) resumen periódico de visibilidad (REQ-C-06), (3) contacto enmascarado (REQ-C-07).

## 4. Cumplimiento regulatorio y de IA responsable

> Esto **no es asesoría legal**; los borradores de `docs/06-operational/` ya están marcados "no revisados por abogado". Las fechas provienen de fuentes secundarias que en algunos puntos discrepan; verificar contra fuentes oficiales antes de citarlas.

| Marco | Estado (según fuentes consultadas) | ¿Aplica a JobSeeker? | Implicación de diseño |
|---|---|---|---|
| **Guatemala – protección de datos** | No existe ley específica; hay iniciativa en comisión (los números de iniciativa varían por fuente: 6572, 6464) y una ley marco de ciberseguridad (6347) con estado discrepante entre fuentes | Sí (usuarios y operador en GT) | Diseñar a estándar tipo GDPR (minimización, finalidad, consentimiento, acceso/portabilidad/supresión, notificación de brechas) para no rehacer cuando se apruebe. REQ-C-05/P-08 |
| **EU AI Act** | Reclutamiento/selección = **alto riesgo (Anexo III)**. Un acuerdo *Digital Omnibus* aplaza el plazo de sistemas Anexo III de 2-ago-2026 a **2-dic-2027** (aprobación final reportada pero con fechas de publicación discrepantes); las obligaciones de transparencia no se movieron | Solo si hay usuarios en la UE | No aplica hoy, pero sirve de **estándar de diseño académico**: gestión de riesgos, calidad/gobernanza de datos, registro (*logging*), transparencia al usuario, **supervisión humana**, exactitud/robustez, documentación técnica. JobSeeker ya cumple varias por diseño (explicabilidad, decisión final humana, auditoría) |
| **NYC Local Law 144** (AEDT) | Vigente desde jul-2023: auditoría de sesgo anual independiente, aviso a candidatos | No aplica directamente | Su métrica (*impact ratio* por sexo/etnia) es una referencia de **cómo reportar equidad** en la tesis |
| **Colorado (SB 24-205)** | Fuentes discrepan (feb-2026 → jun-2026 → reescrita con obligaciones desde 1-ene-2027, según una sola fuente reciente) | No | Mencionar solo como contexto; no basar decisiones en esta fecha sin verificar |
| **Humano en el bucle** | — | Sí (decisión de diseño) | El score **ordena, no decide**; ningún estado `REJECTED` es automático; copy de UI lo explicita |

**Controles de IA responsable (ADR-0025)**: (1) canonicalización sin identificadores; (2) prueba contrafactual en CI y reporte de equidad en la tesis; (3) *model card* + *dataset datasheet* del matching (propósito, limitaciones, métricas por subgrupo, versiones); (4) registro de versión de algoritmo por score; (5) derecho a explicación (D1) y a corrección de datos; (6) revisión humana obligatoria antes de cualquier acción adversa; (7) política de uso del LLM externo (flag, minimización, retención cero acordada con el proveedor).

## 5. NFR adicionales propuestos

| ID | Atributo | Escenario | Respuesta esperada |
|---|---|---|---|
| NFR-PERF-07 | Frescura | Edición de perfil/vacante | Score actualizado p95 ≤ 90 s / ≤ 60 s |
| NFR-PERF-08 | Búsqueda | Búsqueda de vacantes con filtros | p95 ≤ 1.5 s con 1 000 vacantes |
| NFR-REL-03 | Entrega de eventos | Caída del proceso tras commit | Ningún evento se pierde (outbox); reintento en ≤ 1 min |
| NFR-REL-04 | Idempotencia | Reintento de `POST` crítico | Mismo resultado, sin duplicados |
| NFR-DR-01 | RPO/RTO | Pérdida de BD | RPO ≤ 24 h (o ≤ 15 min con PITR contratado); RTO ≤ 4 h; restauración **probada trimestralmente** |
| NFR-SEC-08 | Sesión web | Petición cross-site | Rechazada por CSRF/SameSite; 100 % de rutas mutantes cubiertas por prueba |
| NFR-SEC-09 | Cadena de suministro | Dependencia con CVE crítica | Detectada en CI; remediación ≤ 7 d |
| NFR-SEC-10 | Anti-scraping | Cuenta consulta > cuota de perfiles únicos/día | Bloqueo + alerta; cuota configurable |
| NFR-PRIV-01 | Retención | Logs, snapshots, vistas, archivos | Política escrita y *job* de purga verificado |
| NFR-PRIV-02 | Derechos del titular | Solicitud de exportación | Entregada ≤ 72 h (automatizada) |
| NFR-A11Y-01 | Accesibilidad | Flujos críticos | WCAG 2.2 AA verificado con axe + revisión manual de teclado/lector de pantalla |
| NFR-FAIR-01 | Equidad | Prueba contrafactual | \|Δscore medio\| ≤ 1 pt; sin diferencia significativa (α=0.01) |
| NFR-EVAL-01 | Calidad del matching | Dataset dorado | P@10 ≥ 0.85 (IC 95 % reportado); NDCG@10 no regresiona > 2 pts por versión |
| NFR-COST-01 | Costo | Operación mensual | ≤ presupuesto definido (hosting + email + almacenamiento); revisión mensual |
| NFR-OBS-06 | Medición de producto | Eventos de uso | Cobertura de eventos clave ≥ 95 %, sin PII, para adopción/NPS/SUS |
| NFR-MAINT-04 | Arquitectura | Cada build | ArchUnit/Modulith sin violaciones |

## 6. Rigor de las métricas de tesis

Ver `01` §6. Resumen operativo: definir **P@10** con etiquetado doble (κ ≥ 0.6) y ≥ 300 pares; reportar IC 95 %; NPS/SUS con n e intervalos (n=30 ⇒ ±26 pts en NPS); definir "usuario activo" antes de recolectar; instrumentación desde el día 1.

## 7. Registro de riesgos actualizado (extiende el SDD §8)

| # | Riesgo | Prob. | Imp. | Mitigación | Disparador de revisión |
|---|---|---|---|---|---|
| R1 | Raspado de PII vía sugerencias (T-N1) | Alta | Alto | §2, REQ-R-10/11, REQ-C-07 | Primer reclutador no verificado con > cuota |
| R2 | Precisión < 0.85 con embeddings no calibrados/en inglés | Media | Alto | ADR-0018/0019, calibración, bake-off, dataset dorado | P@10 < 0.75 al cierre del sprint 8 de matching |
| R3 | Métrica de tesis no demostrable (n pequeño, etiquetas inconsistentes) | Media | Alto | `01` §6, κ ≥ 0.6, n ≥ 300 | κ < 0.6 en la primera ronda |
| R4 | Ola 1 excede duración (≈ +4 sprints ya identificados) | Alta | Medio | Walking skeleton, diferir 1 sprint de ola 1→2, gates de recorte del roadmap §11 | Desviación > 2 sprints en un bloque |
| R5 | Ollama como cuello de botella/SPOF | Media | Medio | Cola + debounce + keep-alive + circuito; embeddings solo | p95 de embed > 3 s sostenido |
| R6 | Producción sin topología/DR definidos | Alta | Alto | ADR-0026; backup + restauración probada | Antes de abrir a usuarios reales |
| R7 | Línea base Spring sin soporte (Boot 3.4) | Alta | Medio | ADR-0014 antes del primer commit | — |
| R8 | Pérdida de eventos → scores obsoletos | Media | Medio | Outbox | Cualquier discrepancia score vs. perfil |
| R9 | Sesgo algorítmico detectado tardíamente | Media | Alto | Prueba contrafactual en CI desde el sprint 1 de matching | Δ > 1 pt |
| R10 | Retrabajo por falta de organizaciones | Media | Medio | `organization_id` nullable + `OwnershipPolicy` | Primer cliente-agencia con >1 reclutador |
| R11 | Erosión hexagonal por presión de tiempo | Media | Medio | ArchUnit/Modulith en CI (bloqueante) | Violación en CI |
| R12 | Anonimización incompleta (F13) → incumplimiento de promesa a usuarios | Media | Alto | Inventario §3, pruebas de borrado de extremo a extremo | Auditoría trimestral |
| R13 | GraalVM Native inviable por dependencias | Alta | Bajo | No comprometer en MVP | — |
| R14 | Dependencia de Clavaris (contrato, no capacidad) | Baja | Medio | Spike Sprint 1; pruebas de contrato | Cambio de scopes/claims |
| R15 | Costo operativo insostenible tras la tesis | Media | Medio | NFR-COST-01, sin GPU por defecto | Presupuesto mensual > umbral |
| R16 | Cumplimiento legal no revisado (políticas en borrador) | Media | Alto | Revisión por abogado antes de usuarios reales | Antes del cierre de Ola 1 |
| R17 | Burnout/único punto de fallo (el desarrollador) | Media | Alto | Alcance acotado, automatización, documentación (ya fuerte), runbooks | Retraso acumulado > 3 sprints |

## 8. Defectos y contradicciones detectados en el repositorio

| # | Dónde | Defecto | Corrección |
|---|---|---|---|
| D1 | `.env.example` | Conserva `JWT_SECRET` HS256 "ADR-0009 … access 15 min / refresh 7 días", ADR retirado | Eliminar; añadir `CLAVARIS_ISSUER_URI`, `CLAVARIS_CLIENT_ID/SECRET`, `STORAGE_*`, `MAIL_*` |
| D2 | `docker-compose.yml` (comentarios) y las reglas del proyecto §3 | "un servicio por módulo, Dockerfile por módulo", "scrape de cada módulo" ⇒ contradice ADR-0001/0010 (un desplegable, un OpenAPI, un `/actuator/prometheus`) | Un servicio `app`; módulos Maven como bibliotecas |
| D3 | `pom.xml` | Sin módulo `app`/bootstrap; sin BOM ni versiones | Crear `app`; fijar versiones (ADR-0014) |
| D4 | las reglas del proyecto §11 | Presenta Clavaris como dependencia no resuelta; el repo hermano ya tiene login funcional | Actualizar: el riesgo es de **contrato**, no de capacidad |
| D5 | `prd-mvp.md` §7.1 | "capacidades … representadas en el token" contradice `security-architecture.md` §2 (capacidades locales, no claims) | Alinear PRD con security §2 |
| D6 | `security-architecture.md` §2 / `api-contract-overview.md` §3 | Modelo Bearer para el navegador; sin sesión/CSRF/CSP | Sección de superficies (§1 arriba) |
| D7 | `threat-model-stride.md` §3.1 | Mitigaciones de Spoofing/Info. Disclosure citan refresh token y `password_hash`, ya fuera de JobSeeker | Reescribir §3.1 como "cliente OIDC" |
| D8 | `nfr-quality-attributes.md` NFR-SEC-07 | Fuerza bruta de **login** ya no es responsabilidad de JobSeeker (login alojado en Clavaris) | Mover a Clavaris; en JobSeeker, límite de callback/sesión |
| D9 | `data-model.md` §3/§4 | Tablas del ER sin diccionario (`candidate_skills`, `job_requirements`, `application_status_history`); `vector` sin dimensión; dinero sin moneda | LLD §4 |
| D10 | `data-model.md` §2, `system-design-document.md` §7 | Referencias a `roadmap §5` para rollback/ambientes; esa sección ahora es la Ola 3 (la estrategia de lanzamiento está en §8) | Corregir referencias |
| D11 | `integration-design.md` §7 | Dashboards en `infra/observability/`; el repo usa `infra/docker/grafana` | Unificar ruta |
| D12 | `nfr-quality-attributes.md` NFR-MAINT-02 | "Biome-equivalente si aplica" (herramienta de JS); stack es Java | Checkstyle/PMD/Spotless |
| D13 | `domain-model.md` §6 | 24 eventos sin semántica de entrega ni idempotencia | Outbox (`02` §6) |
| D14 | `data-model.md` §4.7 | Histórico por recálculo sin retención y crecimiento no acotado | `scores_current` + snapshots |
| D15 | `integration-design.md` §4 | Clave de caché sin versión de algoritmo/pesos | LLD §2.4 |
| D16 | `system-design-document.md` §4.6 | `@Scheduled` sin estrategia multiinstancia | `SKIP LOCKED`/CAS (`02` §6.2) |
| D17 | `business-rules.md` BR-TRACK-01 | "Vacante activa" sin definición operativa | Índice único parcial (LLD §4.1) |
| D18 | PRD §4 | No-objetivos no incluyen agentes autónomos, calendario de entrevistas, mensajería | Añadir (`01` §4.4) |
| D19 | `roadmap-and-release-plan.md` | Sin tareas explícitas para evaluación del matching, notificaciones, archivos, búsqueda, accesibilidad, analítica de producto | Ver `00` §4 |
| D20 | las reglas del proyecto §3 | Lista JJWT 0.12.6 como parte del stack | Eliminar al decidir ADR-0014 |

## 9. Decisiones recomendadas para las preguntas abiertas

Cada fila propone un **valor por defecto** para desbloquear el diseño; no sustituye la decisión del responsable del proyecto.

| Pregunta (origen) | Decisión recomendada |
|---|---|
| Verificación de reclutador (PRD §9) | Sí: dominio corporativo verificado por email + revisión manual de la cola; `UNVERIFIED` sin sugerencias |
| Volumen para pruebas de carga (PRD §9) | Validar con 3× lo declarado (300 concurrentes, 30 000 perfiles, 3 000 vacantes) |
| Notificar al candidato que fue sugerido (PRD §9, security §13) | Resumen periódico agregado ("apareciste en N búsquedas"); sin identificar al reclutador |
| Expiración de `HIRING` sin actividad (PRD §9) | Aviso a 14 d, pausa de postulaciones a 30 d, cierre a 45 d; configurable |
| Descubrir a quién excluir (PRD §9) | Sin descubrimiento en MVP; búsqueda por email exacto |
| Vista candidato→candidato (PRD §9, domain §8) | **No** en MVP; la exclusión de cuentas-candidato queda latente (diferir su UI a Ola 2) |
| Vacantes ocultas por elegibilidad: ¿avisar? | Silencioso para criterios incumplidos; aviso agregado solo cuando falta un dato del perfil (fail-closed por perfil incompleto) |
| Detalle del mensaje de rechazo (BR-ELIG-03) | Categoría + acción, sin umbrales (LLD §3.2) |
| ¿Cómo se entera de la invitación? | Notificación in-app + email (REQ-P-01) |
| ¿Se revoca la invitación? | Sí, `revoked_at` (soft); no retroactivo (BR-ELIG-13) |
| `archivalDelayDays` / `accountDeletionGracePeriodDays` | `@ConfigurationProperties` en `application.yml`, validados al arranque (rango 3–5 y 7–60); sin tabla de configuración en MVP |
| Aviso antes de archivar / `SKIPPED` | Sí: notificación in-app 24 h antes; `SKIPPED` notifica al reclutador |
| Almacenamiento de `FILE_UPLOAD` | Supabase Storage con URL firmada; mismos límites que CV (p. ej. 5 MB, PDF/DOCX/PNG/JPG) |
| Reutilizar archivo entre postulaciones | No en MVP |
| Límite de follow-ups por postulación | 3 (configurable) |
| Deshabilitar una capacidad | Permitido; vacantes publicadas pasan a `CLOSED`; datos preservados (reactivable) |
| Cambio de rol activo | Navegación pura (sin nuevo token) |
| Perfil público de candidato | No en MVP; `RecruiterProfile` público para autenticados (BR-ACCT-08) |
| `Application` único vs. dos agregados | Un agregado en `ats`; `tracker` = proyección de lectura |
| Normalizar `Skill`/`Requirement` | Sí (ESCO + alias; ADR-0019) |
| Sugerencias: bajo demanda vs. precálculo | Bajo demanda con caché corta (≈ 10 min) por (vacante, versión) |
| Caché del listado visible por candidato | No en MVP; SQL con índices; medir |
| Validar `criteria` | Tipos sellados al deserializar (LLD §3.2) |
| Datos de elegibilidad vs. scoring (¿mismo dato?) | Fuente única en `cv`; `job` y `matching` leen **proyecciones por puerto**, sin copia persistente en MVP |
| Invitación: ¿aceptación? ¿vigencia? | Sin aceptación; vigente mientras la vacante lo permita |
| Unificar batches | Un `JobLifecycleScheduler` que invoca dos casos de uso independientes |
| `@Scheduled` vs. cron externo | `@Scheduled` + `SKIP LOCKED`/CAS; frecuencia 1 min (job) / 1 h (anonimización) |
| Umbral de similitud BR-APPQ-04 | Calibrar con ≥ 50 preguntas etiquetadas; arrancar conservador y exigir margen sobre el segundo candidato |
| Editar/eliminar `ApplicationQuestion` con respuestas | Editar solo texto; cambiar tipo/opciones bloqueado; eliminar = archivar (conservar respuestas, paridad BR-JOB-09) |
| Fecha límite de follow-up | Sin expiración dura; recordatorio a 7 d; sin efecto en elegibilidad (BR-FUP-05) |
| Capacidades en el JWT | **No**: se resuelven localmente (security §2); corregir PRD §7.1 |
| ¿Embedding de `raw_cv_text` o estructurado? | Representación canónica estructurada (`02` §4.3) |
| `specializationAreas` | Texto libre normalizado a minúsculas en MVP; catálogo en Ola 3 |
| Zona horaria | UTC en BD; conversión en presentación |
| ¿Dónde vive la eliminación de cuenta? | `auth` orquesta; cada módulo anonimiza lo suyo (§3) |
| Eliminar una sola capacidad | Flujo distinto del borrado de cuenta (deshabilitar ≠ eliminar) |
| Cascada a Clavaris | Asíncrona con reintento; evento final al confirmar |
| Valores iniciales de rate limiting | Escritura 60/min, lectura 300/min, postular 10/min, sugerencias 30/min + cuota diaria; **calibrar** con tráfico (Ola 3) |
| Linter OpenAPI | Spectral (`spectral:oas`) + reglas propias (prefijo `/api/v1`, sobre de error, `operationId`, `tags`) |
| Scrape / retención Prometheus | 15 s / 15 d; Alertmanager → email + webhook (Telegram/Discord) |
| Observabilidad en producción | Sí (misma configuración, red interna) |
| Empaquetado de `shared-kernel` | Módulo Maven `common` (ya existe) |
| Feature flags | Tabla `feature_flags` + caché (o FF4J); ADR menor |
| Staging con Supabase | Proyecto Supabase separado (mismo motor que producción) |
| TTL de caché de scores | 7 d (la frescura depende de la clave, no del TTL) |
| Ollama en CI | Prueba de contrato con *mock* en cada PR + prueba nocturna con modelo real pequeño |

## 10. Fuentes consultadas

Fuentes de calidad desigual; la columna de confianza del resumen (§6 de `00`) indica qué afirmaciones son de proveedor.

- Torre — modelo de matching (skills y recomendación): <https://torre.ai/jobmatchingmodel/skillsproficiency>, <https://torre.ai/jobmatchingmodel/recommendationweight>, <https://torre.ai/apiforcompanies>
- LinkedIn Hiring Assistant: <https://business.linkedin.com/talent-solutions/ai-transparency>, <https://tech.co/news/what-is-linkedin-hiring-assistant>, <https://www.herohunt.ai/blog/linkedin-hiring-assistant-2-2026-features-cost-limits/> *(competidor directo; usar con cautela)*
- ATS (Workable/Greenhouse/Lever/Ashby): <https://resources.workable.com/inside-hr/the-best-ai-powered-applicant-tracking-systems-for-enterprise-companies-2026/>, <https://www.aimadefor.com/blog/greenhouse-vs-lever-vs-workable/>, <https://geekflare.com/guides/ats-and-ai/>
- Indeed Smart Screening / Smart Fit Score: <https://mx.indeed.com/reclutamiento/recursos/howtohub/que-es-indeed-smart-screening>, <https://es.indeed.com/reclutamiento/recursos/howtohub/que-es-smart-screening-de-indeed>; Bumeran: <https://www.larepublica.co/alta-gerencia/las-plataformas-de-inteligencia-artificial-que-estan-cambiando-la-forma-de-buscar-empleo-4403131>
- Ghosting / ghost jobs: <https://www.cpapracticeadvisor.com/2026/04/30/ghost-jobs-still-haunting-67-of-job-seekers-report-finds/>, <https://blog.theinterviewguys.com/the-silent-rejection/>
- EU AI Act / Digital Omnibus: <https://www.gibsondunn.com/eu-ai-act-omnibus-agreement-postponed-high-risk-deadlines-and-other-key-changes/>, <https://knowledge.dlapiper.com/dlapiperknowledge/globalemploymentlatestdevelopments/2026/The-Digital-AI-Omnibus-Proposed-deferral-of-high-risk-AI-obligations-under-the-AI-Act>, <https://www.hoganlovells.com/en/publications/eu-legislators-agree-to-delay-for-highrisk-ai-rules>
- NYC LL144 / Colorado: <https://www.littler.com/publication-press/publication/what-does-2025-artificial-intelligence-legislative-and-regulatory>, <https://regulations.ai/compare/us-state-hiring-ai>
- Guatemala: <https://emisorasunidas.com/nacional/2026/07/16/comision-de-asuntos-de-seguridad-analiza-iniciativa-de-ley-de-proteccion-de-datos-personales/>, <https://consortiumlegal.com/2025/09/25/implementacion-efectiva-de-la-proteccion-de-datos-en-centroamerica/>
- Sesgo en retrieval con embeddings: Wilson & Caliskan, AIES 2024, <https://arxiv.org/abs/2407.20371>
- Spring: <https://spring.io/blog/2025/11/21/spring-modulith-2-0-ga-1-4-5-and-1-3-11-released/>, <https://spring.io/blog/2026/06/11/spring-modulith-2-1-ga-2-0-7-and-1-4-12-released/>, <https://www.herodevs.com/blog-posts/spring-boot-versions-eol-dates-and-latest-releases-april-2026>, <https://infoq.com/news/2026/06/spring-news-roundup-jun08-2026>
- pgvector (iterative scan, filtros): <https://www.postgresql.org/about/news/pgvector-0.8.0-released-2952/>, <https://docs.pgedge.com/pgvector/v0-8-5/iterative-index-scans/>
- Taxonomías de skills: <https://lightcast.io/resources/blog/open-skills-taxonomy>, <https://aclanthology.org/2025.genaik-1.15>, <https://nestauk.github.io/ojd_daps_skills/pipeline_summary>
- Embeddings multilingües: <https://surrealdb.com/blog/embedding-models-comparison>, <https://jina.ai/news/aqui-se-habla-espanol-top-quality-spanish-english-embeddings-and-8k-context>
- Repos locales: `docs/**`, las reglas del proyecto, la documentación de estado de Clavaris (estado de implementación al 24-ago-2026 según su propio §11).
