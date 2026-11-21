# 06 — Spike de contrato con Clavaris (resultado)

| Campo | Valor |
|---|---|
| **Estado** | 🟡 En revisión |
| **Versión** | 1.0 · 14-nov-2026 |
| **Relacionado** | ADR-0013 (borrado de cuenta), ADR-0020 (superficies de autenticación), `security-architecture.md` §2, `integration-design.md`, `../Clavaris/docs/03-architecture/integration-design/integration-design.md` |
| **Método** | Lectura de la documentación y el código de Clavaris (solo lectura) **más ejecución real**: instancia aislada de Clavaris (jar `app/target/clavaris-app.jar` del 13-nov-2026, Postgres y Redis propios en puertos altos, directorio de trabajo temporal) y un cliente Spring Boot 4.1.2 / Java 25 desechable con `oauth2-client` y `oauth2-resource-server`. Todo se desmontó al terminar; el repo de Clavaris no se modificó |

> Convención: ✅ ejecutado y observado por mí · 📄 solo documentación/código leído, sin ejecutar · ⚠️ no verificado.

---

## 1. Veredicto

**El contrato funciona y es suficiente para empezar la Ola 1**: un cliente Spring Security estándar completó el login real (Authorization Code + PKCE), la validación del ID token, el cierre de sesión y la lectura de claims sin código a medida. Hay **dos hallazgos que requieren decisión tuya** y **cuatro correcciones a supuestos de nuestra documentación**:

| # | Hallazgo | Severidad |
|---|---|---|
| G1 | **No se puede borrar una cuenta con una credencial acotada a la organización**: borrar y suspender devuelven 403. La única credencial que puede es la de **plataforma, que opera sobre todos los tenants** (borré un usuario de otra organización con ella). Choca con la cascada de BR-DATA-06 / ADR-0013 | 🔴 Decisión |
| G2 | **La revocación de refresh tokens de Clavaris no surte efecto** (devuelve 200, el token sigue funcionando) y el cierre de sesión tampoco los invalida. Defecto reproducible de Clavaris | 🟠 Defecto ajeno + decisión de diseño nuestra |
| G3 | Los tiempos de vida reales son **access 300 s, ID token 1800 s** (no los 15 min asumidos) | 🟡 Corregir documentación |
| G4 | **Clavaris no entrega el nombre** de la persona (sin claim `name`) | 🟡 Corregir modelo |
| G5 | No hay *back-channel logout*; los webhooks exigen **HTTPS público** | 🟡 Condiciona ADR-0020 y el desarrollo local |
| G6 | `aud` del token es el `client_id`; todos los clientes de una organización comparten clave de firma | 🟡 Validación obligatoria en `/api/v1/**` |

---

## 2. Contrato verificado

### 2.1 Descubrimiento y protocolo

| Tema | Valor observado | |
|---|---|---|
| Emisor (`iss`) | `{base}/o/{organizationId}` — **uno por organización** (JobSeeker = una `Organization`) | ✅ |
| Discovery | `{issuer}/.well-known/openid-configuration`; expone `authorization_endpoint`, `token_endpoint`, `jwks_uri`, `userinfo_endpoint`, `end_session_endpoint`, `revocation_endpoint`, `introspection_endpoint`; **sin** `registration_endpoint` ni `backchannel_logout_supported` | ✅ |
| Flujos | `response_types=[code]`; grants: `authorization_code`, `refresh_token`, `client_credentials`, token-exchange | ✅ |
| Cliente | **Solo confidencial** (ADR-0013 de Clavaris): `client_secret_basic` o `client_secret_post`; PKCE **S256 obligatorio** (sin `code_challenge` ⇒ `invalid_request`) | ✅ |
| `redirect_uri` | Coincidencia exacta con las registradas; una no registrada ⇒ **400 sin redirección** | ✅ |
| Scopes | `openid profile email phone offline_access` | ✅ |
| Firma | RS256; JWKS con una clave RSA por organización; `Cache-Control: public, max-age=60` | ✅ |
| `sub` | **UUID** de la cuenta ⇒ coincide con `accounts.id` (uuid) de `data-model.md` §4.1 | ✅ |
| Cliente Spring Security 7 | Descubrimiento por `issuer-uri`, **PKCE añadido automáticamente** a un cliente confidencial, validación de `iss`/`nonce`/firma sin configuración extra | ✅ |

### 2.2 Tokens y claims

| Elemento | Observado |
|---|---|
| **Access token** | JWT; `sub, aud, iss, scope[], nbf, iat, exp, jti, picture`; **vida 300 s** |
| **ID token** | `sub, picture, amr, acr, auth_time, sid, azp, aud, nonce, iss, iat, exp, jti`; **vida 1800 s**. *Hasta el 17-nov-2026 llevaba también `email` y `email_verified`; desde el commit `36e46014` de Clavaris el correo solo sale de `userinfo` (addendum 8)* |
| `aud` / `azp` | El `client_id` del cliente que lo pidió (no un identificador de API) |
| `userinfo` | `sub, email, email_verified, picture` con scopes `openid profile email` |
| **Sin** | `name`, `given_name`, `family_name`, `preferred_username` (la cuenta solo tiene correo y contraseña); sin claim de capacidades `CANDIDATE`/`RECRUITER` (confirma `security-architecture.md` §2) |
| Token de gestión | `client_credentials` del emisor de plataforma; **vida 299 s** |

### 2.3 Renovación, revocación y cierre de sesión

| Comportamiento | Resultado | |
|---|---|---|
| Rotación de refresh token | Cada uso entrega uno nuevo (y un ID token nuevo) | ✅ |
| Reuso de uno ya rotado | `invalid_grant` **y se revoca toda la familia** (el token más nuevo también deja de servir) | ✅ |
| Secreto de cliente incorrecto | 401 `invalid_client` | ✅ |
| Revocar un **access token** (`/oauth2/revoke`) | 200; `userinfo` pasa a 401 | ✅ |
| Revocar un **refresh token** (`/oauth2/revoke`, con y sin `token_type_hint`) | 200 pero **el token sigue emitiendo tokens nuevos** (4 de 4 casos). En BD, `metadata.token.invalidated` permanece `false` | ✅ **defecto (G2)** |
| Cierre de sesión RP-initiated (`end_session_endpoint`) con `id_token_hint` | 302 a la `post_logout_redirect_uri` registrada (conserva `state`); no registrada ⇒ 400; **el SSO termina** (el siguiente acceso pide login) | ✅ |
| ¿El cierre de sesión invalida los refresh tokens? | **No** (el refresh posterior emitió tokens) | ✅ |
| Cliente Spring: `OidcClientInitiatedLogoutSuccessHandler` | Funciona de extremo a extremo | ✅ |

### 2.4 Ciclo de vida de la cuenta (lo que ve JobSeeker)

| Acción administrativa | Efecto observado | |
|---|---|---|
| `POST …/accounts/{id}:suspend` | 204; access token viejo ⇒ 401; refresh ⇒ `invalid_grant`; un login nuevo **no entrega tokens**; evento `account.suspended` en el *outbox* | ✅ |
| `…:reactivate` | 204 | ✅ |
| `…:delete` | 204; **repetir ⇒ 404** (tratar 404 como éxito para idempotencia); tokens previos mueren al instante; la fila desaparece | ✅ |
| Eventos vistos en el *outbox* | `account.created`, `account.deleted`, `account.suspended`, `account.new_device_detected`, `refresh_token.reuse_detected`; catálogo documentado también incluye `account.reactivated`, `account.email_verified`, `account.session_revoked`, `account.profile_updated`, etc. | ✅ / 📄 |
| Carga de `account.created` | `{"accountId":{"value":…},"organizationId":{"value":…},"email":…,"occurredAt":…}` — **contiene el correo (PII)** | ✅ |
| Cambio de correo | **Sí existe `account.email_changed`** (el payload lleva ambas direcciones, tras verificar la nueva). *Corrección del 16-nov-2026:* el spike inicial afirmaba que no existía porque mi búsqueda en el código se limitó a 5 resultados. El catálogo real tiene **28 tipos** de evento | ✅ (leído en código y presente en el jar probado) |

### 2.5 Webhooks

| Tema | Valor | |
|---|---|---|
| Registro | `POST /api/v1/admin/organizations/{id}/webhook-endpoints` `{url, description, subscribedEventTypes[]}`; devuelve el secreto **una sola vez** | 📄 |
| Restricciones | **HTTPS obligatorio** (BR-WEBHOOK-07) **y** rechazo de direcciones privadas/loopback/link-local/metadatos (SSRF, TD-SEC-053/097): mi intento con `http://127.0.0.1` fue rechazado (400) | ✅ |
| Cabeceras de entrega | `Clavaris-Signature: t=<epoch>,v1=<hmac-sha256-hex>[,v1=…]`, `Clavaris-Event-Type`, `Clavaris-Event-Id`, `Clavaris-Delivery-Id`, `Clavaris-Trace-Id` | 📄 |
| Firma | HMAC-SHA256 sobre `"<t>.<cuerpo crudo>"`; dos entradas `v1` durante una rotación de secreto | 📄 |
| Semántica | Al menos una vez; reintentos con *backoff*; deduplicar por `Clavaris-Event-Id`; responder 2xx rápido | 📄 |
| **Entrega real a un receptor** | **Verificada el 16-nov-2026** mediante un túnel HTTPS temporal; ver addendum al final | ✅ |

### 2.6 Credenciales administrativas

| Credencial | Qué puede | |
|---|---|---|
| **`PlatformClient`** (emisor `{base}/oauth2/token`, sembrada por variables de entorno) | Todo `/api/v1/admin/**` sobre **cualquier** organización. Borró un usuario de un tenant distinto (204) | ✅ |
| **`OrganizationClient`** (`sk_test_…`/`sk_live_…`; se emite con `POST …/organizations/{id}/secret-keys`, token con claim `organization_id`) | Solo rutas de una *lista permitida* por organización. **Puede recibir** el scope `platform:accounts:delete`/`suspend` (201) pero **al usarlo: 403** en `:delete` y `:suspend`, aun sobre su propia organización | ✅ |
| Registro de webhooks con `OrganizationClient` | **Verificado: 201** con URL HTTPS pública válida (addendum) | ✅ |

