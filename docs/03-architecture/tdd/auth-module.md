# TDD — `auth-module` y la seguridad del desplegable
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | 🟡 En revisión |
| **Versión** | 1.0 |
| **Fecha** | 20-nov-2026 |
| **Documentos relacionados** | ADR-0020 (decisión), ADR-0015 (reglas ARCH), ADR-0016 (webhooks), `security-architecture.md` §2, `03-lld.md` §6, BR-ACCT-01 a 09 |

---

## 1. Qué cubre este documento

El *Technical Design Document* del módulo `auth` y de la parte de seguridad que vive en `app`. Las decisiones ya están tomadas en el ADR-0020; aquí se fija **cómo se construye**: qué casos de uso existen, dónde vive cada pieza y en qué orden se entrega. Es el documento que las reglas del proyecto exigen antes de crear carpetas de casos de uso.

JobSeeker es **cliente** de Clavaris: nunca emite tokens ni guarda credenciales. `auth` conserva solo el espejo local de la cuenta (`accounts`), sus capacidades y el ciclo de eliminación.

## 2. Reparto de responsabilidades

| Pieza | Dónde | Por qué allí |
|---|---|---|
| `AccountId`, `Capability`, `Actor` | `common`, `domain.model` | Los usan todos los módulos; un tipo compartido evita que cada uno dependa de `auth` (ARCH-04) |
| Reglas de la cuenta, casos de uso, puertos | `auth` (`domain`, `application`) | Lógica de negocio, sin Spring salvo transacciones (ARCH-01, ARCH-02) |
| Persistencia de `accounts` | `auth`, `infrastructure.adapter.out.persistence` | Adaptador JDBC que implementa los puertos |
| Receptor de webhooks | `app`, cadena C (`infrastructure.security.webhook`) | Filtro de la cadena, no un controlador: la firma se verifica sobre el cuerpo crudo antes de cualquier otro procesamiento y las cadenas de seguridad son de `app` (ADR-0020). El caso de uso `handleclavariswebhook` sí es de `auth` (§11) |
| Las tres cadenas de seguridad, el filtro de actor, la sesión en Redis, la renovación con cerrojo | `app`, `infrastructure.security` | Preocupación transversal del único desplegable (ADR-0026); no es lógica de negocio |

## 3. Casos de uso del módulo

| Carpeta (`application/usecase/`) | Qué hace | Puerto de salida |
|---|---|---|
| `provisionaccount` | Primer login: crea la cuenta local con la capacidad de candidato si no existe (BR-ACCT-09). Idempotente: un segundo login no cambia nada | `AccountProvisioningPort` |
| `resolveactor` | Reconstruye el `Actor` desde la base local en cada petición; rechaza `PENDING_DELETION` y `ANONYMIZED` (ADR-0020, resolución de identidad) | `ActorLookupPort` |
| `handleclavariswebhook` | Aplica un webhook de Clavaris ya verificado (§11) | `IdentityStatePort`, `ProcessedEventsPort`, `DomainEventPublisherPort` |
| `getmyaccount` | Devuelve al llamador su propia cuenta (identificador y capacidades): `GET /api/v1/me`. Es el **caso de uso plantilla** del paso 5 del Sprint 0 (§12): no añade reglas de negocio, así que el esfuerzo va a lo transversal | `MyAccountPort` |

Los casos de uso de las demás funciones (`handleclavariswebhook`, el cierre de sesiones de una cuenta, la solicitud de la capacidad de reclutador) se nombran y se crean en el sub-paso que los implementa, no antes (reglas del proyecto, §7.1).

## 4. Decisiones de diseño

1. **La cuenta nace con capacidad de candidato** (BR-ACCT-09): el alta es el primer login, sin pantalla previa. El `INSERT` es `ON CONFLICT (id) DO NOTHING`, así que dos primeros logins simultáneos no chocan.
2. **El `Actor` se reconstruye en cada petición desde la base**, nunca de claims del token (ADR-0020, NFR-SEC-03). Una caché corta por sesión (≤ 60 s) se añade cuando una medición la justifique; sin ella, una consulta por clave primaria por petición es barata.
3. **Cuenta no aprovisionada** (`NOT_PROVISIONED`): el filtro la crea de forma perezosa; solo puede ocurrir si la sesión es anterior a la cuenta.
4. **El registro de cliente OIDC se resuelve de forma perezosa.** `ClientRegistrations.fromIssuerLocation` consulta a Clavaris al arrancar y hace fallar el contexto si no responde; reiniciar JobSeeker durante una caída de Clavaris no debe impedirlo, porque las sesiones viven en Redis. Se resuelve en la primera petición que lo necesita, se guarda y se reintenta con espera si falla.
5. **El refresh token no se pidió hasta el sub-paso 4.3.** El almacén por defecto de Spring lo guardaría en claro en la sesión de Redis, y el ADR-0020 exige cifrarlo y renovarlo con cerrojo. Con el almacén propio del 4.3 (§9) ya se solicita `offline_access`.
6. **Las cadenas B y C nacieron cerradas.** Hasta implementarlas rechazaban todo: ninguna ruta quedaba abierta por omisión. La B se abrió en el 4.4 (§10) y la C en el 4.5 (§11); sin secreto configurado la C vuelve a cerrarse (503).

## 5. Orden de entrega (paso 4 del Sprint 0)

| Sub-paso | Entrega | Estado |
|---|---|---|
| 4.1 | `common` + `auth`: cuenta, aprovisionamiento y resolución del actor, con el adaptador JDBC | ✅ hecho |
| 4.2 | Cadena A: login OIDC con PKCE, `/app/home`, CSRF con HTMX, cabeceras, sesión en Redis con arranque tolerante | ✅ hecho (§8) |
| 4.3 | Refresh token cifrado, cerrojo por sesión, clasificación y margen de 2 h, logout con revocación | ✅ hecho (§9) |
| 4.4 | Cadena B: Bearer con validador de `aud` y sobre de error estándar | ✅ hecho (§10) |
| 4.5 | Cadena C: receptor de webhooks con HMAC, deduplicación y cierre de sesiones | ✅ hecho (§11); sin validar contra Clavaris real |
| 5 | Caso de uso plantilla `getmyaccount` con límite de tasa, OpenAPI validado, métrica de negocio, log estructurado y prueba de autorización | ✅ hecho (§12) |

