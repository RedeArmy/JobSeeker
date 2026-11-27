# Security Architecture
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | 🟡 En revisión |
| **Versión** | 1.1 — §2 reescrita según ADR-0020 (aprobado el 18-nov-2026); §10, §11, §12 y §13 ajustadas |
| **Fecha** | Agosto 2026 · actualizado en noviembre 2026 |
| **Documentos relacionados** | Threat Model STRIDE, NFR & Quality Attributes, Business Rules, ADR-0020, `architecture-review-2026-11/06-spike-contrato-clavaris.md` |

---

## 1. Propósito del documento

Definir los controles de seguridad concretos que mitigan las amenazas identificadas en `threat-model-stride.md` y cumplen los requisitos de NFR-SEC-01 a 08. La seguridad se trata como **no negociable desde el inicio** (Ola 1, `roadmap-and-release-plan.md` §3): autorización, gestión de secretos y rate limiting quedan ratificados por ADR (0011) en vez de diferidos como "propuesta pendiente" — donde aún queda una decisión técnica genuinamente abierta, se marca explícitamente como tal, no se asume. La autenticación misma (emisión de tokens, contraseñas, MFA) ya no es competencia de este documento — es responsabilidad de **Clavaris**, un proveedor de identidad externo con su propio `security-architecture.md` (repositorio separado); este documento cubre solo el lado de **JobSeeker como cliente OIDC**.

## 2. JobSeeker como cliente OIDC de Clavaris

Decisión y pruebas: ADR-0020 (aprobado) y `architecture-review-2026-11/06-spike-contrato-clavaris.md`. JobSeeker **nunca emite** tokens ni guarda credenciales: es un cliente OIDC confidencial de Clavaris, que firma con **RS256** (asimétrico: sirve a varias aplicaciones independientes). El navegador **no** envía un JWT en la cabecera `Authorization`; eso solo ocurre en la API programática.

### 2.1 Tres superficies de seguridad

| Superficie | Rutas | Autenticación | Estado | CSRF |
|---|---|---|---|---|
| **A. Web SSR/HTMX** | `/app/**`, login y logout | `oauth2Login` (Authorization Code + **PKCE S256**) contra Clavaris | Sesión del lado del servidor (Spring Session sobre Redis); el navegador solo guarda el ID de sesión | **Activo** en toda ruta mutante |
| **B. API JSON** | `/api/v1/**` | `Authorization: Bearer <token>` validado como servidor de recursos | Sin estado, sin cookies | No aplica (no hay cookie de sesión que proteger) |
| **C. Receptor de webhooks** | `/api/v1/webhooks/clavaris` | **Solo** la firma `Clavaris-Signature` (HMAC-SHA256) | Sin estado | No aplica |

Cada superficie es una cadena de filtros independiente en el módulo `app`. La UI Thymeleaf + HTMX usa **A**; B es para integraciones y una futura app móvil; C solo la llama Clavaris.

### 2.2 Superficie A: sesión web

| Aspecto | Control |
|---|---|
| Tokens | Los tokens de Clavaris, incluido el refresh token, **no llegan nunca al navegador** |
| Cookie | Prefijo `__Host-`, `HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/`. El ID se rota al autenticar (anti *session fixation*). En desarrollo sobre `http://localhost` se usa el mismo nombre sin prefijo |
| Expiración | Inactividad ≤ 30 min y absoluta ≤ 8 h (configurables) |
| CSRF | Token en una `<meta>` renderizada por Thymeleaf; HTMX lo adjunta como `X-CSRF-TOKEN` en cada petición. Una prueba recorre **todas** las rutas mutantes de `/app/**` y exige 403 sin token |
| Cabeceras | CSP estricta sin `unsafe-inline`, HSTS, `X-Content-Type-Options`, `Referrer-Policy`, `frame-ancestors 'none'`, `Permissions-Policy` |
| Logout | Se revoca el refresh token en Clavaris (`/oauth2/revoke`, que desde TD-SEC-100 termina su sesión), se usa el `end_session_endpoint` y se invalida la sesión local. Clavaris no ofrece *back-channel logout* |
| No autenticado | Redirección a login (navegación) o `HX-Redirect`/401 (HTMX) |