---

## 3. Hallazgos que requieren decisión

### G1 — Borrado de cuenta (cascada BR-DATA-06, ADR-0013)

> **Registrado en Clavaris el 16-nov-2026 como `TD-SEC-101` (P1). Implementado por Clavaris tal como se propuso (ADR-0044) y validado desde JobSeeker el 17-nov-2026: ver addendum 5.** El texto de esta sección describe lo observado antes del cambio.

`business-rules.md` BR-DATA-06 y el job de anonimización exigen llamar a `POST /api/v1/admin/accounts/{id}:delete`. Con el diseño actual de Clavaris eso solo es posible con la credencial de plataforma, es decir, **JobSeeker tendría que custodiar un secreto capaz de borrar cuentas de todos los tenants** (y de crear/borrar organizaciones si tiene esos scopes). Una intrusión a JobSeeker ampliaría su impacto a todo Clavaris. `ADR-0023` de Clavaris reconoce este límite como "deliberado en v1", no como un descuido.

| Opción | Descripción | Evaluación |
|---|---|---|
| **A. Ampliar `OrganizationClient` en Clavaris** (recomendada) | Añadir `accounts:delete` (y, si se desea, `suspend`/`reactivate`) a la lista de rutas permitidas por organización, con la comprobación de propiedad existente. Requiere una enmienda al ADR-0023 de Clavaris y una prueba | Mínimo privilegio real; cambio pequeño y localizado; ambos repos son tuyos. Es la única que elimina el riesgo |
| B. JobSeeker custodia un `PlatformClient` limitado al scope `platform:accounts:delete` | Si Clavaris permite registrar más de un `PlatformClient` con scopes restringidos (⚠️ no verificado: la documentación solo menciona el cliente de arranque por variables de entorno) | Reduce las operaciones pero **no** el alcance entre tenants. Aceptable solo como interino con riesgo documentado |
| C. Borrado mediado por operador | JobSeeker deja la cuenta en `PENDING_IDENTITY_DELETION` y un operador ejecuta el borrado en Clavaris | Sin credencial en JobSeeker; no escala ni es automático; válido para la fase de validación de la tesis |

**Mientras tanto**, el diseño del job de anonimización debe ser tolerante: tratar 404 como éxito, reintentar con espera exponencial, y obtener un token **nuevo en cada ejecución** (vive 299 s).

### G2 — Refresh tokens: defecto de Clavaris y consecuencia para ADR-0020

> **Registrado en Clavaris el 16-nov-2026 como `TD-SEC-100` (P0). Corregido por Clavaris (ADR-0044) y validado desde JobSeeker el 17-nov-2026: ver addendum 5.** El texto de esta sección describe lo observado con el jar del 13-nov.

El defecto (revocación sin efecto; el cierre de sesión no invalida refresh tokens) es de Clavaris y debe registrarse como deuda técnica allí; la causa raíz no está establecida (el estado en BD muestra que la invalidación no se aplica). **No lo corregí**: no era el alcance del spike ni mi repositorio.

**Decisión del responsable (16-nov-2026): opción B — refresh token del lado del servidor como prueba de vida.** Se solicita `offline_access`; el refresh token se guarda **solo** en la sesión del servidor (nunca llega al navegador) y se usa para comprobar, al menos cada 10 min (configurable, ≤ 15), que la cuenta sigue vigente en Clavaris. Se descartó la opción A (sin refresh tokens) porque dejaba la detección de una suspensión/borrado dependiendo solo de los webhooks, que pueden perderse (addendum 2).

**Verificado el 16-nov-2026 con un cliente Spring Security 7 real** (Clavaris aislado, 3 pruebas, tras corregir dos errores de la propia sonda que daban 500 y no demostraban nada):

| Prueba | Resultado |
|---|---|
| 8 renovaciones simultáneas de una misma sesión, **sin** exclusión (comportamiento por defecto de Spring) | 1 OK, 7 `invalid_grant`; **7 eventos `refresh_token.reuse_detected`**; la familia queda revocada y la sesión pide autorizar de nuevo (`client_authorization_required`) |
| 8 renovaciones simultáneas **con cerrojo por sesión** | 8 OK, 8 refresh tokens distintos (rotación correcta), 0 eventos de reutilización |
| Suspender la cuenta y renovar | `invalid_grant` en 0,5 s, mientras la sesión de la aplicación sigue respondiendo 200 |

Consecuencias de diseño (obligatorias):
1. **Exclusión mutua por sesión** alrededor de toda renovación. Sin ella, dos peticiones HTMX paralelas bastan para que Clavaris interprete robo de token y destruya la sesión del usuario. Una instancia: cerrojo en memoria por ID de sesión; varias instancias: cerrojo en Redis.
2. `invalid_grant` ⇒ invalidar la sesión local. Error transitorio de Clavaris ⇒ tolerar un margen acotado (propuesta: 2 intentos o 5 min) y después fallar cerrado.
3. El refresh token vale 30 días y, por G2, **no se puede revocar**: se cifra en reposo en Redis (AES-GCM con clave propia, distinta de la de otros secretos), no se registra en logs y vive solo lo que dure la sesión (≤ 8 h).
4. Evento `refresh_token.reuse_detected` recibido por webhook ⇒ invalidar todas las sesiones de la cuenta.
5. Los webhooks `account.suspended`/`account.deleted` siguen como vía rápida; la renovación periódica es la red de seguridad independiente de ellos.

---

## 4. Configuración concreta de JobSeeker

**Spring Boot (verificado con el cliente de prueba):**

```yaml
spring.security.oauth2.client:
  registration.clavaris:
    client-id: ${CLAVARIS_CLIENT_ID}
    client-secret: ${CLAVARIS_CLIENT_SECRET}
    scope: openid,profile,email,offline_access   # opción B: prueba de vida con refresh token (G2)
    client-authentication-method: client_secret_basic
    authorization-grant-type: authorization_code
    redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
  provider.clavaris:
    issuer-uri: ${CLAVARIS_ISSUER_URI}     # {base}/o/{organizationId}
```

Cierre de sesión: `OidcClientInitiatedLogoutSuccessHandler` con `postLogoutRedirectUri` igual a una URI **registrada en el cliente** (`postLogoutRedirectUris`).

**Aprovisionamiento (lo hace un operador, no JobSeeker):**

1. Crear la `Organization` "JobSeeker" (`POST /api/v1/admin/organizations`, con token de plataforma y scope `platform:organizations:write`); anotar su `id` ⇒ `CLAVARIS_ISSUER_URI={base}/o/{id}`.
2. Registrar el `OAuthClient` (`POST …/organizations/{id}/clients`) con `redirectUris` (por ambiente), `postLogoutRedirectUris`, `allowedGrantTypes=[authorization_code]`, `allowedScopes=[openid,profile,email]`, y **`requireConsent:false`** (el valor por defecto es `true`, ADR-0017 de Clavaris; es un cliente propio y de confianza). El `clientSecret` se muestra **una sola vez**.
3. Configurar una cuenta de operador/entorno por ambiente (`DEVELOPMENT` vs `PRODUCTION`): las organizaciones nuevas nacen en `DEVELOPMENT` y se promueven con `:create-production-environment`.
4. (Pendiente de G1) credencial para el borrado de cuentas.
5. Registrar el webhook con una URL HTTPS pública (G5).

**Validación de la superficie `/api/v1/**` (G6):** el `JwtDecoder` por defecto valida firma, `iss` y `exp`, **no `aud`**. Como todos los clientes de la organización comparten clave de firma, hay que añadir un validador que exija `aud`/`azp` = `client_id` de JobSeeker; sin él, un token emitido a otro cliente de la misma organización sería aceptado.

---

## 5. Impacto en los documentos de JobSeeker

| Documento | Cambio necesario |
|---|---|
| ADR-0013 y `business-rules.md` BR-DATA-06 | Condicionar la cascada a G1; idempotencia (404 = éxito); token por ejecución |
| ADR-0020 | **Resuelve** su pregunta abierta sobre *back-channel logout*: no soportado. Añadir: `offline_access` con refresh token solo en servidor, cifrado, con cerrojo por sesión y renovación ≤ 15 min como prueba de vida (opción B, G2); invalidación adicional por webhook |
| `security-architecture.md` §2 | Vida real de los tokens (300 s / 1800 s); validación de `aud`; `name` ausente; sesión desacoplada del token |
| `data-model.md` §4.2 | `professional_profiles.full_name` es **dato propio de JobSeeker** (el IdP no lo provee): capturarlo en el onboarding |
| `integration-design.md` | Sección de Clavaris (aprovisionamiento, webhooks HTTPS, eventos, firma) |
| `.env.example` | Distinguir `CLAVARIS_ADMIN_*` (credencial de borrado) según G1; añadir `CLAVARIS_WEBHOOK_SECRET` |
| `roadmap-and-release-plan.md` §11 | El riesgo de Clavaris pasa a "contrato cerrado salvo G1"; añade tareas: receptor de webhooks, invalidación de sesiones, aprovisionamiento por ambiente |
| Desarrollo local | Para probar webhooks hace falta un túnel HTTPS público (Clavaris rechaza `localhost`); alternativa: reproducir la entrega con *replay* manual o con pruebas de contrato que firmen sus propios eventos |

---

## 6. Qué **no** se verificó