## 6. Pruebas

- **Dominio y casos de uso:** pruebas unitarias sin Spring, con un puerto en memoria.
- **Adaptador JDBC:** prueba de integración con Postgres 17 (`app`), incluida la concurrencia del primer login.
- **Seguridad:** la prueba que recorre todas las rutas mutantes de `/app/**` y exige 403 sin CSRF; sesión inactiva y rotación del ID al login (ADR-0020, pruebas obligatorias).
- **Contra Clavaris real:** el flujo de login completo se valida en local contra `origin/master` de Clavaris, compilado en solo lectura (el CI no levanta Clavaris).

## 7. No cubierto todavía

El flujo de cancelar la eliminación al volver a iniciar sesión (BR-DATA-04) y la solicitud de la capacidad de reclutador llegan con las funciones de cuenta de la Ola 1.

## 8. Sub-paso 4.2: qué se construyó y cómo se probó

Código en `app/src/main/java/com/usac/jobseeker/app/infrastructure/security` y `…/adapter/in/web`:

| Pieza | Responsabilidad |
|---|---|
| `SecurityConfiguration` | Las tres cadenas con `@Order`: webhooks y API cerradas (rechazan todo) y la web con login OIDC, CSRF, cabeceras y la regla «lo no declarado se rechaza» |
| `LazyClavarisClientRegistrationRepository` | Descubrimiento perezoso del emisor con tiempos de espera, comprobación de que el `issuer` coincide y espera tras un fallo |
| `ActorFilter` / `CurrentActor` | Reconstruye el `Actor` desde la base en cada petición y rechaza cuentas en eliminación o anonimizadas |
| `AccountProvisioningSuccessHandler` | Crea la cuenta local al terminar el login (BR-ACCT-09) |
| `AbsoluteSessionTimeoutFilter` | Límite absoluto de la sesión (8 h por defecto) |
| `DistributedSessionConfiguration` + `TolerantSessionListenerStartup` | Sesión indexada en Redis; el oyente no impide el arranque con Redis caído (ADR-0020, regla 9) |
| `TraceIdFilter` + `ErrorEnvelope` | Identificador de traza en el MDC, la cabecera y el sobre de error estándar |

Hallazgos al construirlo:
- Un `DefaultOAuth2AuthorizationRequestResolver` con `withPkce()` explícito emite `code_challenge_method=S256`; no se confía en un valor por defecto de la biblioteca.
- La métrica del oyente de sesiones debe usar `isListening()`: `isRunning()` vale `true` con Redis inalcanzable, así que marcaba «suscrito» sin estarlo.
- Una identidad inyectada en MockMvc recibe 403 (no 401) en la cadena cerrada; con una cookie real la cadena sin estado ni la lee.

Pruebas: `WebSecurityIntegrationTest` (acceso, CSRF en todas las rutas mutantes, cabeceras, estados de cuenta, cadenas cerradas), `ClavarisRegistrationTest` (descubrimiento, PKCE y emisor suplantado, con un emisor falso), `RedisUnavailableStartupTest` (sin el arranque tolerante falla con `RedisConnectionFailureException`), `AbsoluteSessionTimeoutFilterTest` y `VendoredAssetsTest` (huella de HTMX).

Aún sin implementar de este sub-paso: la caché del actor por sesión (§4.2) y el cierre de sesión con revocación en Clavaris (4.3).

### 8.1 Validación contra Clavaris real (20-nov-2026)

Con `origin/master` de Clavaris (`9bb0cd66`) compilado en solo lectura y levantado con Postgres y Redis aislados, y JobSeeker en el anfitrión contra el stack local, se recorrió el login como lo haría un navegador:

| Comprobación | Resultado |
|---|---|
| Sin sesión, `/app/home` redirige al login | ✅ 302 a `/oauth2/authorization/clavaris` |
| La petición de autorización lleva PKCE con la política por defecto (`REQUIRED`) de Clavaris | ✅ `code_challenge_method=S256` |
| Login en Clavaris, callback y sesión | ✅ `/app/home` muestra el `sub` que emitió Clavaris con la capacidad de candidato |
| Cookie de sesión | ✅ `HttpOnly; SameSite=Lax; Path=/` |
| El ID de sesión se rota al autenticar (anti *session fixation*) | ✅ cambió |
| La cuenta local | ✅ una fila, candidato `true`, reclutador `false`, `ACTIVE` |
| Sesión en Redis | ✅ la sesión y su índice por cuenta (lo que usará el cierre de sesiones por webhook); TTL ≈ 30 min + 5 min de margen de Spring Session |
| Logout sin token CSRF / con token | ✅ 403 / 302; después `/app/home` vuelve a pedir login |
| Segundo login de la misma persona | ✅ `accountCreated=false`, sigue habiendo una sola cuenta |
| Log del login | ✅ JSON, con `accountId` y `traceId`, sin correo ni nombre |
| Clavaris caído: la sesión abierta sigue sirviendo páginas | ✅ 200 |
| Clavaris caído: reiniciar JobSeeker | ✅ arranca sano y la sesión sobrevive (vive en Redis) |
| Clavaris caído: intentar iniciar sesión | ✅ 503 con `Retry-After`; página legible para el navegador y sobre de error para la API |
| Clavaris vuelve | ✅ el siguiente intento, pasada la espera de 10 s, redescubre el emisor sin reiniciar JobSeeker |

Un defecto encontrado en esa validación: con Clavaris caído el login respondía un 500 genérico. Se corrigió con `IdentityProviderAvailabilityFilter` (503 controlado) y su prueba.

## 9. Sub-paso 4.3: prueba de vida de la sesión

Código en `app/src/main/java/com/usac/jobseeker/app/infrastructure/security/liveness`. Implementa las reglas 1–8 y 12 del ADR-0020.