**Prueba de vida contra Clavaris.** Una suspensión o un borrado en Clavaris invalidan los tokens de Clavaris pero **no** la sesión de JobSeeker, así que la sesión se comprueba renovando el refresh token al menos cada 10 min (configurable, ≤ 15):
- `invalid_grant` ⇒ se invalida la sesión local de inmediato.
- Clavaris no responde (cualquier fallo distinto de `invalid_grant`: red, tiempo agotado, 502) ⇒ la sesión sigue válida hasta **2 horas** desde la última comprobación correcta (decidido el 19-nov-2026; configurable; nunca supera el límite absoluto de 8 h) y después se cierra. Durante una caída de Clavaris nadie puede suspender cuentas, así que la exposición se limita a las suspensiones de los ≤ 10 min previos. Un interruptor operativo fuerza el cierre inmediato.
- La renovación lleva **tiempos de espera obligatorios** (conexión 2 s, lectura 10 s) y **caché negativa** de fallos (30–60 s): sin ellos el cliente por defecto de Spring no termina frente a un Clavaris colgado y bloquea los hilos de la sesión. Un tiempo agotado puede dejar consumido el refresh token (Clavaris procesa la petición y se pierde la respuesta): al volver Clavaris esa sesión da `invalid_grant` y el usuario inicia sesión de nuevo (esperable, no un incidente).
- La renovación va bajo un **cerrojo por sesión** (en memoria con una instancia, en Redis con varias), porque los refresh tokens son de un solo uso y reutilizar uno revoca toda la familia (probado: 8 renovaciones simultáneas sin cerrojo dan 7 fallos y 7 eventos de reutilización). Con varias instancias el token se **relee de Redis dentro del cerrojo** y el TTL del cerrojo supera con margen la duración de una renovación.
- El refresh token vale 30 días: se guarda **cifrado en reposo** (AES-GCM, clave propia con identificador `kid` para rotarla), solo en la sesión del servidor y nunca en logs.

### 2.3 Superficie B: API Bearer

- Valida la firma RS256 contra el JWKS de Clavaris (caché con rotación por `kid`), `iss` (`{base}/o/{orgId}`) y `exp`.
- **`aud` se valida con un validador propio:** el decodificador por defecto no lo hace, y todos los clientes de una organización comparten clave de firma, así que sin esa comprobación valdría el token de cualquier otra aplicación de la organización. `aud` es el `client_id` del cliente que pidió el token; se acepta una lista configurable (por defecto el `client_id` de JobSeeker).
- Vida real de los tokens: access token **300 s**, ID token 1.800 s, refresh token 30 días con rotación de un solo uso.
- CORS cerrado por defecto (lista explícita, vacía en el MVP). `401` con `WWW-Authenticate`.

### 2.4 Superficie C: webhooks de Clavaris

- **Autenticidad solo por firma:** HMAC-SHA256 sobre `"<t>.<cuerpo crudo>"`, aceptando cualquier firma `v1` presente (rotación del secreto), con tolerancia de tiempo de 300 s, límite de tamaño del cuerpo y *rate limit* por IP. La firma se comprueba antes de interpretar el cuerpo.
- **Deduplicación por evento procesado** con restricción única (`Clavaris-Event-Id` en `processed_events`, `ON CONFLICT DO NOTHING`), en la misma transacción que su efecto. Orden por la tupla `(occurredAt, event_id)`; `account.deleted` es terminal y gana siempre. Los eventos pueden llegar desordenados, repetirse y perderse si el endpoint está desactivado: tras cualquier hueco se invalidan las sesiones anteriores, se vigila `active`/`autoDisabledAt` con alerta inmediata y el límite absoluto de sesión acota la exposición.
- **PII:** el cuerpo trae correo (en `created`/`deleted`), nombre y teléfono (en `profile_updated`). No se registra en logs y se reduce a identificadores antes de persistir (ADR-0016).

### 2.5 Identidad y capacidades (común)

- En el **primer login** se hace *upsert* de `accounts` por `sub`; no hay webhook obligatorio.
- En **cada petición** se reconstruye `Actor(accountId, capabilities, organizationId?)` **desde la base local** (`is_candidate_enabled`, `is_recruiter_enabled`, estado de eliminación). Del token solo se consume `sub`: **nunca** capacidades ni roles de negocio, que no son un concepto de Clavaris (BR-ACCT-01). El token de acceso de Clavaris no trae `name` ni `capabilities`.
- **El correo ya no viaja en el ID token** (Clavaris, desde el 18-nov-2026): solo `userinfo` lo devuelve con el scope `email`. JobSeeker solo consume `sub`, de modo que no le afecta; no hay que leer `email` del ID token.
- Una cuenta en `PENDING_DELETION` o `ANONYMIZED` es rechazada por el filtro de actor salvo para cancelar el borrado (BR-DATA-04).