- ~~Entrega real de un webhook~~ **Verificada el 16-nov-2026** (addendum). El agotamiento de reintentos y la auto-desactivación se verificaron en una segunda sesión (addendum 2, con límites reducidos). Sigue sin probarse la entrega a un receptor de producción real.
- Login social, MFA/TOTP, passkeys, dominio propio y marca del login alojado.
- **Si Clavaris bloquea el login con correo sin verificar** (mi primer intento con correo sin verificar redirigió a `authorize`; no pude concluir el resultado). Hasta comprobarlo, JobSeeker debe exigir `email_verified=true` antes de confiar en el correo (p. ej. verificación de reclutador, REQ-R-10).
- El jar probado es del 13-nov-2026 01:14, **anterior al último commit** de Clavaris (14-nov-2026 19:08); repetir los puntos críticos (G1, G2) contra la versión actual antes de basar decisiones en ellos.
- Comportamiento en producción (Caddy, dominios, TLS, límites de tasa, Redis dedicado).
- Compatibilidad con Spring AI, pgvector/Hibernate, Bucket4j/Lettuce y Flyway/Supabase sobre Java 25: **sigue pendiente** (spike de bibliotecas). Este spike sí confirma que los starters `web`, `oauth2-client` y `oauth2-resource-server` de Boot 4.1.2 compilan y corren en Java 25.

---

## Addendum (16-nov-2026) — Verificación de webhooks de extremo a extremo

**Método.** Instancia aislada de Clavaris (jar del 13-nov), un receptor local con verificación de firma (`anexos/webhooks-clavaris/`) y un *Cloudflare Quick Tunnel* temporal (binario `cloudflared 2026.11.0`, SHA-256 comprobado contra la API de GitHub y las notas de la release). Datos de prueba únicamente (cuentas `@example.test`). Al terminar se cerró el túnel (la URL pasó a responder 530), se borraron binario y secretos y se retiraron los contenedores.

### Resultados

| Aspecto | Resultado |
|---|---|
| Registro del endpoint | ✅ `OrganizationClient` (scope `webhook-endpoints:write`) → **201**; Clavaris acepta un host público como `*.trycloudflare.com` (el control SSRF solo bloquea rangos privados/loopback) |
| Entrega y firma | ✅ 4/4 eventos (`account.created/suspended/reactivated/deleted`) con firma HMAC-SHA256 válida y tiempo dentro de tolerancia. Cabeceras reales: `Clavaris-Signature`, `-Event-Type`, `-Event-Id`, `-Delivery-Id`, `-Trace-Id` |
| Latencia (evento → recepción, incl. túnel) | **1,2 – 5,4 s** |
| Payload | `account.created` y `account.deleted` **incluyen el correo electrónico**; `suspended` y `reactivated` solo `accountId`, `organizationId`, `occurredAt`. Los identificadores van anidados: `{"accountId":{"value":…}}` |
| Reintentos | ✅ Con 2 fallos inyectados: intento 1 a +5 s, **reintento a +35 s** y siguiente a **+61 s** (30 s duplicándose, ±20 % de variación; en el código: tope de 1 h por intento). **Mismo `Event-Id` y mismo `Delivery-Id`** en todos los intentos |
| Contador de fallos | ✅ Tras recuperarse dentro de los reintentos, `consecutiveFailures` vuelve a **0**. Solo cuentan las entregas *agotadas*; al alcanzar el umbral, Clavaris pone `active=false` y `autoDisabledAt` (leído en código; no se agotó una entrega en la prueba) |
| Replay manual | `POST …/deliveries/{id}:replay` con la credencial de plataforma → 200 y reentrega con el **mismo `Event-Id` y `Delivery-Id`**; con `OrganizationClient` → **403** aun con el scope de replay |
| Historial de entregas | `GET …/deliveries` con `OrganizationClient` → **403**; listar endpoints (con `active`, `consecutiveFailures`, `autoDisabledAt`) → **200** |
| Rotación de secreto | `:rotate-secret` con `OrganizationClient` → **403**; con plataforma → 200. Durante el solape cada entrega lleva **dos firmas `v1`** y valida con **solo el secreto nuevo o solo el antiguo** ✅ |
| Seguridad del receptor (prototipo) | ✅ 401 sin firma; 401 con timestamp de hace 10 min (replay); 401 con cuerpo alterado tras firmar |

### Consecuencias para JobSeeker

1. **Tercera superficie de seguridad** (ADR-0020): `/api/v1/webhooks/clavaris` no usa sesión ni Bearer ni CSRF; se autentica **solo por firma HMAC**, con tolerancia de tiempo, límite de tamaño, `rate limit` y sin sesión. Debe quedar excluida de las otras dos cadenas.
2. **Deduplicación por estado procesado, no por recepción.** Los reintentos y el *replay* reutilizan `Event-Id`/`Delivery-Id`: el consumidor debe registrar el evento como procesado **en la misma transacción que su efecto** (tabla `processed_events` del ADR-0016). Si se marca al recibir, un reintento tras un fallo se descarta y el efecto se pierde; y un *replay* manual solo sirve si el evento no figura como procesado.
3. **El payload contiene PII (correo)**: no registrarlo (BR-DATA-01) y tratarlo como dato transitorio; JobSeeker no debe persistirlo (consistente con `accounts` sin `email`).
4. **Respuesta rápida y asíncrona:** responder 2xx tras verificar y persistir en el *outbox* local; procesar después (ADR-0016). Un receptor lento entra en la política de reintentos.
5. **Vigilancia del endpoint:** JobSeeker puede leer `active`/`consecutiveFailures`/`autoDisabledAt` con su credencial de organización: añadir una comprobación periódica y una alerta si `active=false` (un endpoint auto-desactivado deja de recibir `account.suspended/deleted` y la invalidación de sesiones, ADR-0020, dejaría de funcionar sin aviso).
6. **Reparto de capacidades por credencial:** la credencial de organización sirve para **registrar y listar** endpoints; **rotar secretos, leer entregas y hacer *replay*** exigen la credencial de plataforma. Esas operaciones quedan como tareas de **operador**, no del servicio.
7. **Desarrollo local:** Clavaris rechaza `localhost`; para probar webhooks hace falta un túnel HTTPS (se verificó que un *Quick Tunnel* funciona) o pruebas de contrato que firmen sus propios eventos con el esquema verificado aquí.

---

## Addendum 2 (16-nov-2026) — Orden, agotamiento, desactivación y reconciliación

**Método.** Segunda sesión con el mismo montaje (túnel temporal, receptor con verificación de firma, instancia aislada), con los límites de reintento de Clavaris bajados por variables de entorno para no esperar horas: `CLAVARIS_WEBHOOK_DELIVERY_MAX_ATTEMPTS=2` y `CLAVARIS_WEBHOOK_ENDPOINT_AUTO_DISABLE_THRESHOLD=2` (los valores por defecto son **8** y **5**). El endpoint se suscribió a los **28 tipos** del catálogo. Al terminar se cerró el túnel (la URL pasó a responder 530) y se borraron binario y secretos.

### Resultados

| Aspecto | Resultado |
|---|---|
| **Orden de llegada** | ❌ **No garantizado.** En una ráfaga de 6 cambios de estado sobre una cuenta (0,7 s), el primer par llegó invertido: `reactivated` (occurredAt …26.070) **antes** que `suspended` (…25.970); los demás llegaron en orden. 12 entregas, 12 eventos distintos, 0 duplicados; 6 altas simultáneas correctas |
| Tipos de evento observados | `account.created`, `suspended`, `reactivated`, `deleted`, `profile_updated`, `refresh_token.reuse_detected`. `:force-password-reset` **no** produjo `password_reset.requested` en 25 s. El catálogo real tiene **28** tipos (incluye `email_changed`, `password_changed`, `totp_*`, `registration_*`, `social_identity.*`, `workspace*`) |
| PII en los payloads | `created` y `deleted`: **correo**. `profile_updated`: **nombre, apellido, teléfono y usuario**. `suspended`, `reactivated`, `reuse_detected`: solo `accountId`, `organizationId`, `occurredAt` |
| Agotamiento y auto-desactivación | ✅ Con el receptor caído (el túnel respondía 502): a los **+11 s** las entregas pasaron a `FAILED`, a los **+43 s** a `EXHAUSTED` y el endpoint quedó `active=false`, `consecutive_failures=2`, `auto_disabled_at` fijado |
| **Eventos durante la desactivación** | ❌ **Se pierden.** `matchedEndpointCount=0`, no se crea ninguna fila de entrega, y el evento del *outbox* se marca como publicado. No hay cola ni recuperación |
| Reactivación | `:activate` con `OrganizationClient` → **403**; con plataforma → 200 (`active=true`, `consecutive_failures=0`, `auto_disabled_at=null`). **No se entrega nada atrasado**; los eventos nuevos sí |
| API de lectura de cuentas | `GET /api/v1/admin/accounts/{id}/profile` responde 200 también a la credencial de organización **si** lleva el scope `platform:accounts:profile:write`. Devuelve `id, email, firstName, lastName, username, phoneNumber` y **no incluye el estado** (la cuenta suspendida aparece sin marca alguna). **No existe listado de cuentas** |

**Tiempos con los valores por defecto (calculados del código, no observados).** El calendario entre intentos es 30 s duplicándose con tope de 1 h y ±20 %: con 8 intentos, una entrega tarda ≈ **64 minutos** en agotarse. Como el umbral cuenta entregas agotadas consecutivas (no tiempo), **5 eventos que fallen a la vez pueden desactivar el endpoint en ≈ 1 hora**. Un comentario del código de Clavaris habla de "cerca de un día" por entrega; esa discrepancia no está resuelta.

### Consecuencias para JobSeeker

1. **Los webhooks son una optimización, no la fuente de verdad.** Pueden llegar desordenados, repetirse y **perderse por completo** (endpoint caído más de ≈ 1 h con 5 eventos, o desactivado). La lógica de seguridad no puede depender de que lleguen.
2. **Orden:** aplicar cada evento solo si su `occurredAt` es **mayor** que el último aplicado para esa cuenta (guardar `last_event_at` por cuenta), o, mejor, tratar el evento como "algo cambió" y releer el estado. Nunca "gana el último en llegar".
3. **La suspensión no se puede reconciliar por API de administración** (no hay campo de estado). Un borrado sí se detectaría por 404 (según el código; no probado). Lo que da una respuesta fiable de "¿sigue activa esta cuenta?" es la **concesión de refresh token**: tras suspender o borrar, `refresh_token` devuelve `invalid_grant` (verificado en el spike). Esto reabre una decisión del ADR-0020 (ver abajo).
4. **Tras cualquier hueco de eventos** (endpoint desactivado, caída de JobSeeker, despliegue largo): **invalidar todas las sesiones anteriores al inicio del hueco** y forzar nuevo inicio de sesión; es barato, seguro e idempotente, y no requiere saber qué evento se perdió.
5. **Monitorizar `active`/`autoDisabledAt`** del endpoint (legible con la credencial de organización) y alertar de inmediato; la reactivación es tarea de **operador** (credencial de plataforma) y debe ir seguida del paso 4.
6. **Reducir la ventana de pérdida:** el receptor debe responder 2xx en milisegundos (persistir y encolar, ADR-0016) para que los reintentos de Clavaris no se acumulen por lentitud propia.
7. **PII de los payloads:** no registrarlos ni persistirlos; el receptor solo necesita `accountId`, `organizationId`, `occurredAt` y el tipo.