| Pieza | Responsabilidad |
|---|---|
| `VaultingAuthorizedClientRepository` | Sustituye al almacén de Spring, que dejaría el refresh token **en claro dentro de la sesión**. Cifra el token a Redis y la sesión solo guarda un identificador de enlace (regla 6: no se usa el ID de sesión, que se rota al login). Un login sin refresh token, o sin clave de cifrado, se rechaza |
| `RefreshTokenCipher` | AES-256-GCM con identificador de clave (`kid`) y datos asociados atados al enlace: un valor copiado a otro registro no descifra. Rotar es activar una clave nueva; la anterior solo descifra |
| `LivenessStore` | Estado en Redis (`jobseeker:liveness:{enlace}`, espacio propio) y cerrojo por sesión con liberación solo por su dueño (guion Lua) |
| `LivenessCoordinator` | Cada ≤ 10 min por sesión renueva dentro del cerrojo y **releyendo el estado ya dentro de él** (regla 5): quien esperaba ve el token ya rotado y no renueva otra vez |
| `ClavarisRefreshTokenClient` | Llamada directa al endpoint de tokens con tiempos de espera (2 s / 5 s). Solo `invalid_grant` es definitivo; `invalid_client`, 5xx, red o tiempo agotado son transitorios |
| `LivenessFilter` | Antes del filtro anónimo: si la sesión ya no está respaldada, limpia el contexto e invalida la sesión; la petición sigue como anónima hacia el login. Una sesión autenticada sin enlace se cierra (fallo cerrado) |
| `RevokingLogoutHandler` | Al salir revoca el refresh token en Clavaris (de mejor esfuerzo) y borra el estado; luego `OidcClientInitiatedLogoutSuccessHandler` redirige al fin de sesión de Clavaris |

Decisiones:
1. **Llamada directa y no el cliente de refresh de Spring**, para controlar la clasificación: un secreto de cliente rotado (`invalid_client`) cerraría la sesión de todos si se tratara como definitivo.
2. **Margen sin Clavaris: 2 h**, nunca más que el límite absoluto de la sesión. `jobseeker.auth.liveness.fail-open-enabled=false` fuerza el cierre inmediato ante cualquier fallo (incidente de seguridad).
3. **Caché negativa de 45 s** tras un fallo transitorio: no se reintenta esa sesión durante ese tiempo y no se repite el mismo tiempo agotado.
4. **Sin clave de cifrado y con Clavaris configurado la aplicación no arranca**, con un mensaje que dice qué falta (`TOKEN_ENCRYPTION_KEYS`). Sin Clavaris configurado arranca igual.
5. **Redis caído durante la comprobación no cierra a nadie**: Redis es también el almacén de la sesión, y cerrar a todos por esa caída no aporta seguridad.

Pruebas: `RefreshTokenCipherTest`, `LivenessPropertiesTest`, `LivenessCoordinatorIntegrationTest` (Redis real y un Clavaris falso de tokens de un solo uso: 8 peticiones simultáneas renuevan **una** vez y ninguna reutiliza), `ClavarisRefreshTokenClientTest`, `LivenessFilterTest` y `RefreshTokenVaultTest`.

**Límites conocidos:** al caducar por inactividad una sesión, su registro de vida queda en Redis (cifrado) hasta su TTL (≤ 8 h), porque los eventos de expiración de Spring Session dependen de `notify-keyspace-events`. El refresh token se revoca solo al cerrar sesión explícitamente o al cerrarla la prueba de vida.

### 9.1 Validación contra Clavaris real (21-nov-2026)

Con `origin/master` de Clavaris (`9bb0cd66`) compilado en solo lectura, con Postgres y Redis aislados y JobSeeker en el anfitrión con tiempos acortados (renovación cada 5 s, margen de 40 s):

| Comprobación | Resultado |
|---|---|
| Login con `offline_access`: Clavaris entrega el refresh token | ✅ el login termina y `/app/home` responde 200 |
| El refresh token no está en claro en la sesión | ✅ la sesión no contiene el cliente autorizado; solo el atributo de enlace |
| Estado en Redis | ✅ `rt` cifrado (`k1.…`), `renewed`, `retryAfter`; TTL ≈ 8 h (el límite absoluto) |
| Renovación vencida | ✅ renueva, el valor cifrado cambia (el token rotó) y la métrica `renovado` sube |
| 8 peticiones simultáneas con la renovación vencida | ✅ 8 de 8 responden 200 y hay **una** sola renovación; los tokens de Clavaris quedan 2 revocados por rotación y 1 activo, sin reutilización |
| Token revocado en Clavaris (equivale a suspender o borrar la cuenta) | ✅ la siguiente petición vencida cierra la sesión (302 al login), borra el estado y la cookie vieja ya no entra |
| Logout | ✅ revoca el token en Clavaris (activos 1 → 0), borra el estado y redirige al `end_session_endpoint` con `id_token_hint` |
| Clavaris caído, renovación vencida | ✅ 200 en 0,15 s (conexión rechazada, sin esperar un tiempo agotado), `fallo_transitorio` |
| Segunda petición inmediata | ✅ 200 en 0,08 s: la caché negativa evita reintentar |
| Pasado el margen sin Clavaris | ✅ la sesión se cierra (`cerrada_margen_agotado`) |

Solo con el emisor falso, no con Clavaris real: el tiempo agotado con respuesta perdida (`invalid_grant` tras una caída con latencia) y la ventana de reutilización de 30 s, que exige que un operador la configure en la organización. La revocación se simuló en la base de Clavaris, no con su API de suspensión.

## 10. Sub-paso 4.4: la API con Bearer de Clavaris

Código en `app/src/main/java/com/usac/jobseeker/app/infrastructure/security/api`. Implementa la superficie B del ADR-0020. La dependencia (`spring-boot-starter-security-oauth2-resource-server`) ya estaba decidida allí.