### 2.6 Degradación

| Falla | Efecto |
|---|---|
| Redis caído (JobSeeker) | El servicio debe **arrancar** igualmente (`ConfigureRedisAction.NO_OP` y escucha de sesiones tolerante, ADR-0020 regla 9): *liveness* 200 y *readiness* 503; las sesiones web dejan de funcionar hasta que vuelva Redis, y se recupera sin reiniciar |
| Clavaris caído | Las sesiones sobreviven el margen de la prueba de vida y luego se cierran; el login nuevo no es posible |
| Redis caído (Clavaris) | La renovación, la revocación y suspender/borrar cuentas siguen funcionando (ADR-0045 de Clavaris); el cierre de sesiones de navegador se aplaza hasta que vuelva |

**Cuenta multi-rol:** las capacidades `CANDIDATE`/`RECRUITER` (BR-ACCT-01, ADR-0008) son un concepto de negocio de JobSeeker, resuelto y persistido localmente; Clavaris solo identifica *quién* es la persona, JobSeeker decide *qué puede hacer*. Cambiar de vista (candidato ↔ reclutador) es navegación pura sobre la misma sesión; no requiere nuevo login ni otro token.

## 3. Contraseñas y credenciales

Fuera del alcance de este documento — JobSeeker nunca ve, procesa ni almacena una contraseña de usuario; ese ciclo completo (hashing, política de complejidad, recuperación, MFA) es responsabilidad exclusiva de Clavaris. `accounts.password_hash` **no existe** en el esquema de JobSeeker (`data-model.md` §4.1) precisamente para no duplicar esa superficie de riesgo.

## 4. Gestión de secretos

| Ambiente | Estrategia |
|---|---|
| Desarrollo local | Variables de entorno vía `.env` (excluido de control de versiones) |
| CI/CD (GitHub Actions) | Secretos de repositorio/organización de GitHub Actions |
| Producción (Supabase + despliegue) | **Ratificado:** variables de entorno inyectadas por el pipeline de despliegue (GitHub Actions → destino de despliegue) al momento de desplegar, sin persistir el secreto en ningún artefacto ni imagen — mismo mecanismo de origen (GitHub Actions Secrets) que CI/CD, extendido a producción sin introducir un gestor de secretos dedicado adicional (p. ej. Vault), no justificado para la escala de este proyecto |

Regla transversal: ninguna cadena de conexión, clave de API o credencial se hardcodea en el repositorio, en ningún ambiente (criterio verificado en `definition-of-done.md`).

## 5. Cifrado

| Capa | Control |
|---|---|
| En tránsito | TLS obligatorio para toda comunicación entre navegador y aplicación, y entre aplicación y Supabase (NFR-SEC-02) |
| En reposo | Cifrado en reposo provisto por la capa gestionada de Supabase en producción; en desarrollo local (Docker), sin cifrado en reposo adicional al del sistema de archivos del host — aceptable porque no contiene datos reales de usuarios |

## 6. Control de acceso basado en capacidades (RBAC multi-rol)

- Dos capacidades no excluyentes definidas por `auth-module`: `CANDIDATE` y `RECRUITER` (BR-ACCT-01, ADR-0008) — una cuenta puede tener una, la otra, o ambas. Reemplaza el modelo anterior de rol único.
- La(s) capacidad(es) se resuelve(n) siempre desde `accounts.is_candidate_enabled`/`is_recruiter_enabled` en el servidor, nunca desde un valor enviado por el cliente (mitigación de Elevation of Privilege, `threat-model-stride.md` §3.1).
- La autorización se aplica en la capa de aplicación (casos de uso), no únicamente ocultando elementos de UI — un endpoint llamado directamente sin pasar por la UI debe rechazar igual una acción no autorizada (NFR-SEC-03).
- **Aislamiento entre capacidades de una misma cuenta (BR-ACCT-04, NFR-SEC-04):** cada caso de uso valida la capacidad específica que requiere (p. ej. `RECRUITER` para publicar una vacante, `CANDIDATE` para postular), sin que la presencia de la otra capacidad en la misma cuenta otorgue ni amplíe permisos. Una cuenta con ambas capacidades que postula (actúa como candidato) se autoriza exactamente con las mismas reglas que una cuenta exclusivamente `CANDIDATE` — el caso de uso no debe consultar ni ramificar sobre si esa cuenta también tiene `RECRUITER`.
- **Auto-postulación bloqueada (BR-ACCT-05):** el caso de uso de creación de `Application` rechaza explícitamente si `candidateProfile.accountId == jobPosting.recruiterAccountId`, incluso si técnicamente ambas capacidades están habilitadas y activas — control adicional al de la sección 7, no reemplaza el chequeo de propiedad.