---

## Addendum 3 (16-nov-2026) — Concurrencia con dos instancias y sesión en Redis

**Montaje:** dos instancias de la sonda (puertos 9090 y 9091, Spring Boot 4.1.2, `@EnableRedisIndexedHttpSession`) contra el mismo Redis y un Clavaris aislado; las peticiones se reparten al azar entre ambas. Cerrojo: `SET NX PX` con token propio y liberación mediante script Lua (solo libera quien lo tiene). Dos errores de la propia sonda (`@RequestParam` sin nombre y un ciclo de beans) produjeron 500 en las primeras ejecuciones y se corrigieron antes de interpretar nada.

| # | Escenario | Resultado |
|---|---|---|
| 1 | 8 simultáneas, sin cerrojo | 1 OK, 7 `invalid_grant`, 7 `reuse_detected` |
| 2 | 8 simultáneas, cerrojo Redis + cliente leído de la **sesión** | 1 OK, 7 `invalid_grant`, 7 `reuse_detected` (copia de sesión obsoleta) |
| 3 | 8 simultáneas, cerrojo Redis + cliente releído de **Redis** | 8/8 OK, 8 tokens distintos, 0 reutilizaciones |
| 4 | 24 simultáneas, igual | 24/24 OK; espera máxima 5,8 s (renovaciones forzadas en serie) |
| 5 | 6 sesiones × 8 simultáneas | 48/48 OK, sin contención cruzada |
| 6 | Instancia muerta (`kill -9`) con el cerrojo tomado, TTL 8 s | La otra espera 5,6 s y continúa; 0 reutilizaciones |
| 7 | El cerrojo expira (TTL 2 s) con el dueño renovando 5 s después de leer el token | B rota el token; A lo reutiliza ⇒ `invalid_grant`, 1 `reuse_detected`, sesión destruida |

**Hallazgo adicional:** indexar el cliente autorizado por ID de sesión falla porque el ID rota tras el login; la sonda lo detectó como `client_authorization_required` incluso en secuencial.

**Consecuencias:** reglas 5–8 añadidas a ADR-0020 (relectura dentro del cerrojo, identificador estable, TTL ≫ renovación con *timeout* HTTP, renovación solo cuando toca).

**No verificado:** *fencing* o comparación-e-intercambio del token antes de usarlo (reduciría, no eliminaría, la ventana del escenario 7); latencia de Redis en un despliegue real; comportamiento con Redis caído (ADR-0020 ya acepta que cierra sesiones). Mediciones de tiempo hechas en una VM sin AVX y con contención: son orientativas.

---

## Addendum 4 (16-nov-2026) — Receptor de webhooks bajo concurrencia

**Montaje:** receptor de prueba en Spring Boot 4.1.2 + Postgres 16 (hasta 48–60 hilos del cliente, pool de 40 conexiones) con la firma real de Clavaris (HMAC-SHA256 sobre `"<t>.<cuerpo>"`, tolerancia 300 s). Los eventos son **simulados** con tipos representativos (no la lista real de 28) y firmados por el cliente de prueba; el formato de entrega ya estaba verificado contra Clavaris real (addendum 1). Carga: 30 cuentas, 385 eventos únicos, cada uno entregado 3 veces y mezclados al azar, con 5 cuentas con `account.deleted` y 10 con un empate exacto de `occurredAt` entre estados opuestos.

| # | Escenario | Resultado |
|---|---|---|
| A | Dedup por `SELECT` previo, sin restricción única | **8 eventos con efecto duplicado** (393 efectos para 385 eventos) |
| B | Dedup con `ON CONFLICT`, orden solo por `occurredAt` | 0 duplicados, pero **7 de 30** cuentas con estado distinto del correcto (todas con empate); con otro orden de llegada, 4 (distintas): resultado **no determinista** |
| C | Dedup con `ON CONFLICT`, orden por `(occurredAt, event_id)` | 0 duplicados, **0 de 30** distintas del esperado; igual con otro orden de llegada |
| D | Excepción tras aplicar el efecto, antes del commit | HTTP 500; **no queda rastro** (ni evento marcado ni efecto); el reintento aplica 1 vez; 10 reentregas simultáneas posteriores: todas «duplicado», 1 efecto |
| E | 5 entregas simultáneas del mismo evento, la primera fallando | 1 aplicada, 4 duplicadas, 1 fallo 500; 1 efecto en total |
| F | 25 % de entregas con firma manipulada mezcladas con tráfico válido (60 hilos) | Todas las manipuladas 401 y sin efecto; marca de tiempo antigua 401 |

**Conclusiones (reflejadas en ADR-0020):** (1) la deduplicación necesita una restricción única en la misma transacción que el efecto: si el efecto falla, el evento sigue siendo reintentable y nunca se pierde; (2) comparar solo `occurredAt` es insuficiente: hace falta un desempate determinista; (3) el borrado es terminal y no depende del orden; (4) ninguna ruta de error dejó un efecto sin su marca de procesado.

**No verificado:** eventos reales con `occurredAt` idéntico (no se observó ningún empate en las entregas reales; se probó por ser posible); rendimiento con tablas grandes y retención de `wh_processed` (hace falta una política de purga, p. ej. ≥ 30 días, mayor que cualquier ventana de reintento de Clavaris); rotación de secreto bajo carga; semántica real de cada tipo de evento (los estados del mapa son ilustrativos); tiempos en una VM sin AVX y con contención.

---

## Addendum 5 (17-nov-2026) — Validación contra el Clavaris corregido (TD-SEC-100 y TD-SEC-101)

**Qué se probó:** `master` de Clavaris (commit `a7d2dbdd`, rama `fix/refresh-token-revocation-and-org-client-erasure`, ya integrada en `master` local), compilado el 17-nov-2026 (el jar anterior era del 13-nov y no contenía el cambio). Instancia aislada, dos organizaciones (A y B), clientes OAuth con `offline_access` y una Secret Key por organización con los scopes `accounts:delete`, `accounts:suspend`, `webhook-endpoints:write` y `webhook-deliveries:replay`. Diseño implementado: ADR-0044 de Clavaris.

### TD-SEC-100 (revocación de refresh tokens): **corregido, 21 de 21 comprobaciones correctas** (corrección del 18-nov-2026: este documento decía 22; eran 21)

| Caso | Resultado |
|---|---|
| Revocar un refresh token con `hint=refresh_token`, sin hint y con `hint=access_token`, luego refrescar | 200 y después `invalid_grant` (antes: el token seguía funcionando) |
| Revocar el token **anterior** (ya rotado) | La sesión entera muere: el vigente también da `invalid_grant` |
| Revocar dos veces; valor desconocido | 200 en ambos casos (idempotente y sin oráculo) |
| Cliente de otra organización revoca en su propio endpoint | 200, pero **no** revoca el token de A; las credenciales de B contra el endpoint de A dan 401 |
| Cierre de sesión (RP-Initiated Logout) con `id_token_hint` válido | Redirección 302 y el refresh token muere |
| Logout con hint forjado, o con `client_id` de otra organización | No revoca nada |
| Access token ya emitido tras revocar | Sigue válido hasta expirar (límite que Clavaris documenta en el ADR-0044) |

### TD-SEC-101 (OrganizationClient gestiona sus cuentas y webhooks): **implementado, sin desviaciones, todas las comprobaciones correctas**

- **Cuentas propias:** suspender (204), reactivar (204) y borrar (2xx) funcionan; el refresh token de una cuenta suspendida o borrada da `invalid_grant`; la cuenta suspendida y reactivada vuelve a iniciar sesión; la borrada desaparece de la base (comprobado por id y por correo) y el evento queda en el *outbox*.
- **Aislamiento entre organizaciones:** suspender o borrar una cuenta de B con la Secret Key de A da 403 y la cuenta de B queda intacta; un UUID inexistente o un id que no es UUID dan 403 (sin oráculo entre «tuyo» y «de nadie»).
- **Sigue prohibido:** borrar la organización, mintear otra Secret Key y listar organizaciones (403).
- **El scope manda:** una Secret Key sin `accounts:delete` o sin `accounts:suspend` no puede borrar ni suspender; con solo `webhook-endpoints:write` puede gestionar su endpoint pero no suspender ni reenviar entregas; sin token, 401.
- **Webhooks propios:** desactivar, activar (estado verificado en la base), rotar secreto y leer entregas funcionan; reenviar una entrega inexistente pasa el filtro y responde 404 (decide el caso de uso). Los mismos cinco sobre un endpoint de B dan 403 y el endpoint de B sigue activo.

### Regresión del diseño de ADR-0020

8 refrescos simultáneos con el mismo token: 1 correcto y 7 `invalid_grant`, con 7 eventos `refresh_token.reuse_detected`: **idéntico a la medición del 16-nov**, así que el cerrojo por sesión sigue siendo obligatorio.

### Consecuencias para JobSeeker

1. **La decisión B se mantiene** (la prueba de vida necesita el refresh token), pero **desaparece el argumento de «no revocable»**: el cierre de sesión de JobSeeker debe llamar a `/oauth2/revoke` con el refresh token (o al `end_session_endpoint`) y ahora termina la sesión en Clavaris. El cifrado en reposo del refresh token se conserva como defensa en profundidad.
2. **El *job* de anonimización puede usar una Secret Key** (`OrganizationClient`) con solo `platform:accounts:delete`, en lugar del credencial de plataforma. Se elimina el riesgo de G1; se conservan el 404 como éxito, los reintentos con espera exponencial y un token nuevo en cada ejecución (299 s).
3. El borrado de una cuenta con Secret Key emite el evento de webhook y el refresh token queda inválido en el acto.