| Pieza | Responsabilidad |
|---|---|
| `ClavarisJwtDecoder` | Valida la firma RS256 contra el JWKS de Clavaris (con recarga al ver un `kid` desconocido), el emisor y `exp`/`nbf` con una tolerancia configurable. Se construye de forma perezosa con el mismo registro que el login |
| `AudienceValidator` | Exige que `aud` esté en la lista de audiencias aceptadas (G6). Comparación exacta; sin audiencias configuradas rechaza todo |
| `AccessTokenValidator` | Exige el claim `scope`: el ID token de Clavaris tiene la misma firma, emisor y audiencia, pero no es una credencial de la API |
| `SubjectValidator` | El `sub` debe ser un UUID canónico, que es el identificador de la cuenta local |
| `BearerAuthenticationEntryPoint` | `401` con `WWW-Authenticate` y el sobre estándar; `400` si el Bearer está mal formado (RFC 6750). Nunca dice por qué se rechazó el token |
| `ApiAvailabilityFilter` | Si no se puede comprobar el token porque Clavaris no responde, `503` con `Retry-After`, no `401` |
| `ApiConfiguration` | El decodificador y el CORS cerrado por defecto |
| `ActorFilter` (generalizado) | El mismo filtro de la web resuelve el actor del Bearer **desde la base local**: lo parametriza qué autenticación le toca y qué rutas se permiten siempre |

Decisiones:
1. **El token identifica, la base decide.** Las capacidades nunca salen de los claims ni de los `scope`: la cadena no convierte `scope` en autoridades. Una capacidad concedida después de emitir el token se ve en la petición siguiente.
2. **Un ID token no es un Bearer.** Se exige `scope`. Sin esta comprobación un ID token (30 min de vida en vez de 5) valdría como credencial.
3. **Fallo cerrado y sin pistas.** Sin audiencias configuradas ni `client_id`, todo se rechaza. El cuerpo y la cabecera de un `401` no dicen por qué falló el token (la cabecera lleva solo `error="invalid_token"`); el motivo queda en el log, sin el token.
4. **Spring Security 7 añade a `WWW-Authenticate` un `resource_metadata=` construido con el `Host` de la petición** y apuntando a un documento que no se sirve. No se puede desactivar con el resolutor (devolver `null` produce la cadena `"null"`), así que la cabecera se escribe a mano.
5. **Lo que no hace falta consultar a Clavaris se rechaza antes.** Un token mal formado, sin firma o con otro algoritmo que RS256 recibe `401` aunque Clavaris esté caído, en vez de un `503` que invitaría a sondear la API. Un token bien formado cuya firma no se puede comprobar recibe `503`.
6. **El algoritmo se fija a RS256**: el ataque de confusión de algoritmo (HS256 con la clave pública como secreto) se rechaza.
7. **CORS cerrado**: con la lista de orígenes vacía (el MVP) ninguna petición de otro origen se acepta; no se admiten comodines y nunca credenciales.
8. **Sin estado ni CSRF**, con la justificación de `S4502` anotada en el código: no hay cookie de sesión que proteger.

Configuración (todas opcionales; `.env.example`): `CLAVARIS_ALLOWED_AUDIENCES` (vacío = solo el `client_id`), `API_CORS_ALLOWED_ORIGINS` (vacío = CORS cerrado) y `API_TOKEN_CLOCK_SKEW_SECONDS` (30 s por defecto, máximo 120).

Pruebas: `ClavarisJwtDecoderTest` (tokens RSA reales: caducidad y `nbf` en el borde exacto de la tolerancia, emisor, audiencia exacta, ID token, `sub`, firma ajena con el mismo `kid`, cuerpo alterado, `alg: none`, confusión de algoritmo, basura y un token enorme, caché y rotación de claves, y Clavaris caído o sin responder), pruebas unitarias de cada validador, de la respuesta `401`, del filtro `503` y del CORS, y tres pruebas de integración de la cadena completa con la base real (`ApiBearerChainIntegrationTest`, `ApiConfiguredIntegrationTest` y `ApiIdentityProviderDownIntegrationTest`). Mutación (PIT) sobre el paquete: 93 % de entrada y 98 % tras cerrar los supervivientes; queda un mutante equivalente (el segundo chequeo del bloqueo del decodificador).

**Límites conocidos:**
- Cada token con un `kid` desconocido fuerza una recarga del JWKS. Sin límite de tasa, alguien podría usarlo para presionar a Clavaris; lo cubre el límite de tasa transversal (ADR-0011), que aún no existe.
- Un `404` de una ruta de la API no usa todavía el sobre estándar: llega con el primer endpoint real.
- Un access token revocado en Clavaris sigue valiendo hasta que expira (5 min); es un límite de Clavaris (ADR-0044 de Clavaris) y JobSeeker no depende de la revocación inmediata.

### 10.1 Validación contra Clavaris real (22-nov-2026)

Con `origin/master` de Clavaris (`9bb0cd66`) compilado en solo lectura, con Postgres y Redis aislados, **una organización con dos clientes** (JobSeeker y otra aplicación, que comparten clave de firma) y JobSeeker en el anfitrión. Los tokens se obtuvieron con el flujo de código con PKCE S256. La API no tiene aún endpoints: una ruta inexistente responde `404` si el token pasó la autenticación y `401` si no, que es lo que se distingue.

| Comprobación | Resultado |
|---|---|
| Claims reales del token de acceso y del ID token | ✅ el de acceso lleva `scope`, el ID token no; `aud` es el `client_id` de cada cliente; vida 300 s. Confirma la suposición de `AccessTokenValidator` |
| Sin token | ✅ `401`, `WWW-Authenticate: Bearer`, `AUTHENTICATION_REQUIRED` |
| Token de acceso de JobSeeker | ✅ pasa la autenticación (`404` de la ruta inexistente) |
| La cuenta se aprovisiona al primer uso del Bearer | ✅ una fila, candidato `true`, reclutador `false` |
| **ID token del mismo cliente** (misma firma, emisor y audiencia) | ✅ `401` `invalid_token`: no sirve como credencial |
| **Token de acceso válido de otra aplicación de la misma organización (G6)** | ✅ `401` `invalid_token`: sin el validador propio de `aud` habría pasado |
| Firma alterada (un carácter del medio) | ✅ `401` |
| Basura | ✅ `401` |
| El log del rechazo y el token | ✅ el motivo queda en el log (`audience`, `access token`, `no es un JWT`) y **ningún token aparece en él** |
| Clavaris caído, token con la clave ya en caché | ✅ sigue valiendo |
| JobSeeker reiniciado con Clavaris caído: token bien formado | ✅ `503`, `Retry-After: 10`, `IDENTITY_PROVIDER_UNAVAILABLE` |
| Lo mismo: basura, sin token y la web | ✅ `401`, `401` y `302` al login: solo el login y la comprobación de tokens dependen de Clavaris |
| Clavaris vuelve | ✅ pasada la espera, un token nuevo vale sin reiniciar JobSeeker |