## 7. Aislamiento de datos entre reclutadores

Control directo de BR-VIS-03 y BR-CVGEN-01: toda consulta de `matching-module` o `ats-module` que involucre un `job_posting_id` valida, antes de responder, que ese `job_posting_id` pertenece al `recruiter_account_id` del solicitante autenticado. Lo mismo aplica a `recruiter_templates` (BR-CVGEN-01) — un reclutador nunca puede leer, listar ni referenciar una plantilla cuyo `recruiter_account_id` no coincide con su propia cuenta.

Este control se implementa en la capa de aplicación (casos de uso), no como un filtro opcional de la consulta — su ausencia es tratada como un defecto de seguridad, no un caso límite de UX.

## 8. Visibilidad asimétrica del score (BR-VIS-01, 02, 04)

| Rol | Qué puede ver |
|---|---|
| Candidato | Score y desglose únicamente de vacantes que él mismo consultó o a las que postuló |
| Reclutador | Score y desglose completo de todos los candidatos que postularon a **sus propias** vacantes (BR-VIS-02), **más** el score y desglose completo de candidatos **sugeridos** que no postularon pero cuyo perfil es descubrible (BR-VIS-04) — nunca de vacantes de otro reclutador en ninguno de los dos casos |

Implementado como regla de autorización en el caso de uso de consulta de score, no como un simple filtro de presentación. La consulta de candidatos sugeridos (BR-VIS-04) aplica dos filtros obligatorios en la capa de aplicación, no solo uno: (1) el `job_posting_id` pertenece al reclutador solicitante (sección 7), y (2) `candidate_profiles.discoverable = true` (BR-DATA-03) — la ausencia de cualquiera de los dos filtros es un defecto de seguridad, no un caso límite.

### 8.1 Descubribilidad del candidato (BR-DATA-03)

- Por defecto, todo `CandidateProfile` es descubrible (`discoverable = true`) — modelo opt-out, no opt-in.
- El candidato puede desactivar la descubribilidad en cualquier momento desde su perfil; el efecto debe ser inmediato (no depende del próximo recálculo de score) — ver `CandidateDiscoverabilityChangedEvent` en `domain-model.md` §6.
- Desactivar la descubribilidad retira al candidato de futuras consultas de sugerencia (BR-MATCH-06), pero no afecta scores ya generados a partir de postulaciones reales (BR-VIS-02), que se rigen por la relación de `Application`, no por `discoverable`.

### 8.2 Estado de disponibilidad del candidato (BR-CAND-04, BR-VIS-05, BR-VIS-06)

- `availabilityStatus` (`OPEN_TO_WORK` / `NOT_LOOKING` / `UNSET`) es visible al reclutador en cualquier contexto donde el candidato ya sea visible (postulación o sugerencia) — no es una vía de exposición adicional, es metadato de un perfil ya autorizado (BR-VIS-05), **salvo** para un reclutador en la lista de exclusión del candidato (sección 8.4).
- `availabilityStatus` nunca se usa como filtro de autorización (a diferencia de `discoverable`) — solo afecta orden de presentación (BR-MATCH-07), nunca si el reclutador puede o no ver al candidato.
- **Decisión de alcance del MVP:** el sistema no infiere automáticamente quién es el "empleador actual" de un candidato — no existe detección heurística por nombre de empresa ni por experiencia laboral vigente. El control equivalente lo da la lista de exclusión manual (sección 8.4): el candidato decide explícitamente a quién ocultarle su estado, sin que el sistema adivine esa relación.