### No verificado

- La **entrega real** al receptor de los eventos generados por un borrado o suspensión hechos con Secret Key: se comprobó el evento en el *outbox*, no su llegada a una URL pública (habría requerido el túnel, que solo se autorizó para la sesión de pruebas anterior).
- El `access token` emitido antes de la revocación sigue siendo válido hasta 5 min (decisión de Clavaris); JobSeeker no debe depender de su revocación inmediata.
- Comportamiento con Redis caído de Clavaris (cambio `fix(cache)` del mismo conjunto de commits) y la política de PKCE opcional (ADR-0043): fuera del alcance de esta validación.

---

## Addendum 6 (17/18-nov-2026) — Entrega real de webhooks con Secret Key, Redis caído y política de PKCE

**Montaje:** Clavaris actualizado (commit `a7d2dbdd`), instancia aislada. Para la entrega real se abrió un túnel temporal (versión de `cloudflared` verificada contra el digest publicado por GitHub), con un receptor que solo acepta peticiones con firma válida; al terminar se cerró (la URL pública dejó de responder, HTTP 530) y se borraron el binario y el secreto de firma. Anexo sin datos personales: `anexos/webhooks-clavaris/eventos_secret_key_sin_pii.json`.

### 1. Entrega real de los eventos generados con una Secret Key

Ciclo hecho con la Secret Key de la organización (crear cuenta, suspender, reactivar, borrar) más una reutilización de refresh token y una revocación:

| Acción | Evento recibido | Resultado |
|---|---|---|
| Alta de cuenta | `account.created` | firma válida, 200 |
| Suspender / reactivar (Secret Key) | `account.suspended`, `account.reactivated` | firma válida, 200 |
| Borrar (Secret Key) | `account.deleted` | firma válida, 200 |
| Reutilizar un refresh token | `refresh_token.reuse_detected` | firma válida, 200 |
| Revocar un refresh token (`/oauth2/revoke`) | **ningún evento** | no hay `account.session_revoked` ni otro |

- Las 5 entregas figuran como `SUCCEEDED` con 1 intento al consultarlas con la propia Secret Key.
- **Orden real, confirmado con eventos auténticos:** `reuse_detected` (ocurrió a las 03:05:21) llegó **después** de `deleted` (03:05:27). Confirma la regla del ADR-0020: el orden de llegada no es el orden de los hechos; se decide por `occurredAt`.
- **Latencia** de 2 a 10 s desde la acción (el sondeo del *outbox* agrupa eventos).
- `account.created` y `account.deleted` llevan el correo en el payload; los demás solo `accountId`, `organizationId` y `occurredAt`.
- **Consecuencia:** el cierre de sesión de JobSeeker contra Clavaris no genera webhook; JobSeeker no puede enterarse por esa vía de una revocación hecha desde otro cliente. La prueba de vida por renovación (decisión B) es la que cubre ese hueco.

### 2. Redis caído en Clavaris

| Operación | Con Redis caído | Observación |
|---|---|---|
| `liveness` | 200 | instantáneo |
| `readiness` | **503** | ~2 s |
| Renovar refresh token existente | **OK** | rápido (0,03 s): es lo que hará JobSeeker cada ≤ 10 min |
| `userinfo` | 200 | ~2 s más lento |
| `client_credentials` de la Secret Key | OK | |
| Revocar un refresh token | 200 y después `invalid_grant` | funciona sin Redis |
| **Suspender / borrar con Secret Key** | **500, y no se aplica nada** | la cuenta sigue ACTIVE o existente y su refresh sigue OK (~4 s por intento) |
| Login interactivo y login fallido | 500 / sin tokens (~24 s) | la sesión HTTP vive en Redis |
| Limitador de tasa | falla **abierto** (`rate_limit_fail_open`) | según TD-SEC-022 |

- La suspensión y el borrado fallan **cerrados y de forma atómica**: la causa es `AccountRevocationCascade` → `AccountSessionRevokerBridge`, que necesita Redis para cerrar las sesiones HTTP de la cuenta. No queda ningún estado a medias, pero **las operaciones de ciclo de vida no están disponibles mientras Redis esté caído**.
- **Recuperación:** al reiniciar Redis, `readiness` vuelve a 200 en ~9 s sin reiniciar Clavaris; el login y la suspensión funcionan de nuevo.
- **Hallazgo: Clavaris no arranca con Redis caído**, contra lo que declara el commit `72d0edd6`. El reintento de la suscripción de invalidación funciona (`event=cache_invalidation_subscribe_failed … retryInSeconds=10`), pero el contexto falla después por otro bean: `enableRedisKeyspaceNotificationsInitializer`, de Spring Session (`RedisIndexedHttpSessionConfiguration`), que intenta configurar Redis al arrancar (`Unable to connect to Redis`). El proceso termina («Application run failed») y no llega a servir peticiones.

> **Registrado en Clavaris el 18-nov-2026:** el arranque con Redis caído como `TD-ARCH-056` (P1) y el fallo de suspender o borrar como `TD-ARCH-057` (P2), en `../Clavaris/docs/05-engineering/technical-debt-register.md`.

### 3. Política de PKCE (ADR-0043 de Clavaris): conforme

- **Por defecto, `REQUIRED`**: registrar sin `pkcePolicy` da `REQUIRED`; un valor inválido da 400. Sin `code_challenge` se rechaza (`invalid_request`); `plain` se rechaza con cualquier política.
- **`OPTIONAL`**: acepta autorizar sin challenge y canjear sin verificador. Con challenge, el verificador sigue siendo obligatorio (ausente o incorrecto: `invalid_grant`). Un verificador sobre un código sin challenge se rechaza (**protección contra *downgrade* verificada**).
- **Interruptor de plataforma** (`OAUTH_PKCE_ALLOW_OPTIONAL=false`): registrar un cliente `OPTIONAL` da 400; un cliente ya guardado como `OPTIONAL` queda sujeto a PKCE sin tocar su fila (la política almacenada sigue siendo `OPTIONAL`); `REQUIRED` no cambia.
- **El cliente real de Spring Security (JobSeeker) funciona con `REQUIRED`:** envía `code_challenge_method=S256` por sí solo y el login completo funciona. Con el Clavaris actualizado se repitió el cerrojo: 8/8 con cerrojo; sin cerrojo 1 OK y 7 `invalid_grant` con 7 reutilizaciones.

### Consecuencias para JobSeeker

1. **El cliente de JobSeeker debe registrarse con la política por defecto (`REQUIRED`); no hace falta `OPTIONAL`.**
2. Con Redis caído en Clavaris, la renovación (prueba de vida) y la revocación **siguen funcionando**; el *job* de anonimización recibirá **500 de forma transitoria** al borrar: debe tratarlos como reintentables (espera exponencial, token nuevo en cada intento) y no marcar la cuenta como borrada hasta recibir 2xx.
3. **JobSeeker tendrá el mismo problema de arranque:** `@EnableRedisIndexedHttpSession` registra el mismo inicializador de Spring Session. Hay que declarar `ConfigureRedisAction.NO_OP` (y fijar `notify-keyspace-events` en el servidor de Redis, necesario también en Redis gestionado donde `CONFIG` suele estar deshabilitado) y probar el arranque con Redis caído en Sprint 0.

### No verificado

- El arranque de Clavaris con Redis caído **tras** quitar el inicializador de Spring Session (no se modificó Clavaris).
- El comportamiento de las sesiones web de JobSeeker con Redis caído (ADR-0020 ya acepta que se cierran).
- La entrega de eventos tras un reintento fallido contra un endpoint caído en este montaje (cubierta en los addendums 1 y 2).

---

## Addendum 7 (18-nov-2026) — Validación completa contra el Clavaris con ADR-0045 (`master` `c10281b3`)

**Qué cambió desde la validación anterior (`a7d2dbdd`):** ADR-0045 (arranque con Redis caído y cierre diferido de sesiones, migración `pending_session_closures`), ADR-0043 aprobado (umbral de 45 días para cerrar el PKCE opcional), el Redis de los *compose* pasa a arrancar con `--notify-keyspace-events Egx`, un candado en el reenvío del correo de verificación y varias actualizaciones de dependencias (Tomcat 11.0.29, BouncyCastle 1.87, AWS SDK, Maven, Pitest). No se tocó `webhook-module`. Instancia aislada, jar compilado el 18-nov, dos organizaciones y Secret Keys por organización.

### Regresión (todo lo verificado antes sigue igual)

| Área | Resultado |
|---|---|
| Revocación de refresh tokens y logout (TD-SEC-100) | 21 de 21 correctas |
| `OrganizationClient` gestiona sus cuentas y webhooks, aislamiento entre organizaciones, scopes (TD-SEC-101) | 17 + 18 + 6 correctas; sin token, 401 |
| PKCE `REQUIRED`/`OPTIONAL`, `plain` rechazado, *downgrade* rechazado, registro con valor inválido | 10 de 10 en el endpoint de tokens más los casos de autorización |
| Interruptor `OAUTH_PKCE_ALLOW_OPTIONAL=false` | registrar `OPTIONAL` da 400; un cliente ya `OPTIONAL` exige challenge y conserva su política; `REQUIRED` sin cambios |
| Cliente real de Spring Security (JobSeeker) | PKCE S256 automático; 8/8 con cerrojo; sin cerrojo 1 OK y 7 `invalid_grant` con 7 reutilizaciones; la suspensión se detecta en la renovación mientras la sesión de la app sigue viva |

Tres ❌ de esta sesión fueron del arnés y no de Clavaris: se superó el límite de login por IP (429, lo que demuestra que el limitador actúa), un script con la comprobación de scope mal planteada, y clientes de PKCE registrados sin `requireConsent:false` (el flujo se detenía en la pantalla de consentimiento). Se corrigieron y se repitieron.