**Un hallazgo de la validación.** Un token con el **último carácter** de la firma alterado seguía siendo aceptado. No es una debilidad: una firma RSA-2048 son 256 bytes y el último carácter en base64 solo aporta 2 bits de datos y 4 de relleno, así que cambiarlo deja los mismos bytes; con un carácter del medio, `401`. Quedó fijado en dos pruebas (`aTokenWithOneSignatureByteFlippedIsRejected` y la que documenta el relleno), y en el helper de pruebas con la advertencia correspondiente.

**No se validó con Clavaris real** (sí con tokens RSA propios, en los bordes exactos): un token realmente caducado (se desmontó el entorno antes de que venciera el de la prueba), `nbf`, la rotación de claves (publicar un `kid` nuevo) y un token de otra organización con el mismo `client_id`.


## 11. Sub-paso 4.5: el receptor de webhooks de Clavaris

Cadena C del ADR-0020: `/api/v1/webhooks/clavaris`. Sin sesión, sin Bearer y sin CSRF; la autenticidad la da **solo** la firma `Clavaris-Signature`. Su función es una optimización: cerrar de inmediato las sesiones de una cuenta suspendida, borrada o con un refresh token reutilizado. **No es la fuente de verdad**: un webhook puede perderse (si Clavaris desactiva el endpoint tras fallos repetidos), así que la prueba de vida del 4.3 y el límite absoluto de 8 h siguen siendo la red de seguridad.

### 11.1 Qué se construyó

| Pieza | Dónde | Decisión |
|---|---|---|
| Verificador de firma | `app`, `security.webhook.WebhookSignatureVerifier` | HMAC-SHA256 sobre `"<t>.<cuerpo crudo>"`, comparación de tiempo constante contra **todos** los `v1` y **todos** los secretos (vigente y anterior), sin cortocircuito. La firma se comprueba **antes** que la vigencia. Tolerancia de `t` configurable (300 s, máx. 15 min) |
| Filtro del receptor | `app`, `ClavarisWebhookFilter` | Filtro de la cadena y no controlador: la firma se verifica sobre los bytes exactos antes de cualquier otro procesamiento. Límite de cuerpo (256 KiB) comprobado por `Content-Length` y, sin él, con una lectura acotada |
| Reducción a identificadores | `ClavarisWebhookParser` | Solo se leen `accountId` y `occurredAt`; correo, nombre y teléfono no salen de este método (ADR-0016, decisión 11). Nunca se registra el cuerpo |
| Caso de uso | `auth`, `handleclavariswebhook` | Una **sola transacción**: marcar el evento como procesado, aplicar el efecto local y escribir en la bandeja lo que vive fuera de la base |
| Espejo de la identidad | `auth`, `IdentityState` + columnas `identity_*` de `accounts` | Orden por la tupla `(occurredAt, event_id)`; `DELETED` terminal |
| Bandeja de salida | `common` (puertos) + `app.infrastructure.outbox` | `OutboxStore` (publicar en la transacción del llamador, reclamar con `FOR UPDATE SKIP LOCKED`), `OutboxPoller` (reintentos con retroceso exponencial y jitter, `FAILED` al agotar intentos) y purga de retención |
| Cierre de sesiones | `app`, `SessionInvalidationConsumer` | Borra, por el índice `PRINCIPAL_NAME_INDEX_NAME`, todas las sesiones de la cuenta en Redis |

**Por qué el cierre de sesiones pasa por la bandeja y no se hace en la transacción del webhook.** Redis no participa en la transacción de Postgres, y ADR-0016 (decisión 11) prohíbe efectos externos desde el caso de uso o desde `afterCommit`. Con la bandeja, un Redis caído no retrasa ni pierde la aceptación del webhook: el sondeador reintenta, y el consumidor es idempotente.

**La bandeja no existía.** Solo estaba la migración `V20261121100000`; el 4.5 construyó la parte mínima y reutilizable (puertos en `common`, escritura, sondeador y purga), y no un motor genérico. La purga conserva la bandeja procesada 7 días y `processed_events` 30 (ADR-0016, decisión 8).

### 11.2 Reglas de comportamiento

| Situación | Respuesta | Efecto |
|---|---|---|
| Firma ausente, mal formada, que no casa o fuera de tolerancia | `401 WEBHOOK_SIGNATURE_INVALID` | Ninguno |
| Sin secreto configurado | `503 WEBHOOK_NOT_CONFIGURED` | Receptor cerrado: aceptar sin firma dejaría cerrar las sesiones de otro |
| Cuerpo mayor que el límite | `413 PAYLOAD_TOO_LARGE` | Ninguno; no se lee entero |
| Firmado pero ilegible, o sin `Clavaris-Event-Id` | `400 WEBHOOK_PAYLOAD_INVALID` | Ninguno: un 4xx evita que Clavaris insista en algo que no mejorará |
| Método distinto de POST | `405` + `Allow: POST` | Ninguno |
| Fallo al aplicar (base caída, bandeja caída) | `503 WEBHOOK_UNAVAILABLE` | **Nada**: ni el evento queda marcado ni el efecto; el reintento de Clavaris lo aplica una vez |
| Entrega auténtica | `204` solo **después** de confirmar la transacción | Aplicado |
| Misma entrega repetida | `204` | Una sola vez (`processed_events`) |
| Tipo sin efecto, o cuenta que nunca inició sesión | `204` | Ninguno |

**Eventos con efecto** (los cuatro verificados con entregas reales en el spike): `account.suspended`, `account.reactivated`, `account.deleted` y `refresh_token.reuse_detected`. Los demás tipos del catálogo se aceptan y se ignoran, porque cualquier código distinto de 2xx haría que Clavaris reintentara y acabara desactivando el endpoint. `account.banned`/`account.unbanned` parecen análogos pero su carga no se ha comprobado con una entrega real: no se tratan hasta verificarlo.