### 8.3 Etapa de reclutamiento de la vacante (BR-JOB-04, BR-MATCH-08)

- `recruitingStage` es visible al candidato en la vista de la vacante (transparencia sobre si el proceso está activo) y controlado únicamente por el reclutador dueño de esa vacante (mismo control de propiedad de la sección 7).
- `REVIEWING` es una señal de negocio (pausa de nuevas sugerencias), no un control de seguridad — no afecta autorización de acceso a postulaciones ni scores ya existentes.

### 8.4 Lista de exclusión de disponibilidad (BR-CAND-05, BR-CAND-06, BR-VIS-06, BR-MATCH-09)

- El candidato mantiene una lista propia de cuentas — de **cualquier rol**, reclutador u otro candidato (`AvailabilityExclusion`, `candidate_availability_exclusions`) — a quienes se les oculta específicamente `availabilityStatus`; nunca el resto del perfil, el score o el desglose.
- **Doble filtro obligatorio en la capa de aplicación**, análogo al de la sección 7: toda respuesta que incluya `availabilityStatus` de un candidato para una cuenta dada evalúa primero si existe una fila en `candidate_availability_exclusions` para ese par `(candidateProfileId, excludedAccountId)`; si existe, el campo se omite o se fuerza a `UNSET` en la respuesta — nunca se filtra en el cliente.
- La misma exclusión se propaga a la consulta de sugerencia de candidatos (BR-MATCH-09) cuando la cuenta excluida actúa como reclutador: no solo deja de ver el badge, tampoco recibe el boost de orden que delataría la señal igual.
- **Invariante de seguridad crítica (BR-CAND-06):** la exclusión **nunca** es evaluada por el flujo de postulación ni por BR-VIS-02/03/04, ni restringe el acceso al perfil o al score de ninguna forma — un candidato siempre puede postular a una vacante de un reclutador que tiene en su lista de exclusión, y esa cuenta sigue viendo el score y desglose de esa postulación con normalidad. Un defecto que bloqueara o degradara la postulación, o el acceso al perfil/score, en función de esta lista se trata como un defecto de severidad alta, no una variante aceptable.
- **Alcance sin canal actual:** excluir una cuenta con rol candidato no tiene, hoy, un canal donde surta efecto visible — el dominio no modela una vista de "candidato ve el perfil de otro candidato" (ver `domain-model.md` §8). El control queda modelado igual porque BR-CAND-05 lo exige explícitamente y porque el rol de una cuenta no es necesariamente estático.
- **Límite conocido de alcance:** la exclusión opera por cuenta individual, no por empresa (el sistema no modela `Company`/`Organization`). Si el empleador del candidato tiene más de una cuenta de reclutador en la plataforma, el candidato debe excluir cada una — riesgo residual documentado, no resuelto en el MVP (ver `threat-model-stride.md` §3.3).

### 8.5 Elegibilidad, invitación y ciclo de vida de vacante (BR-ELIG, BR-JOB-06 a 20)

- **Configuración de `EligibilityRule`, `ApplicationQuestion`, `JobPostingScheduledTransition`** y transiciones manuales de estado (`CLOSED`, reapertura, eliminación): todas exigen el mismo control de propiedad de la sección 7 (`job_posting_id` pertenece al `recruiter_account_id` autenticado) — no son controles nuevos, son la misma regla aplicada a entidades hijas del agregado `JobPosting`.
- **`CandidateInvitation` (BR-ELIG-09/11):** su creación exige, además del control de propiedad, que el solicitante tenga la capacidad `RECRUITER` activa (sección 6); su sola existencia hace que la validación de postulación (sección 8.6) trate como cumplidas todas las reglas activas para ese candidato — este bypass se evalúa en el mismo caso de uso que BR-ELIG-02/03, nunca como un chequeo separado que pueda omitirse.
- **Eliminación de vacante (BR-JOB-09):** exige `status = ARCHIVED` además de la propiedad — un intento de eliminar una vacante en cualquier otro estado se rechaza a nivel de autorización/invariante de dominio, no solo de UI.

### 8.6 Validación de elegibilidad y respuestas de postulación (BR-ELIG-02/03, BR-APPQ-05/06)