### TD-ARCH-056 (arranque con Redis caído): **corregido**

- Con Redis detenido, Clavaris **arranca** (liveness 200, readiness 503, sin «Application run failed»), con los eventos `cache_invalidation_subscribe_failed` y `session_listener_start_failed … retryInMillis=10000` y reintentos en segundo plano.
- Recién arrancado sin Redis: renovar un refresh token y obtener un token de `client_credentials` funcionan. Registrar cuentas nuevas y el login interactivo no (necesitan sesión).
- Al volver Redis, `readiness` pasa a 200 en 1–10 s **sin reiniciar**, y el login interactivo funciona.
- **La aplicación ya no configura Redis:** con un Redis sin `notify-keyspace-events`, arranca, sirve y deja el servidor tal cual (`''`); el requisito pasa a ser de despliegue (el Redis de los *compose* ya lo lleva).

### TD-ARCH-057 (suspender y borrar con Redis caído): **corregido para cuentas**

| Prueba (Redis caído) | Resultado |
|---|---|
| Suspender, borrar, reactivar con Secret Key | **204** (antes 500); estado aplicado al instante; el refresh deja de funcionar; ~2–4 s por llamada (los tiempos de espera del limitador de tasa) |
| Filas en `pending_session_closures` | una por cuenta, sin duplicados (6 suspensiones simultáneas de la misma cuenta: 1×204 y 5×409, 1 fila) |
| Eventos de webhook | quedan en el *outbox* |
| Redis vuelve | las filas se vacían en ~10–20 s; las sesiones de navegador de las cuentas suspendidas, **cerradas**, incluida la reactivada antes del trabajo (lado seguro) |
| Cuenta no pendiente | su sesión de navegador sigue activa tras 35 s |

### Hallazgo nuevo: el borrado de una organización con Redis caído deja trabajo sin hacer

Reproducido de forma comparable con dos organizaciones de 3 cuentas (archivo de claves comprobado antes y después, en `data/`):

| Organización | Archivo de claves tras `:delete` | Filas pendientes | Log |
|---|---|---|---|
| Con Redis **sano** | purgado | n/a | sin fallo |
| Con Redis **caído** | **sigue existiendo, también 40 s después de volver Redis** | **0** | `event=after_commit_action_failed exceptionType=InvalidDataAccessApiUsageException` |

- El borrado responde 204 y las cuentas y la organización desaparecen, pero la acción posterior al commit (`OrganizationIdentityDataEraserBridge`, que cierra las sesiones de cada cuenta y **después purga el material de claves de firma**, TD-SEC-052) falla al intentar registrar la fila pendiente, y la purga no llega a ejecutarse ni se reintenta.
- Causa probable, **no probada**: `JpaPendingSessionClosureRepository.add` es `@Transactional` y se invoca desde `afterCommit`, donde se une a una transacción ya completada; la inserción debería ir en una transacción nueva (`REQUIRES_NEW`).
- **Impacto:** las claves privadas de firma de una organización eliminada permanecen en disco de forma indefinida. No afecta a JobSeeker (no borra organizaciones) y exige una caída de Redis coincidente con el borrado de una organización, pero contradice el motivo de TD-SEC-052.

> **Registrado en Clavaris el 18-nov-2026 como `TD-SEC-102` (P2, Pending)** en `../Clavaris/docs/05-engineering/technical-debt-register.md`, con la corrección propuesta (`REQUIRES_NEW` y purga de claves independiente del cierre de sesiones) y la prueba que faltaba. **Corregida por Clavaris y validada el 19-nov-2026 (addendum 8).**

### Consecuencias para JobSeeker

1. **El *job* de anonimización ya no necesita reintentar por Redis caído**: suspender, reactivar y borrar con Secret Key responden 204. Sigue necesitando reintento ante cualquier otro 5xx o fallo de red.
2. Un borrado hecho con Redis caído es efectivo al instante (credenciales y refresh tokens revocados en Postgres); solo una sesión de navegador ya abierta tarda hasta ~20 s tras volver Redis en cerrarse.
3. La regla 9 del ADR-0020 se mantiene (`ConfigureRedisAction.NO_OP`), ahora **confirmada**: Clavaris la aplica y arranca contra un Redis sin la opción.

### No verificado

- Entrega real de webhooks con el jar actual: no se reabrió el túnel; `webhook-module` no cambió en este rango de commits y la entrega ya estaba verificada en el addendum 6.
- El candado nuevo en el reenvío del correo de verificación y las actualizaciones de dependencias, salvo lo que cubre la regresión anterior.
- Que Clavaris arranque con Redis caído en un despliegue real con *compose*, y el contador de PKCE opcional (métrica no consultada).

---

## Addendum 8 (19-nov-2026) — Validación contra el Clavaris con ADR-0046 (`f25d5838`)

**Qué cambió desde `c10281b3`:** 73 archivos. Lo relevante: ventana de reutilización del refresh token por organización (ADR-0046, mi `TD-SEC-103`); purga de claves y cierres de sesión diferidos al borrar una organización con Redis caído (`TD-SEC-102`); refresh token **vinculado al cliente** al que se emitió; `POST` anónimo a `/authorize` respondido como `GET`; correo solo en `userinfo`. Jar compilado el 18-nov; mismo montaje aislado (Postgres 16, Redis 7 con `notify-keyspace-events Egx`).

### Regresión (lo ya validado sigue igual)

| Área | Resultado |
|---|---|
| Revocación de refresh tokens y logout (`TD-SEC-100`) | 21 de 21 |
| `OrganizationClient`: cuentas propias, aislamiento, scopes, webhooks (`TD-SEC-101`) | 17 + 17 + 6 correctas |
| PKCE (`REQUIRED`/`OPTIONAL`, `plain`, *downgrade*) | 13 de 13 |
| Redis caído: suspender y borrar cuentas con Secret Key | 204 y estado aplicado al instante; el refresh deja de funcionar; filas diferidas 1 y 2; al volver Redis, 0 filas en 7 s y la sesión de navegador de la cuenta suspendida **cerrada**, la de control activa |
| Arrancar Clavaris con Redis detenido | liveness 200 a los 38 s, readiness 503, sin «Application run failed»; al volver Redis, readiness 200 en 1 s sin reiniciar |

(Hubo un ❌ de mi propio script, la misma comprobación mal planteada de `skC` que ya había identificado; las seis comprobaciones correctas que la sustituyen pasan.)

### `TD-SEC-102` (borrado de organización con Redis caído): **corregido**

| | Antes (`c10281b3`) | Ahora |
|---|---|---|
| Archivo de claves de la organización tras `:delete` con Redis caído | seguía en disco, también tras volver Redis | **purgado** |
| Filas en `pending_session_closures` | 0 | **3** (una por cuenta), vaciadas al volver Redis |
| `after_commit_action_failed` en el log | sí | **0** |
| Con Redis sano | purgado | purgado |

### `TD-SEC-103` (ventana de reutilización): **implementada tal como la describe el ADR-0046**

Por defecto la ventana vale **0 (apagada)**; es por organización, de 0 a 60 s, y solo un operador (`platform:session-policy:write`) puede fijarla: una Secret Key de organización recibió 403 al intentar cambiarla. El `PUT` reemplaza la política entera (no hay lectura por la API).

| Escenario | Resultado |
|---|---|
| Ventana apagada, respuesta perdida (proxy que retrasa 9 s, cliente espera 5 s) | Igual que antes: la sesión muere con `invalid_grant` |
| Ventana de 30 s, el cliente **reintenta a los ~8 s** | **Recupera**: devuelve un token nuevo, la sesión continúa; evento `refresh_token.reuse_graced` (distinto de `reuse_detected`) |
| Ventana de 30 s, reintento **a los ~41 s** | `invalid_grant` (fuera de la ventana) |
| **Robo:** el cliente ya usó el sucesor y alguien presenta el token viejo dentro de la ventana | `invalid_grant` y **se revoca toda la sesión** (`reuse_detected`): la ventana no debilita esta regla |
| **8 presentaciones simultáneas** del mismo token (ventana de 30 s) | **1 correcta y 7 `invalid_grant`**, 7 `reuse_detected`, 0 «toleradas»; el único token devuelto quedó muerto |
| Cliente real de Spring, 8 peticiones simultáneas sin cerrojo | Igual: 1 correcta, 7 fallos, y la sesión murió (`client_authorization_required`) |

**Conclusiones para JobSeeker:**
1. La ventana **ayuda solo a un reintento cercano** (hasta 60 s tras la rotación). No rescata una caída larga: tras horas sin Clavaris, el reintento llega fuera de la ventana.
2. **No sustituye al cerrojo por sesión:** la concurrencia sigue siendo fatal, porque quien pierde la carrera por el sucesor cae en el camino de reutilización normal. El cerrojo sigue siendo obligatorio.
3. Es una **configuración del operador de Clavaris** que debe hacerse para la organización de JobSeeker (requisito de despliegue): 30 s es un valor razonable con una lectura de 10 s.

### Otros cambios

| Cambio | Resultado |
|---|---|
| Refresh token vinculado al cliente (`e1b326ae`) | Otro cliente de la **misma organización** recibe `invalid_grant` y el token **no se consume** (el dueño lo sigue usando); 0 eventos de reutilización. JobSeeker tiene un único cliente: sin efecto |
| `POST` anónimo a `/authorize` (`248b4883`) | 303 hacia el mismo flujo como `GET` |
| Correo solo en `userinfo` (`36e46014`) | El ID token ya **no** lleva `email` ni `email_verified`; `userinfo` devuelve `email`, `email_verified`, `sub` y `picture`. El inicio de sesión con el cliente de Spring funcionó con normalidad (JobSeeker solo consume `sub`) |

### No verificado
- `TD-FUT-081` (lectura de estado de cuenta): **sigue sin implementar** (implementada después; ver addendum 12).
- `OrphanedSigningKeyFileCleanupJob` (diario, 04:10): no se esperó a su ejecución.
- Filas de refresh token anteriores a la migración de vínculo con el cliente (se «adoptan» en la primera rotación): sin base de datos con datos previos.
- Entrega real de webhooks (sin cambios en `webhook-module`, sin reabrir el túnel).