**Orden.**
- El espejo aplica un evento solo si su `(occurredAt, event_id)` es mayor que el del último aplicado; el UUID se compara **sin signo**, como Postgres (con signo, un empate se resolvería al revés en el dominio y en SQL).
- `DELETED` es terminal y gana siempre, con la marca que traiga (en entregas reales `reuse_detected` llegó después de `deleted`).
- **Cerrar sesiones no respeta el orden, a propósito**: un evento viejo que llega tarde igual cierra las sesiones. Una sesión cerrada de más obliga a iniciar sesión otra vez; una abierta de menos es un acceso que debía haberse cortado.
- Una cuenta con identidad `DELETED` es rechazada por la resolución del actor (`IDENTITY_DELETED`, 403 `ACCOUNT_UNAVAILABLE`).

**Deduplicación.** `INSERT … ON CONFLICT DO NOTHING` sobre `processed_events(consumer, event_id)` **dentro de la misma transacción** que el efecto. Un `SELECT` previo produjo duplicados bajo concurrencia en el spike (8 de 385); la restricción única no puede.

### 11.3 Pruebas

| Capa | Qué comprueba |
|---|---|
| Unidad, verificador | Firma calculada de forma **independiente** (`WebhookSigner`, escrito a partir del código de Clavaris, no compartido con el verificador); rotación con uno y dos `v1`; cuerpo alterado un byte; JSON reserializado (la firma es sobre bytes crudos); bordes exactos de la tolerancia; cabeceras mal formadas de varias formas |
| Unidad, dominio | `IdentityState`: la propiedad **«cualquier orden de llegada deja el mismo estado»** con 300 rondas × 20 permutaciones y empates exactos, reentrega triplicada y `DELETED` en cualquier posición |
| Contrato del puerto | `IdentityStateContract` corre contra un almacén en memoria y contra el adaptador JDBC con Postgres 17 real, incluida la concurrencia (200 eventos simultáneos, 16 hilos): si el SQL ordena distinto que el dominio, falla |
| Caso de uso | Cada evento, repetido, desordenado, tipo ignorado, cuenta desconocida y el fallo de la bandeja que se propaga para revertir |
| Filtro | Bordes del límite de cuerpo con y sin `Content-Length`; métricas por resultado |
| Bandeja | Reintento y éxito posterior, `FAILED` al tope, un fallo no bloquea al resto del lote, `SKIP LOCKED` con 4 sondeadores simultáneos sin entrega doble, purga por retención |
| Integración (23) | La cadena real con Postgres y Redis reales: entrega auténtica → espejo + bandeja + sesión cerrada tras el sondeo; sesión de **otra** cuenta intacta; rotación; 16 copias simultáneas → un solo efecto; evento viejo tardío; `DELETED` terminal; **rollback completo con bandeja caída y reintento que aplica una vez**; firma ausente, ajena, cuerpo alterado, repetición fuera de tolerancia, Bearer que no sustituye a la firma; 413; 400; 405; el cuerpo nunca se repite en la respuesta |
| Cerrado por defecto | Sin secreto, toda entrega recibe 503 |

### 11.4 Pruebas de mutación (PIT, perfil `mutation`, mutadores STRONGER)

Perfil `mutation` (PIT 1.30.2, mutadores `STRONGER`) sobre las clases del 4.5, en dos pasadas: la primera encontró supervivientes reales y la segunda confirma que se cerraron.

| Módulo | Mutantes | Eliminados | Resultado |
|---|---|---|---|
| `common` (puertos de la bandeja y de idempotencia) | 7 | 7 | **100 %** |
| `auth-module` (dominio de identidad, caso de uso, adaptador JDBC) | 63 | 63 | **100 %** (en la primera pasada 60 de 66) |
| `app` (verificador, filtro, parser, propiedades, bandeja, consumidor) | 166 | 160 + 1 cerrado después | **96 %** en la segunda pasada (82 % en la primera: 158 de 193) |

**Qué enseñó la primera pasada.** 35 supervivientes en `app`, de tres clases:
- *Condiciones redundantes* que una excepción posterior ya cubría (parser, propiedades, consumidor). No se añadieron tests inútiles: se **quitó la condición** y quedó un solo camino de error.
- *Comportamiento real sin prueba*: el borde exacto del límite de cuerpo, la lectura acotada sin `Content-Length`, las métricas por resultado, un hex de 64 sin prefijo `v1=`, un timestamp de 12 dígitos, que un consumidor solo se llame para su tipo de evento. Se escribieron los tests.
- *Aritmética sin sentido*: la versión del evento en la bandeja se calculaba con microsegundos a mano; pasó a ser `occurredAt.toEpochMilli()`.

**Quedan 5 supervivientes, todos justificados:**
- 4 en `OutboxConfiguration` (métodos `@Bean` que devuelven nulo): cableado, lo cubre el arranque del contexto.
- 1 **equivalente** en `ClavarisWebhookParser.occurredAt` (`isNumber()` forzado a verdadero): `decimalValue()` sobre un nodo que no es número lanza y el `catch` lo convierte en el mismo resultado, un cuerpo rechazado; ninguna prueba puede distinguirlos.

Un sexto (la comprobación previa por `Content-Length` del filtro) era real y se cubrió con una prueba que falla si se lee el cuerpo; **no se volvió a pasar PIT** después de ese último test.

### 11.5 Validación contra Clavaris real

**No se validó contra un Clavaris real en el 4.5.** Registrar un endpoint exige una URL pública HTTPS (Clavaris rechaza `localhost`) y abrir un túnel público solo se hace a petición expresa. Lo que sí se comprobó: el formato de la firma, leído del código de Clavaris (`WebhookSignature.header`: `t=<epoch>,v1=<hex>` por secreto, HMAC-SHA256 sobre `<t>.<cuerpo>`), contra el cual el firmador de las pruebas es independiente; las cabeceras y la forma de las cargas, de las entregas reales del spike (19 y 20 de noviembre de 2026). **Queda por validar**: una entrega real de punta a punta, incluido el comportamiento de Clavaris ante nuestros 4xx/5xx y su reintento con retroceso.

### 11.6 Decisiones pendientes y límites conocidos