- El caso de uso de creación de `Application` (`ats-module`) es el único punto de entrada que valida elegibilidad — no existe una vía alterna de creación de `Application` que la omita (p. ej. un endpoint de administración fuera de este flujo sería un defecto de seguridad).
- **`ApplicationAnswer` con `source = AUTO_FILLED_FROM_PROFILE`:** el servidor, no el cliente, resuelve el valor prellenado contra `CandidateProfile`/`ProfessionalProfile` de la cuenta autenticada — el cliente nunca envía qué perfil leer. Aun así, `confirmed_by_candidate = true` es obligatorio antes de aceptar el envío de la `Application` (BR-APPQ-06); el servidor rechaza una `Application` con alguna respuesta sin confirmar.
- **Visibilidad de `ApplicationAnswer`:** mismo doble filtro de la sección 8 — el candidato ve solo sus propias respuestas; el reclutador ve las respuestas de una postulación solo si el `job_posting_id` de esa `Application` le pertenece (sección 7).
- **`ApplicationFollowUpRequest` (BR-FUP-03):** su creación exige el mismo control de propiedad de la sección 7 sobre la vacante de la postulación referenciada.

### 8.7 Visibilidad de `RecruiterProfile` (BR-ACCT-08)

A diferencia de todo lo anterior, `RecruiterProfile` **no** tiene control de propiedad ni de descubribilidad — es intencionalmente público y de solo lectura para cualquier cuenta autenticada que consulte una vacante publicada por esa capacidad (BR-ACCT-08). El único control aplicable es de **integridad** (solo el propio reclutador puede editar su `RecruiterProfile` y el `ProfessionalProfile` compartido, BR-ACCT-06), no de lectura.

## 9. Protección de PII en observabilidad

- Logs estructurados incluyen `traceId`, `accountId` (identificador, no dato personal) y metadatos técnicos — nunca nombre, email, teléfono ni contenido de CV (BR-DATA-01, NFR-OBS-01).
- Los mensajes de error expuestos al cliente siguen el formato de `api-contract-overview.md` §6, que excluye explícitamente datos sensibles.

## 10. Eliminación de datos a solicitud del titular (ADR-0013)

Soporta BR-DATA-02 a 06: el titular de una cuenta puede solicitar la eliminación de sus datos. **Resuelto (ya no es pregunta abierta):** ni borrado inmediato en cascada, ni retención indefinida — periodo de gracia de `accountDeletionGracePeriodDays` (por defecto 30 días, cancelable volviendo a iniciar sesión), seguido de **anonimización** automática por un proceso programado (`AnonymizeExpiredAccountDeletionsUseCase`, mismo patrón que los batches de `job-module`).

| Dato | Tratamiento al vencer la gracia |
|---|---|
| PII directa (`professional_profiles.full_name`/`bio`/`headline`/`profile_photo_key` (y el objeto de la foto), `candidate_profiles.contact_email`/`contact_phone`/`raw_cv_text`/`embedding`) | Anonimizado (`NULL` o placeholder genérico) |
| Filas hijas de solo el titular (`experiences`, `education_entries`, `certifications`, `projects`, `candidate_work_authorizations`, `candidate_languages`) | Borrado físico — nadie más tiene interés legítimo sobre el detalle de la experiencia laboral de una persona |
| `applications`, `compatibility_scores`, historial de pipeline | **Conservados**, referenciando el perfil ya anonimizado — el reclutador que evaluó al candidato no pierde su propio historial de decisión por una acción del titular (mismo principio que BR-JOB-09) |
| Identidad real en Clavaris (email, credencial, identidades sociales) | Eliminada vía la API de administración de Clavaris en el mismo proceso (BR-DATA-06) — el borrado no es completo si la identidad sigue existiendo fuera de JobSeeker. Se usa una **Secret Key de la organización** (`OrganizationClient`) con el único scope `platform:accounts:delete` (TD-SEC-101 de Clavaris): solo alcanza cuentas de su propia organización, a diferencia del credencial de plataforma. Responde 204 incluso con Redis caído; se reintenta solo ante errores de red o 5xx |

Detalle completo: ADR-0013, `domain-model.md` §4.2, `data-model.md` §4.1/4.2/4.4.

## 11. Rate limiting (ADR-0011)

Mecanismo transversal — Bucket4j sobre Redis, algoritmo de token bucket — aplicado a todo el sistema, no solo a `matching-module` como se mencionaba de forma dispersa antes de este ADR.