---

## Addendum 9 (19-nov-2026) — Validación de `origin/master` `4039127b` (PR #342, `acr_values_supported`)

**Qué cambió desde `f25d5838`:** un solo commit, pequeño. El documento de descubrimiento OIDC anuncia ahora `acr_values_supported` (`urn:clavaris:loa:2` y `urn:clavaris:loa:3`) y `claims_parameter_supported: false`; el resto del documento queda igual (`code_challenge_methods_supported` solo `S256`, `RS256`, scopes `openid profile email phone offline_access`, autenticación de cliente `client_secret_basic` y `client_secret_post`). La versión se compiló desde una exportación de solo lectura de `origin/master` (el `master` local de Clavaris estaba un commit por detrás y no se tocó).

| Comprobación | Resultado |
|---|---|
| Revocación y logout (G2) | 21 de 21 |
| PKCE | 13 de 13 |
| Cuentas propias, aislamiento y scopes (G1) | 17 de 17 |
| Cliente de Spring Security interpreta el descubrimiento nuevo | Arranca sin errores; inicio de sesión completo correcto |
| Cerrojo por sesión: 8 renovaciones simultáneas | 8 de 8 con cerrojo; sin cerrojo 1 correcta y 7 `invalid_grant` con 7 reutilizaciones (idéntico a antes) |

**Consecuencia para JobSeeker:** ninguna. JobSeeker no envía `acr_values` ni `claims`, así que no usa los campos nuevos; solo hay que no fallar ante campos desconocidos del descubrimiento, y no fallan.

---

## Addendum 10 (19-nov-2026) — Los dos puntos que el addendum 8 dejó sin ejecutar

Sin commits nuevos en Clavaris (`origin/master` sigue en `4039127b`). Se ejecutaron los dos puntos pendientes.

### Actualización sobre una base de datos con tokens antiguos (vínculo del refresh token con el cliente)

Se emitieron tres refresh tokens con el jar anterior a la migración (`a7d2dbdd`, sin la columna `registered_client_id`), con un cliente de una organización que tiene **dos** clientes, y se arrancó el jar nuevo sobre la misma base.

| Comprobación | Resultado |
|---|---|
| La migración se aplica al arrancar | Sí (`V20261118110000` y `V20261120100000`); el arranque no falla. Las 3 filas quedan con el cliente vacío |
| El **dueño** canjea un token heredado | Correcto; el sucesor queda **vinculado** a su cliente |
| Ese sucesor presentado por el otro cliente | `invalid_grant`, y el token no se consume (el dueño lo sigue usando) |
| Un **cliente ajeno** de la misma organización presenta primero un token heredado | **Lo adopta**: lo canjea con éxito y el dueño real queda fuera (`invalid_grant`) |
| Duración de esa ventana | Lo que viva el refresh token: ~30 días |

La **adopción por un cliente ajeno** es una limitación conocida y documentada por Clavaris (las filas anteriores a la migración no pueden rellenarse), no un defecto; solo afecta a organizaciones con más de un cliente y a tokens anteriores a la actualización. **No afecta a JobSeeker**: despliegue nuevo y un único cliente.

### Trabajo diario de limpieza de claves huérfanas (`OrphanedSigningKeyFileCleanupJob`)

El `cron` está fijado a las 04:10 y solo considera archivos de más de 1 hora. Se arrancó Clavaris con la JVM en `UTC−1` para que las 04:10 locales cayeran a los pocos minutos y se prepararon tres archivos de control. Disparó a las 04:10:00 exactas (`removedCount=1`):

| Archivo | Esperado | Resultado |
|---|---|---|
| Huérfano de 3 h (organización inexistente) | borrado | **borrado** |
| Huérfano de menos de 1 h | conservado | **conservado** |
| Archivo de una **organización viva** con 3 h | conservado | **conservado** |
| Tres huérfanos de ejecuciones anteriores (18, 18 y 6 min) | conservados (< 1 h) | **conservados** |
| Archivo de la plataforma | conservado | **conservado** |

Ninguna clave en uso se perdió. Con esto no queda ningún punto del addendum 8 sin ejecutar salvo `TD-FUT-081` (no implementado) y la entrega real de webhooks (sin cambios en ese módulo).

---

## Addendum 11 (19-nov-2026) — Entrega real de webhooks con el Clavaris de `origin/master` (`4039127b`)

**Montaje:** túnel temporal de `cloudflared` 2026.11.1 (binario verificado contra el digest publicado por GitHub) hacia un receptor que solo acepta peticiones con firma válida; datos de prueba; una Secret Key de organización con los scopes de cuentas y de webhooks. Al terminar se cerró el túnel (la URL pública dejó de responder, HTTP 530) y se borraron el binario y los secretos. Resumen sin datos personales: `anexos/webhooks-clavaris/entrega_real_19nov_sin_pii.json`.

| Comprobación | Resultado |
|---|---|
| Registro del endpoint con la Secret Key | 201; el secreto de firma se devuelve una sola vez |
| Destinos locales o privados (`localhost`, `127.0.0.1`, `10.x`) | **400** (protección SSRF) |
| Eventos entregados con firma válida | 8 de 8: `account.created` (×3), `account.suspended`, `account.reactivated`, `account.deleted`, `refresh_token.reuse_detected` y el nuevo **`refresh_token.reuse_graced`** (distinto del de robo) |
| Cabeceras | `Clavaris-Event-Id`, `-Event-Type`, `-Delivery-Id`, `-Signature`, `-Trace-Id`; un `Event-Id` y un `Delivery-Id` distintos por evento |
| Payload | `accountId`, `organizationId`, `occurredAt`; **`email` solo en `account.created` y `account.deleted`** (como ya se había documentado) |
| Orden de llegada | Igual al de `occurredAt` en esta serie corta (no es una garantía) |
| Estado desde la Secret Key (`GET …/deliveries`) | 8 `SUCCEEDED`, 1 intento |
| **Reenvío** (`:replay`, scope `webhook-deliveries:replay`) | HTTP 200; llega el **mismo `Event-Id` y `Delivery-Id`** |
| **Reintentos** (el receptor devuelve 500 las dos primeras veces) | 3 intentos del mismo evento a +0, +25 y +56 s (calendario de 30 s con duplicación y ±20 % de variación); mismo `Event-Id` y `Delivery-Id`; final `SUCCEEDED` y fallos consecutivos del endpoint a 0 |
| **Rotación del secreto** (`:rotate-secret`) | Cada entrega lleva **dos firmas `v1`**; el receptor valida con **solo el secreto anterior** y con **solo el nuevo**; con un secreto ajeno, rechazo 401 |

**Una ilustración útil:** mi receptor de prueba marcó los reintentos como «duplicado» aunque el primer intento había devuelto 500. Si un receptor real descartara por recepción, perdería el evento: por eso el ADR-0020 exige deduplicar por **evento procesado**, no por recepción.

### Hallazgos en la validación de URL del endpoint (no afectan a la entrega)

| URL enviada | Resultado | Esperado |
|---|---|---|
| `http://example.com/h` | **HTTP 500** `internal_error` | 400 |
| `ftp://example.com/h` | **HTTP 500** | 400 |
| `https://example.com:99999/h` (puerto inválido) | **201** (aceptada) | 400 |
| `not a url`, `https://`, `http://localhost/h` | 400 | 400 |

> **Registrados en Clavaris el 19-nov-2026 como `TD-ARCH-058` (500 ante esquemas no HTTPS) y `TD-ARCH-059` (puerto inválido aceptado), ambos P3, Pending**, en `../Clavaris/docs/05-engineering/technical-debt-register.md`.

El dominio lanza `IllegalArgumentException` («url must be an absolute https URL») y el controlador solo traduce `UnsafeWebhookUrlException` a 400. Es una API de administración y no se crea ningún endpoint con un esquema inválido, pero un 500 por entrada inválida contamina las alertas de error y no distingue «reintenta» de «corrige tu petición».

### No verificado
- Cuánto tiempo sigue firmando el secreto anterior tras una rotación: **resuelto en el addendum 13** (24 h por defecto, medido).
- La desactivación automática tras 5 entregas agotadas consecutivas (ya medida en el addendum 2; no repetida).
- `account.email_changed` y `account.profile_updated`: **resuelto en el addendum 13** (se generan; el cuerpo trae PII).


## Addendum 12 (20-nov-2026) — Validación de `origin/master` `95a88f67` para cerrar el tema de autenticación

Compilado desde `git archive origin/master` (solo lectura), con Postgres/Redis aislados y dos organizaciones, clientes y Secret Keys propios. **38 de 41 comprobaciones pasan; las 3 restantes (`expires_in`) eran una expectativa mía demasiado estricta (ver nota), no un defecto, y la prueba de Redis caído se repitió tras corregir un fallo de mi propio script (5 de 5).** Entorno desmontado (sin procesos, contenedores, puertos ni secretos).