- **Límite de tasa por IP** del receptor (ADR-0011): resuelto en el paso 5 (§12): política `webhook`, por IP, antes de verificar la firma.
- **Conciliación**: no hay un recorrido que compare el espejo con Clavaris tras un hueco de eventos. El 4.3 (prueba de vida) lo cubre en la práctica; si se quiere más, es trabajo aparte.
- **Vigilancia del endpoint**: Clavaris lo desactiva tras fallos repetidos y los eventos se pierden. Hace falta una alerta sobre la métrica `jobseeker_webhook_total` (ausencia de `applied` o exceso de `error`) cuando exista Alertmanager (ADR-0012).
- **Estado de liveness y refresh token almacenado** de la sesión cerrada: el cierre borra la sesión de Redis, pero **no se verificó** qué pasa con la entrada de liveness y el refresh token guardado de esa sesión (4.3). Hay que comprobar que no quedan huérfanos y, si quedan, borrarlos en el mismo consumidor.
- **`account.banned`/`unbanned`**: sin tratar hasta verificar su carga.
- **Cableado de beans** (`OutboxConfiguration`): 4 mutantes que devuelven nulo de un método `@Bean` no los distingue una prueba dirigida; los cubre el arranque del contexto.

## 12. Paso 5 del Sprint 0: el caso de uso plantilla y lo transversal

Criterio de salida del Sprint 0 (`roadmap-and-release-plan.md`): un caso de uso completo de punta a punta con límite de tasa, OpenAPI anotado y validado, métrica de negocio, log estructurado y prueba de autorización, que sirva de plantilla. El elegido es **`getmyaccount`** (`GET /api/v1/me`): devuelve a quien llama su propia cuenta. Se escogió a propósito porque **no añade reglas de negocio ni datos nuevos** (lee lo que `resolveactor` ya resuelve): todo el esfuerzo va a lo transversal, que es lo que debe quedar bien hecho una sola vez.

### 12.1 Dónde vive cada cosa

| Preocupación | Dónde | Cómo |
|---|---|---|
| Caso de uso | `auth`, `application/usecase/getmyaccount` | `GetMyAccountQuery` → `GetMyAccountUseCase` → `Optional<MyAccountView>`; puerto de salida `MyAccountPort`; el bean se declara en `infrastructure.config`, sin estereotipos en `application` |
| Endpoint | `auth`, `infrastructure/adapter/in/web` (ARCH-08) | `MyAccountController`: delgado, sin lógica. La identidad sale del token (`Principal`), nunca de un parámetro, así nadie puede pedir la cuenta de otro (BR-ACCT-04) |
| Contrato público | `MyAccountResponse` | Un DTO propio con `@Schema` en términos de negocio; el modelo de dominio nunca sale tal cual |
| Autenticación y cuenta activa | cadena B + `ActorFilter` | Ya resueltas: el controlador solo ve peticiones con Bearer válido y una cuenta que puede actuar |
| Límite de tasa | `app`, `infrastructure.ratelimit` | **Transversal**: una política por tipo de petición, no una anotación por endpoint (§12.2) |
| OpenAPI | `@Tag`/`@Operation`/`@ApiResponse` en el controlador; documento único en `app` | §12.3 |
| Métrica de negocio | puerto `UseCaseMetricsPort` en `common` | El caso de uso dice `record("get_my_account", "found")`; `app` lo traduce al contador `jobseeker_usecase_total{use_case,outcome}`. Una sola métrica para todos los casos de uso, con etiquetas de conjunto cerrado: añadir un caso de uso no obliga a tocar los paneles |
| Log estructurado | el caso de uso, con `event=… clave=valor` y mensaje en español | El `traceId` lo añade el MDC; **nunca** lleva el identificador de la cuenta ni datos personales (BR-DATA-01) |
| Errores | `ApiExceptionHandler` (`app`) | Todo error de un controlador sale con el sobre estándar y sin detalles internos; 500 genérico con la causa solo en el registro |

### 12.2 Límite de tasa (ADR-0011)

- **Bucket4j sobre Redis**, token bucket, con clave `ratelimit:{política}:{cuenta|ip}`. Los límites están en `application.yml` (`jobseeker.ratelimit.policies.*`: ráfaga, reposición y clave) y se pueden ajustar sin redesplegar código.
- **Cuatro políticas, asignadas por ruta** (`RateLimitRules`): `login` (inicio y *callback* OIDC, por IP), `webhook` (por IP), `write` (POST/PUT/PATCH/DELETE, por cuenta) y `read` (GET/HEAD/OPTIONS, por cuenta). Un endpoint nuevo queda limitado sin que nadie se acuerde de pedirlo; lo único que decide es si es de lectura o escritura. Faltar una política **impide arrancar**. Los sondeos de salud, Prometheus y los recursos estáticos no se limitan.
- **Dos puntos de la cadena.** Las políticas por IP corren **antes** de cualquier procesamiento (quien no sabe firmar no gasta ni la lectura del cuerpo); las de cuenta, después de resolver el actor, y una petición anónima que llegue ahí se limita por IP.
- **Al excederlo:** `429`, sobre estándar con `RATE_LIMIT_EXCEEDED`, `Retry-After` (segundos, redondeado hacia arriba: un 0 invitaría a reintentar y fallar) y `X-RateLimit-Limit`/`X-RateLimit-Remaining`, también en las respuestas que sí pasan.
- **Redis caído: se deja pasar** (decisión de este paso). La API Bearer no necesita Redis para nada más, y tumbarla por el límite de tasa sería peor que no limitar mientras dura la caída; los demás controles (firma, token, cuenta) siguen en pie. Queda medido (`jobseeker_ratelimit_total{outcome="unavailable"}`) y con un aviso como mucho cada 30 s. La conexión se abre en el primer uso y con un reintento espaciado, porque la aplicación debe arrancar con Redis caído (ADR-0020, regla 9).
- **La clave por IP es la dirección del socket, no `X-Forwarded-For`**: esa cabecera la escribe cualquiera salvo que haya un proxy de confianza delante, y creerla permitiría evadir el límite cambiándola en cada petición. Cuando el ADR-0026 fije la topología habrá que decidir qué proxy es de confianza; hasta entonces, detrás de un proxy todas las peticiones compartirían una IP.

### 12.3 OpenAPI (ADR-0010)