| Aspecto | Detalle |
|---|---|
| Clave de bucket | `accountId` autenticado, o IP para endpoints previos a autenticación (login, registro) |
| Configuración | Capacidad y tasa de recarga por política, en configuración de aplicación — nunca hardcodeada (mismo criterio que `archivalDelayDays`, BR-JOB-06) |
| Política reforzada — login | La fuerza bruta contra credenciales es responsabilidad de Clavaris, donde vive el formulario (NFR-SEC-07 se traslada allí). En JobSeeker, un límite por IP sobre el *callback* de login (`/login/oauth2/code/*`), sobre el inicio del flujo y sobre la creación de sesiones, y otro por IP sobre `/api/v1/webhooks/clavaris` |
| Respuesta al exceder | `429 Too Many Requests` con el sobre de error de `api-contract-overview.md` §6 (`RATE_LIMIT_EXCEEDED`) y encabezados `Retry-After`/`X-RateLimit-*` |
| Alcance | Todo endpoint HTTP expuesto (`/api/v1/...` y fragmentos HTMX `/app/...`); los batches internos de `job-module` (`system-design-document.md` §4.6) no pasan por este filtro |

## 12. Documentación de API y superficie de ataque (ADR-0010)

- El spec OpenAPI agregado (springdoc, ADR-0010) documenta necesariamente la superficie completa de endpoints del sistema — es, en sí mismo, un activo sensible desde la perspectiva de reconocimiento de un atacante.
- **Swagger UI expuesta sin restricción solo en desarrollo/staging.** En producción, requiere autenticación antes de servirse (sesión de la superficie A o Bearer de la B, §2) — nunca pública sin control de acceso.
- El pipeline de CI que exporta y valida el spec (ADR-0010) no publica el artefacto exportado en un destino público accesible sin autenticación.

## 13. Preguntas abiertas

- Alcance exacto de la eliminación de datos solicitada por el candidato (sección 10) — ahora también aplica a `ProfessionalProfile` compartido: ¿su eliminación es posible mientras la cuenta conserve alguna capacidad activa, o requiere deshabilitar ambas primero?
- Complemento directo de la pregunta abierta de `threat-model-stride.md` §5: ¿se necesita un `BR-AUTH-XX` nuevo en `business-rules.md` para formalizar el control de la sección 7 como regla de negocio explícita, más allá de BR-VIS/BR-CVGEN?
- ¿El candidato es notificado cuando su perfil aparece como sugerido a un reclutador (sección 8), o la exposición vía BR-VIS-04 es silenciosa? (misma pregunta abierta de `prd-mvp.md` §9)
- ¿El reclutador ve datos de contacto directo (email, teléfono) de un candidato sugerido no postulado, o solo score + desglose hasta que exista una postulación real o un contacto explícito? (misma pregunta abierta de `threat-model-stride.md` §5)
- ¿El mensaje de rechazo por elegibilidad (BR-ELIG-03, NFR-USA-03) expone el criterio exacto de la regla incumplida, o un mensaje genérico por categoría? Mismo trade-off ya señalado en `prd-mvp.md` §9 entre transparencia para el candidato y exposición de la configuración de screening del reclutador.
- Backend concreto de almacenamiento de archivos para respuestas `FILE_UPLOAD` (`integration-design.md` §6) — determina el control de acceso aplicable (firma de URL temporal vs. proxy autenticado a través de la aplicación).
- Valores finales de las políticas de rate limiting (sección 11) — delegados a calibración, ver preguntas abiertas de ADR-0011. Los parámetros de Argon2id ya no son competencia de este documento (ver sección 3) — son pregunta abierta del `security-architecture.md` de Clavaris.
- Canal de notificación de Alertmanager y alcance de Prometheus/Grafana en producción (ver ADR-0012, preguntas abiertas).
- ~~Margen cuando Clavaris no responde (§2.2)~~ **Resuelta el 19-nov-2026: 2 horas**, configurable y acotado por las 8 h absolutas (ADR-0020, reglas 2 y 12; medido en `09-verificacion-supabase-hibernate-despliegue.md` §9).
- ¿Quién puede abrir Swagger UI en producción (§12)? Cualquier cuenta autenticada o solo una capacidad de operador; hoy no existe esa capacidad.