| Área | Resultado |
|---|---|
| Discovery | S256, `refresh_token`, RS256 y `acr_values_supported` presentes |
| Login + PKCE, rotación | Refresh de un solo uso; reutilizar el viejo → `invalid_grant` y la familia entera queda revocada (el sucesor también falla) |
| ID token / access token | Sin `email` ni `email_verified` en el ID token (correo solo en `userinfo`); claims: `acr, amr, aud, auth_time, azp, exp, iat, iss, jti, nonce, picture, sid, sub` |
| **Lectura de estado (ADR-0047, cierra `TD-FUT-081`)** | `GET /api/v1/admin/accounts/{id}/status` con Secret Key propia → 200 `{accountId,status}` y nada más, `Cache-Control: no-store`; cuenta de otra organización, id inexistente y id malformado → **403** idéntico; sin el scope `platform:accounts:read-status` → 403; sin credencial → 401; tras `:suspend` responde `SUSPENDED`; tras `:delete` responde **403** (nunca `DELETED`, no es un oráculo). Funciona con Redis caído |
| **URL de webhook (ADR-0047, cierra `TD-ARCH-058/059`)** | `http://`, `ftp://`, sin esquema, puerto 99999 y puerto 0 → **400**; `https` válido → 2xx; host privado → rechazado por el filtro SSRF |
| **Vida del access token por cliente (ADR-0049, `TD-SEC-104`)** | Por defecto `exp − iat` = 300 s; cliente con 120 s → 120 s, se conserva tras el refresh; 30 s y 7200 s → 400 |
| PKCE | Cliente por defecto sin `code_challenge` → `error=invalid_request` |
| Redis caído (ADR-0045 + 0049) | Suspender → 2xx; cierre diferido (`pending_session_closures` = 1); el refresh de la cuenta suspendida sigue dando `invalid_grant`; al volver Redis se drena a 0. Visibilidad: `clavaris_redis_keyspace_events_configured` = 1 y `clavaris_redis_listener_subscribed` = 1 para ambos listeners |
| Código que NO cambió desde `4039127b` | Firma y entrega de webhooks, rotación de refresh y discovery (el diff solo toca pruebas y el cierre de sesión sin `redirect_uri`): lo medido en los addenda 8–11 sigue vigente |

> **Nota sobre `expires_in`.** La respuesta del token informa 299 (300 s) y 119 (120 s): un segundo menos que el tiempo de vida real. `exp − iat` es exactamente 300 y 120. JobSeeker no debe derivar su lógica de `expires_in`, sino de `exp` del token.

**Qué implica para JobSeeker**
- `TD-FUT-081` ya existe: la prueba de vida puede complementarse con la lectura de estado sin gastar el refresh token. Requiere una Secret Key con `platform:accounts:read-status`. Su límite de tasa es el de la API de administración: sirve para decidir en un punto concreto (job, reingreso), **no por petición**.
- La ventana tras revocar un token de acceso pasa a ser configurable por cliente (60–3600 s); JobSeeker debería registrar su cliente con un valor explícito en vez de depender del defecto.
- `account.email_changed` / `account.profile_updated` sí se generan y la ventana del secreto es de 24 h por defecto (addendum 13).

## Addendum 13 (20-nov-2026) — `account.email_changed`, `account.profile_updated` y ventana del secreto anterior (`95a88f67`)

Mismo método que el addendum 12 (compilado de `origin/master` en solo lectura, Postgres/Redis aislados, entorno desmontado al terminar). Sin túnel público: los eventos se leyeron en la tabla `webhook_deliveries` (el cuerpo que se firmaría y enviaría), con un endpoint HTTPS registrado en un dominio de pruebas; la entrega fallaba, como esperaba, y no hace falta que llegue.

### `account.profile_updated` y `account.email_changed`: se generan

| Evento | Cómo se provocó | Cuerpo observado (datos sintéticos) |
|---|---|---|
| `account.profile_updated` | `PUT /api/v1/admin/accounts/{id}/profile` con una Secret Key con el scope `platform:accounts:profile:write` | `{"accountId":{"value":"…"},"organizationId":{"value":"…"},"firstName":"Ana","lastName":"Prueba","username":"ana_prueba","phoneNumber":"+502 5555-0100","occurredAt":"…"}` |
| `account.email_changed` | Flujo web real: login, solicitud de cambio con la contraseña actual, confirmación con el token | `{"accountId":{"value":"…"},"organizationId":{"value":"…"},"previousEmail":"…","email":"…","occurredAt":"…"}` |

Consecuencias para JobSeeker:
- **El cuerpo trae PII** (correo anterior y nuevo, nombre, apellido, usuario y teléfono). Confirma la regla de ADR-0016/ADR-0020: el receptor debe **reducir el evento a `accountId` y tipo antes de persistirlo, loguearlo o publicarlo**, y no copiar esos campos a `accounts` (JobSeeker no guarda correo ni nombre de Clavaris; los pide a `userinfo` cuando los necesita).
- **Los identificadores vienen anidados**: `accountId` y `organizationId` son objetos `{"value": "…"}`, no cadenas. El deserializador del receptor debe aceptar esa forma (y tolerar que cambie a cadena).
- `profile_updated` es una *instantánea completa* de los campos, no un diff (su Javadoc lo dice y coincide); `email_changed` solo se emite **tras confirmar** el enlace, no al solicitar el cambio.
- Para JobSeeker ninguno cambia permisos ni capacidades locales: basta con invalidar cualquier copia en caché de `userinfo` de esa cuenta.
- La solicitud de cambio de correo respondió **500** en este entorno porque no había clave del proveedor de correo (Resend contestó 401); la solicitud quedó guardada y el cambio se confirmó igual. Es una limitación de mi entorno, no un defecto medido; no lo registro como deuda en Clavaris.

### Cuánto tiempo sigue firmando el secreto anterior

| Medida | Resultado |
|---|---|
| Valor por defecto | `clavaris.webhook.secret-rotation-overlap` = `PT24H` (24 h, según el código) |
| Con la ventana fijada en 90 s | `previous_secret_expires_at − momento de la rotación` = **90 s** exactos (medido en la base) |
| Regla de uso | En cada intento de entrega, `activeSecretsEncrypted(now)` incluye el secreto anterior solo mientras `now < previousSecretExpiresAt`; el instante se evalúa **en cada intento**, así que un reintento tardío tras expirar la ventana va firmado solo con el secreto nuevo |
| **Segunda rotación dentro de la ventana** | Reinicia la ventana (otra de 90 s) y el «anterior» pasa a ser el secreto de la primera rotación: **el secreto original deja de valer de inmediato**. Solo se aceptan dos secretos a la vez |

Consecuencias para JobSeeker:
- Rotar el secreto en JobSeeker debe hacerse **después** de desplegar el nuevo valor y no dos veces seguidas en menos de 24 h (por defecto), o los eventos firmados con el valor original se rechazarán.
- Un evento reintentado después de que expire la ventana trae solo la firma nueva: el receptor debe tener el secreto nuevo configurado antes de que pasen las 24 h.
- El encabezado real tras la expiración se midió después, con entrega real y túnel temporal: ver el addendum 14 (una sola firma `v1`, con el secreto nuevo, incluso en un reintento que nació dentro de la ventana).

## Addendum 14 (20-nov-2026) — Entrega real completa de webhooks y firma tras expirar la ventana (`95a88f67`)

Repite la entrega real del addendum 11 y cierra el punto que el addendum 13 dejó sin medir. Clavaris compilado de `origin/master` (solo lectura), Postgres/Redis aislados, receptor local con **un túnel público temporal** (autorizado expresamente para esta prueba) y ventana del secreto anterior fijada en **60 s** (`clavaris.webhook.secret-rotation-overlap=PT60S`) para poder cruzarla. El receptor etiqueta cada firma `v1` con el secreto que la valida (`current` o `previous`). 19 entregas recibidas, todas con firma válida y marca de tiempo dentro de la tolerancia. Túnel, receptor, binario, secretos y entorno desmontados al terminar. Registro sin PII: `anexos/webhooks-clavaris/entrega_real_20nov_sin_pii.json`.

### La firma antes, durante y después de la ventana

Rotación en T0 (la base fija la expiración en T0 + 60 s):

| Entrega | Llegó | Firmas `v1` | Validan | Respuesta |
|---|---|---|---|---|
| A, enviada a +0 s | +5 s (dentro) | **2** | `current` y `previous` | 200 |
| B, enviada a +36 s, con **1 fallo inyectado** | +41 s (dentro) | **2** | `current` y `previous` | 500 (inyectado) |
| B, **reintento** (mismo `Clavaris-Event-Id`) | **+71 s (tras expirar)** | **1** | **solo `current`** | 200 (`duplicado`=sí) |
| C, enviada a +72 s | +77 s (tras expirar) | **1** | solo `current` | 200 |

Conclusiones medidas:
- **Tras expirar la ventana el encabezado `Clavaris-Signature` lleva una sola firma `v1`, firmada con el secreto nuevo.** Un receptor que solo conozca el secreto anterior rechazaría esa entrega.
- **La firma se calcula en cada intento, no al crear la entrega:** el reintento de B nació dentro de la ventana y salió después de expirarla con una sola firma. Un receptor no puede asumir que lo que se le reintenta lleva las mismas firmas que el primer envío.
- Durante la ventana las dos firmas van en el mismo encabezado (`t=…,v1=…,v1=…`) y basta con que una valide.
- Con el valor por defecto (24 h) el comportamiento es el mismo; solo cambia la duración (no se esperó 24 h).

### Entrega completa

| Comprobación | Resultado |
|---|---|
| Tipos de evento recibidos | `account.created`, `account.suspended`, `account.reactivated`, `account.profile_updated`, `account.email_changed`, `account.deleted`, `refresh_token.reuse_detected` |
| Firma | 19 de 19 válidas; `t` dentro de la tolerancia (300 s) en todas |
| Cabeceras de Clavaris | `Clavaris-Signature`, `-Event-Type`, `-Event-Id`, `-Delivery-Id` (y `-Trace-Id` cuando existe) |
| Reintento | Tras un 500 Clavaris reintenta (~30 s) con el **mismo** `Clavaris-Event-Id`; el receptor lo detecta como duplicado |
| `:replay` | Mismo `Clavaris-Event-Id` **y** mismo `Clavaris-Delivery-Id`; llega marcado como duplicado, así que el receptor debe deduplicar por evento procesado, no por recepción |
| Ráfaga de 5 actualizaciones | Las 5 llegan, con `event_id` distintos, en el orden de emisión |
| Cuerpo | `accountId` y `organizationId` anidados como `{"value":…}`; `email_changed` y `profile_updated` traen PII (reducir a identificador, ADR-0016) |
| Latencia | De 5 s a ~30 s entre la acción y la llegada (el despachador agrupa por ciclos); el receptor no debe depender de un orden temporal estricto ni de inmediatez |

**Implicaciones para JobSeeker:** al rotar el secreto hay que desplegar el nuevo antes de que expire la ventana (24 h por defecto) y no rotar dos veces dentro de ella (addendum 13); un reintento pendiente tras la expiración solo puede validarse con el secreto nuevo.