- **springdoc 3.2.0, code-first, un único documento** para todo el desplegable, con un `@Tag` por módulo. La información, el esquema de seguridad `bearer` y el sobre `ErrorEnvelope` están en `OpenApiConfiguration`.
- **No se sirve por defecto.** Describe toda la superficie de ataque, y el ADR exige que nunca sea público en producción: `springdoc.api-docs.enabled` y `swagger-ui.enabled` son `false` y solo el perfil `dev` los enciende. Un test comprueba que sin tocar nada ni el spec ni la UI responden.
- **Validación en el build, no en un paso aparte.** Un test exporta el spec de la aplicación real, lo valida con el parser de OpenAPI (sin mensajes de error, versión 3.1) y le aplica las reglas del proyecto (`OpenApiRules`): toda ruta bajo `/api/v1`, `summary`, etiqueta de módulo, requisito de seguridad, 401 y 429 documentados, y **todo error 4xx/5xx con el sobre `ErrorEnvelope`**. Las reglas tienen su propia prueba negativa: parten de un spec correcto y rompen una cosa por vez, para demostrar que de verdad rechazan.
- Esto responde a la pregunta abierta del ADR-0010 sobre el linter: se adopta un conjunto **propio, ejecutado como prueba** y no Spectral, porque las reglas que importan (sobre de error, versionado, una etiqueta por módulo) son convenciones de este proyecto que ningún ruleset de la comunidad conoce, y así no se añade una herramienta ni un runtime de Node al CI. Si más adelante se quiere Spectral, se suma sin quitar esto.

### 12.4 Pruebas

| Capa | Qué comprueba |
|---|---|
| Caso de uso | La cuenta devuelta, que cada llamador solo ve la suya, cuenta inexistente, la métrica registrada sin identificadores y que el resultado es inmutable |
| Controlador | La identidad es el `sub` del token y nada más, orden estable de capacidades, 404 |
| Límite: propiedades y rutas | Política faltante impide arrancar, validaciones, cada ruta recibe su política y las operativas quedan exentas |
| Límite: filtro | En qué punto actúa cada política, la clave (cuenta o IP), `X-Forwarded-For` ignorado, cabeceras y 429 |
| Límite: Redis real | Cuenta atrás, cubos independientes por clave y por política, clave en el espacio de nombres documentado, **200 intentos simultáneos conceden exactamente la capacidad** y Redis caído deja pasar y lo cuenta |
| Cadena completa (`/api/v1/me`) | Tokens RSA reales, Postgres y Redis reales: la cuenta propia, aprovisionamiento en el primer uso, nadie ve la de otro aunque lo intente por parámetro o cabecera, 401 sin token o con audiencia ajena, 403 en eliminación o con identidad borrada, **429 con el sobre y `Retry-After` sin afectar a otras cuentas**, la métrica de negocio y el log con el `traceId` y sin el identificador |
| IP | Webhook y login limitados por dirección, incluso con firmas válidas, y la inundación de firmas falsas se corta **antes** de verificarlas |
| OpenAPI | Spec válido 3.1, reglas del proyecto sobre el spec real, reglas probadas en negativo y spec no servido por defecto |
| Errores | 500 sin detalle interno, estado conservado con código genérico, 405 y 404 con sobre |

### 12.5 Receta para un endpoint nuevo

1. Nombrar el caso de uso en el TDD de su módulo (no se crea una carpeta de `usecase/` sin que esté escrito).
2. `application/usecase/<nombre>`: consulta o comando, interfaz del caso de uso, puerto de salida y servicio; el servicio recibe `UseCaseMetricsPort` y registra su resultado.
3. `infrastructure/adapter/in/web`: controlador delgado, DTO propio con `@Schema`, `@Tag` del módulo, `@Operation` y un `@ApiResponse` por cada código posible, **incluidos 401 y 429**, con el sobre `ErrorEnvelope` en los errores.
4. Registrar el bean en `infrastructure.config`.
5. Nada más para el límite de tasa: la política se asigna por método. Si el endpoint necesita otra, se añade a `jobseeker.ratelimit.policies` y una regla en `RateLimitRules`.
6. Pruebas: caso de uso con almacén en memoria, controlador, y una de la cadena completa que incluya la autorización (qué pasa con otra cuenta, con una cuenta en eliminación y sin token). El test de OpenAPI ya fallará si falta una anotación.

### 12.6 Límites conocidos y decisiones abiertas

- **Valores de los límites**: iniciales, a calibrar con tráfico real (ADR-0011). Tampoco hay política diferenciada para cuentas con ambas capacidades.
- **`X-Forwarded-For`**: ver §12.2; depende del ADR-0026.
- **Swagger UI en producción**: apagada. Si se quiere en staging, falta decidir su protección (hoy `permitAll` donde está encendida, solo en `dev`).
- **Snapshot del spec versionado en el repositorio** (pregunta abierta del ADR-0010): no se hace todavía.
- **Códigos de error genéricos** (`REQUEST_INVALID`, `RESOURCE_NOT_FOUND`…): los propios del dominio (`JOB_POSTING_NOT_FOUND`…) los pondrá cada módulo al tenerlos.
- **Pruebas de mutación**: 

Perfil `mutation` (PIT, mutadores `STRONGER`) sobre las clases del paso 5, en dos pasadas:

| Módulo | Mutantes | Eliminados | Resultado |
|---|---|---|---|
| `auth-module` (`getmyaccount` y su controlador) | 8 | 8 | **100 %** |
| `app` (límite de tasa, manejador de errores, métrica de negocio) | 115 | 114 + 1 cerrado después | **99 %** en la segunda pasada (90 %, 101 de 112, en la primera) |

La primera pasada dejó 11 supervivientes, todos reales: que **no se reintenta la conexión dentro del margen de 5 s** (lo que protege la latencia con Redis caído), el espaciado de **un aviso cada 30 s**, la métrica de negocio sin cobertura directa y la construcción de la conexión (contraseña). Para probar el reloj sin esperar se inyectaron la conexión y el reloj del limitador. El último superviviente (el primer aviso con un reloj que arranca cerca de cero, porque `nanoTime` no tiene origen definido) se cubrió con un test **sin volver a pasar PIT**.
