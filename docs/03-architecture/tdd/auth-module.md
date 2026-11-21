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

El *Technical Design Document* del módulo `auth` y de la parte de seguridad que vive en `app`. Las decisiones ya están tomadas en el ADR-0020; aquí se fija **cómo se construye**: qué casos de uso existen, dónde vive cada pieza y en qué orden se entrega. Es el documento que `CLAUDE.md` exige antes de crear carpetas de casos de uso.

JobSeeker es **cliente** de Clavaris: nunca emite tokens ni guarda credenciales. `auth` conserva solo el espejo local de la cuenta (`accounts`), sus capacidades y el ciclo de eliminación.

## 2. Reparto de responsabilidades

| Pieza | Dónde | Por qué allí |
|---|---|---|
| `AccountId`, `Capability`, `Actor` | `common`, `domain.model` | Los usan todos los módulos; un tipo compartido evita que cada uno dependa de `auth` (ARCH-04) |
| Reglas de la cuenta, casos de uso, puertos | `auth` (`domain`, `application`) | Lógica de negocio, sin Spring salvo transacciones (ARCH-01, ARCH-02) |
| Persistencia de `accounts` | `auth`, `infrastructure.adapter.out.persistence` | Adaptador JDBC que implementa los puertos |
| Receptor de webhooks | `auth`, `infrastructure.adapter.in.web` | Es una entrada del módulo; se entrega en el sub-paso 4.5 |
| Las tres cadenas de seguridad, el filtro de actor, la sesión en Redis, la renovación con cerrojo | `app`, `infrastructure.security` | Preocupación transversal del único desplegable (ADR-0026); no es lógica de negocio |

## 3. Casos de uso del módulo

| Carpeta (`application/usecase/`) | Qué hace | Puerto de salida |
|---|---|---|
| `provisionaccount` | Primer login: crea la cuenta local con la capacidad de candidato si no existe (BR-ACCT-09). Idempotente: un segundo login no cambia nada | `AccountProvisioningPort` |
| `resolveactor` | Reconstruye el `Actor` desde la base local en cada petición; rechaza `PENDING_DELETION` y `ANONYMIZED` (ADR-0020, resolución de identidad) | `ActorLookupPort` |

Los casos de uso de las demás funciones (`handleclavariswebhook`, el cierre de sesiones de una cuenta, la solicitud de la capacidad de reclutador) se nombran y se crean en el sub-paso que los implementa, no antes (`CLAUDE.md` §7.1).

## 4. Decisiones de diseño

1. **La cuenta nace con capacidad de candidato** (BR-ACCT-09): el alta es el primer login, sin pantalla previa. El `INSERT` es `ON CONFLICT (id) DO NOTHING`, así que dos primeros logins simultáneos no chocan.
2. **El `Actor` se reconstruye en cada petición desde la base**, nunca de claims del token (ADR-0020, NFR-SEC-03). Una caché corta por sesión (≤ 60 s) se añade cuando una medición la justifique; sin ella, una consulta por clave primaria por petición es barata.
3. **Cuenta no aprovisionada** (`NOT_PROVISIONED`): el filtro la crea de forma perezosa; solo puede ocurrir si la sesión es anterior a la cuenta.
4. **El registro de cliente OIDC se resuelve de forma perezosa.** `ClientRegistrations.fromIssuerLocation` consulta a Clavaris al arrancar y hace fallar el contexto si no responde; reiniciar JobSeeker durante una caída de Clavaris no debe impedirlo, porque las sesiones viven en Redis. Se resuelve en la primera petición que lo necesita, se guarda y se reintenta con espera si falla.
5. **El refresh token no se pide hasta el sub-paso 4.3.** El almacén por defecto de Spring lo guardaría en claro en la sesión de Redis, y el ADR-0020 exige cifrarlo y renovarlo con cerrojo. Hasta tener ese almacén no se solicita `offline_access`.
6. **Las cadenas B y C nacen cerradas.** Mientras no estén implementadas, `/api/v1/**` y `/api/v1/webhooks/clavaris` rechazan todo: ninguna ruta queda abierta por omisión.

## 5. Orden de entrega (paso 4 del Sprint 0)

| Sub-paso | Entrega | Estado |
|---|---|---|
| 4.1 | `common` + `auth`: cuenta, aprovisionamiento y resolución del actor, con el adaptador JDBC | ✅ hecho |
| 4.2 | Cadena A: login OIDC con PKCE, `/app/home`, CSRF con HTMX, cabeceras, sesión en Redis con arranque tolerante | ✅ hecho (§8) |
| 4.3 | Refresh token cifrado, cerrojo por sesión, clasificación y margen de 2 h, logout con revocación | pendiente |
| 4.4 | Cadena B: Bearer con validador de `aud` y sobre de error estándar | pendiente |
| 4.5 | Cadena C: receptor de webhooks con HMAC, deduplicación y cierre de sesiones | pendiente |

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

