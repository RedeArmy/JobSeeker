# ADR-0020: Dos superficies de autenticación — sesión + CSRF para la web, Bearer para la API
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | ✅ Aprobado — 18-nov-2026, por el responsable del proyecto, tras la validación completa descrita al final de este documento |
| **Versión** | 1.1 — ajustado el 18-nov-2026 tras la validación completa |
| **Fecha** | Noviembre 2026 |
| **Documento relacionado** | ADR-0005 (Thymeleaf + HTMX), ADR-0008, ADR-0010, ADR-0011, ADR-0014, `security-architecture.md` §2, `api-contract-overview.md` §3 y §7, Clavaris ADR-0003 |

---

## Contexto

`security-architecture.md` §2 y `api-contract-overview.md` §3 describen la autenticación como "JWT Bearer en el header `Authorization`" enviado desde el navegador. Eso no corresponde a la arquitectura elegida: con **Thymeleaf + HTMX** (ADR-0005) el navegador realiza navegaciones y peticiones parciales con **cookies**, no con cabeceras que JavaScript añade a mano. Además:

- Guardar un token en `localStorage` para adjuntarlo desde JS expone el token a XSS y obliga a escribir JS que ADR-0005 evita.
- Con cookies aparece el riesgo **CSRF**, que el modelo Bearer no tenía y la documentación no trata.
- JobSeeker no es el emisor de identidad: es cliente OIDC de Clavaris, que ya implementa Authorization Code + PKCE con login alojado.
- El `/api/v1/**` sí está pensado para consumidores programáticos (integraciones, futura app móvil) donde Bearer es lo adecuado.
- NFR-SEC-07 (fuerza bruta de login) ya no es responsabilidad de JobSeeker: el formulario de login vive en Clavaris.

## Decisión

Definir **tres cadenas de seguridad independientes** (tres `SecurityFilterChain` ordenados: A web, B API y C receptor de webhooks) en el módulo `app`:

### Superficie A — Web SSR/HTMX: `/app/**` (y rutas de login/logout)

| Aspecto | Decisión |
|---|---|
| Autenticación | `oauth2Login` de Spring Security contra Clavaris: Authorization Code + **PKCE**, `scope=openid profile email offline_access` (el refresh token se usa solo como prueba de vida, ver más abajo) |
| Sesión | Del lado del servidor (Spring Session sobre Redis); el navegador solo guarda el ID de sesión. Los tokens de Clavaris, incluido el refresh token, no llegan al navegador. **En Boot 4 `spring.session.*` no configura nada:** se declara `@EnableRedisIndexedHttpSession` de forma explícita y se usa el repositorio **indexado** por cuenta (`PRINCIPAL_NAME_INDEX_NAME` = `accountId`), verificado en `07-spike-bibliotecas-java-25.md`, lo que permite cerrar todas las sesiones de una cuenta al recibir un webhook |
| Cookie | Prefijo `__Host-`, `HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/`; rotación del ID al autenticar (anti *session fixation*). El prefijo exige HTTPS: el perfil de desarrollo sobre `http://localhost` usa el mismo nombre sin prefijo |
| Expiración | Inactividad ≤ 30 min, absoluta ≤ 8 h (configurable). Aparte, una **prueba de vida** contra Clavaris cada ≤ 10 min (sección propia más abajo): si falla, la sesión se cierra. El access token (300 s) no limita la sesión |
| CSRF | **Activo** para toda ruta mutante (`POST/PUT/PATCH/DELETE`). El token se entrega en una `<meta>` renderizada por Thymeleaf y HTMX lo adjunta con `hx-headers` (`X-CSRF-TOKEN`) en cada petición |
| Logout | Revocar el refresh token en Clavaris (`/oauth2/revoke`, que ahora termina su sesión; TD-SEC-100) + RP-initiated logout hacia el `end_session_endpoint` + invalidación de la sesión local. Clavaris **no** ofrece *back-channel logout* (verificado) |
| Cabeceras | CSP estricta sin `unsafe-inline` (HTMX configurado sin `eval` y sin estilos inline), HSTS, `X-Content-Type-Options`, `Referrer-Policy`, `frame-ancestors 'none'`, `Permissions-Policy` |
| Rate limiting | ADR-0011, clave `accountId` (o IP antes de autenticar) |
| Respuesta a no autenticado | Redirección a login (navegación) o `HX-Redirect`/401 para peticiones HTMX |

### Superficie B — API JSON: `/api/v1/**`

| Aspecto | Decisión |
|---|---|
| Autenticación | `oauth2ResourceServer().jwt()`: valida firma RS256 contra el JWKS de Clavaris (caché con rotación por `kid`), `iss` (`{base}/o/{orgId}`), `exp` y **`aud` mediante un validador propio**: el decodificador por defecto **no** valida `aud` (G6) y todos los clientes de una organización comparten clave de firma, así que sin esa comprobación valdría el token de cualquier otra aplicación de la organización. `aud` es el `client_id` del cliente que pidió el token, por lo que se acepta una **lista configurable de audiencias** (por defecto el `client_id` de JobSeeker; se amplía al dar de alta un cliente programático o móvil) |
| Sesión | **Sin estado** (`STATELESS`); sin cookies |
| CSRF | Deshabilitado **solo** en esta cadena (no hay cookie de sesión que proteger) |
| CORS | **Cerrado por defecto**; los orígenes permitidos son una lista explícita en configuración, vacía en el MVP |
| Respuestas | Sobre de error estándar (`api-contract-overview.md` §6); `401` con `WWW-Authenticate` |
| Swagger UI / `/v3/api-docs` | Autenticados en producción (ADR-0010, `security-architecture.md` §12) |

### Superficie C — Receptor de webhooks de Clavaris: `/api/v1/webhooks/clavaris`

Verificada el 16-nov-2026 (`architecture-review-2026-11/06-spike-contrato-clavaris.md`, addendum). **Tercera cadena de seguridad**, independiente: sin sesión, sin Bearer y sin CSRF; la autenticidad la da **solo la firma `Clavaris-Signature`** (HMAC-SHA256 sobre `"<t>.<cuerpo crudo>"`, aceptando cualquiera de las firmas `v1` presentes durante una rotación), con tolerancia de tiempo (propuesta: 300 s), límite de tamaño del cuerpo, *rate limit* por IP y respuesta 2xx **después** de deduplicar y aplicar el efecto en una sola transacción (ADR-0016, decisiones 11 y 12): el cuerpo se reduce a identificadores, el efecto sobre la base local se aplica en esa misma transacción y lo que viva fuera de la base (cerrar sesiones en Redis) sale por el *outbox*. La deduplicación es por **evento procesado** (`Clavaris-Event-Id` registrado en la misma transacción que su efecto), nunca por recepción. **Verificado bajo concurrencia (16-nov-2026, `06-…` addendum 4):** la deduplicación debe apoyarse en una restricción única (`INSERT … ON CONFLICT DO NOTHING` sobre `event_id`) y no en una consulta previa (8 de 385 eventos se aplicaron dos veces con la consulta previa); el orden se decide con la tupla `(occurredAt, event_id)`, no solo con `occurredAt` (un empate exacto dejó 4–7 de 30 cuentas en estado distinto según el orden de llegada); y `account.deleted` es **terminal y gana siempre**, aunque llegue con una marca anterior a la de otro evento. El cuerpo contiene PII (correo; nombre y teléfono en `profile_updated`): no se registra en logs y se descarta antes de persistir nada (ADR-0016, decisión 11).

**Fiabilidad (segunda sesión de verificación, 16-nov-2026):** los eventos pueden llegar **desordenados** (se decide con la tupla `(occurredAt, event_id)` y `account.deleted` gana siempre; ver arriba), **repetirse** y **perderse sin recuperación** (con el endpoint desactivado no se encolan). Por tanto: (1) tras cualquier hueco de eventos se **invalidan todas las sesiones anteriores al hueco**; (2) se vigila `active`/`autoDisabledAt` del endpoint con alerta inmediata; (3) los webhooks aceleran el cierre de sesión pero **no son la única barrera**: el límite absoluto de sesión (8 h) acota la exposición si todo falla. Detalle en `06-spike-contrato-clavaris.md`, addendum 2.


### Prueba de vida con refresh token (decisión B, verificada el 16-nov-2026)

Un filtro renueva el access token de la sesión al menos cada 10 min (configurable, ≤ 15 min). Reglas no negociables, derivadas de `06-spike-contrato-clavaris.md` G2:

1. **Cerrojo por sesión** alrededor de toda renovación (en memoria con una instancia; en Redis con varias). Los refresh tokens de Clavaris son de un solo uso y la reutilización revoca toda la familia: con 8 peticiones simultáneas sin cerrojo, 7 fallaron y se generaron 7 eventos `refresh_token.reuse_detected`; con cerrojo, 8 de 8 correctas.
2. **Clasificación y margen (decidido el 19-nov-2026; medido con Clavaris real, `09-…` §9).** Solo `invalid_grant` es definitivo ⇒ se invalida la sesión local. Cualquier otro fallo de la renovación (`invalid_token_response`, con causa de red, tiempo agotado o 502) es **transitorio** ⇒ la sesión sigue válida hasta **2 horas** desde la última comprobación correcta (valor por defecto, configurable en `jobseeker.auth.liveness.clavaris-unavailable-margin`; nunca por encima del límite absoluto de 8 h) y después se cierra (fallo cerrado). Fundamento medido: durante una caída de Clavaris **nadie puede suspender ni borrar cuentas** (el endpoint de administración no responde), así que mantener la sesión solo expone a las suspensiones hechas en los ≤ 10 min previos y aún no detectadas; cerrar pronto castigaría a todos los usuarios por un fallo ajeno y, como el login también depende de Clavaris, no podrían volver a entrar. Un interruptor operativo (`jobseeker.auth.liveness.fail-open-enabled=false`) fuerza el cierre inmediato ante un incidente de seguridad, y las sesiones de una cuenta pueden cerrarse localmente por el índice de Redis sin depender de Clavaris.
3. El refresh token se guarda cifrado en reposo (AES-GCM, clave propia), solo en la sesión del servidor y nunca en logs: vale 30 días. La revocación en Clavaris **ya funciona** (TD-SEC-100 corregido y validado el 17-nov-2026, addendum 5), de modo que el cierre de sesión de JobSeeker **debe revocar el refresh token** (`/oauth2/revoke` o `end_session_endpoint`); el cifrado se mantiene como defensa en profundidad. El valor cifrado lleva un identificador de clave (`kid`); rotar la clave es activar una nueva (la anterior solo descifra) y, al retirar la antigua, las sesiones que la usaban se invalidan (≤ 8 h de vida).
4. Un `refresh_token.reuse_detected` recibido por webhook invalida todas las sesiones de la cuenta.

**Verificado con dos instancias y sesión en Redis (16-nov-2026, `06-spike-contrato-clavaris.md`, addendum 3).** Reglas adicionales para varias instancias:

5. El cerrojo **no basta por sí solo**: con cerrojo Redis pero el cliente autorizado leído de la sesión de Spring Session, 7 de 8 renovaciones fallaron (7 `reuse_detected`), porque Spring Session cachea la copia de la sesión por petición y quien espera el cerrojo usa un token ya rotado. El refresh token debe **releerse de Redis dentro del cerrojo**. Con cerrojo Redis y relectura: 8/8, 24/24 y 6 sesiones × 8 simultáneas, todo correcto, sin eventos de reutilización.
6. **No indexar el almacén de tokens por ID de sesión**: Spring Security lo rota al terminar el login (anti *session fixation*) y la clave queda huérfana. Se usa un identificador propio, estable, guardado como atributo de sesión.
7. **TTL del cerrojo ≫ duración máxima de una renovación** (propuesta: 30 s, con *timeout* HTTP de 5 s hacia Clavaris). Si el cerrojo expira con el dueño aún renovando, otra instancia rota el token y el dueño lo reutiliza: reproducido (1 `reuse_detected`, sesión destruida). Si una instancia cae con el cerrojo tomado, las demás esperan como máximo el TTL y no hay reutilización (verificado: 5,6 s con TTL de 8 s).
8. La renovación solo debe ocurrir cuando toca (cada ≤ 10 min por sesión), no en cada petición: serializar 24 renovaciones forzadas costó hasta 5,8 s de espera. El filtro debe saltarse la renovación si otra petición ya la hizo dentro del intervalo.
9. **Redis caído (verificado el 17 y 18-nov-2026, addenda 6 y 7):** el servicio no debe fallar al arrancar por Redis. Hay **dos** causas y hay que tratar las dos (Clavaris encontró la segunda al probar con el contexto completo contra un Redis muerto: corregir solo la primera habría movido el fallo, no quitado): (a) el inicializador de Spring Session ejecuta `CONFIG SET notify-keyspace-events` al arrancar ⇒ `ConfigureRedisAction.NO_OP` y fijar `notify-keyspace-events Egx` en el servidor (también necesario en un Redis gestionado donde `CONFIG` está deshabilitado); (b) el contenedor de escucha de Spring Session (`springSessionRedisMessageListenerContainer`) se suscribe al iniciar el ciclo de vida y falla el contexto ⇒ desactivar su arranque automático, iniciarlo desde un gancho propio y reintentar cada 10 s en segundo plano (el equivalente de `TolerantSessionListenerStartup` de Clavaris). Prueba obligatoria: arrancar el contexto completo contra un puerto sin Redis y comprobar *liveness* 200 y *readiness* 503, y que se recupera sin reiniciar. **Verificado en la pila de JobSeeker el 18-nov-2026** (Boot 4.1.2, `@EnableRedisIndexedHttpSession`, sin Redis en el puerto): sin ninguna de las dos medidas **no arranca** (falla `enableRedisKeyspaceNotificationsInitializer`); con solo `NO_OP` **tampoco** (falla el contenedor de escucha); con las dos arranca con *liveness* 200 y reintento cada 10 s. Dos precisiones: (1) *readiness* por defecto responde **200** aunque Redis esté caído, porque Boot no incluye Redis en ese grupo: hay que declarar `management.endpoint.health.group.readiness.include=readinessState,redis` para obtener el 503; (2) mientras Redis está caído, toda petición que necesite sesión responde 500, y al volver Redis la escucha se reanuda sin reiniciar (en la prueba tardó ~1 min por los tiempos de reconexión de Lettuce, más que los 10 s del reintento).
10. **El cliente OAuth de JobSeeker usa la política de PKCE por defecto (`REQUIRED`)**; Spring Security envía S256 automáticamente (verificado). No se solicita `OPTIONAL`.
11. **Con Redis caído en Clavaris** (verificado el 18-nov-2026 con el ADR-0045 de Clavaris, addendum 7): la renovación, la revocación, `client_credentials` y también suspender, reactivar y borrar cuentas con Secret Key **responden 204** y se aplican al instante; Clavaris cierra después las sesiones de navegador pendientes. El *job* de anonimización solo reintenta ante errores de red o 5xx inesperados.

12. **Llamada de renovación resiliente (medido el 18/19-nov-2026 con el cliente de Spring Security 7 contra Clavaris real).**
    - **Tiempos de espera obligatorios** (conexión 2 s; lectura 10 s inicial, a ajustar con la latencia real; en la prueba se usó 5 s y la renovación normal tardó 83–1.246 ms). Sin ellos el cliente por defecto **no termina** frente a un Clavaris colgado (≥ 45 s) y las demás peticiones de la sesión quedan bloqueadas detrás del cerrojo, agotando hilos. Se inyecta con `RestClientRefreshTokenTokenResponseClient.setRestClient(...)`.
    - **Caché negativa:** tras un fallo transitorio no se reintenta esa sesión durante 30–60 s (y un cortacircuitos limita los intentos globales); las peticiones que esperan el cerrojo reciben el fallo ya conocido. Medido: dos peticiones simultáneas tardaron 5 s + 5 s repitiendo el mismo tiempo agotado.
    - **Un tiempo agotado no es un fallo inocuo.** Clavaris puede haber procesado la petición y rotado el token cuya respuesta se perdió; el siguiente intento recibe `invalid_grant` por reutilización y la sesión muere (Spring borra el cliente: `client_authorization_required`). Reproducido de forma determinista con un proxy que retrasa la respuesta, y de forma natural con Clavaris detenido o con su base de datos detenida. Con conexión rechazada o 502 el token nunca llega a Clavaris y la **misma sesión se recupera sola** (medido). Consecuencia: tras una caída con tiempos agotados es **esperable** que algunas sesiones den `invalid_grant` al volver Clavaris; se cierran y el usuario inicia sesión de nuevo, sin tratarlo como incidente (se mide con una métrica, `liveness_invalid_grant_tras_caida`).
    - Si el fallo es transitorio **no** se reintenta con el mismo token dentro del mismo intento.
    - **Ventana de reutilización de Clavaris (ADR-0046, validada el 19-nov-2026, `06-…` addendum 8).** Es **por organización, apagada por defecto (0 s) y solo la fija un operador de Clavaris**: es un **requisito de despliegue** que la organización de JobSeeker tenga `refreshTokenReuseGraceSeconds` en **30 s** (máximo 60). Con ella, un reintento **único y cercano** tras un tiempo agotado (a ≤ ~5 s, siempre dentro de la ventana) recupera la sesión: Clavaris devuelve un token nuevo y emite `refresh_token.reuse_graced`. Límites medidos: (1) un reintento **fuera** de la ventana (a los ~41 s) sigue dando `invalid_grant`, así que **no cubre una caída larga**; (2) **no sustituye al cerrojo:** 8 presentaciones simultáneas del mismo token dan 1 correcta y 7 `invalid_grant` también con la ventana activa; (3) si el sucesor ya se usó, un token viejo sigue revocando toda la sesión (robo). El reintento único va **dentro del mismo intento de renovación**, antes de abrir la caché negativa, y solo si la ventana está configurada (`jobseeker.auth.liveness.retry-once-within-grace`).
    - Clavaris ya ofrece la lectura idempotente del estado de cuenta (`TD-FUT-081`, cerrada por su ADR-0047, verificada el 20-nov-2026 sobre `95a88f67`: `GET /api/v1/admin/accounts/{id}/status` con el scope `platform:accounts:read-status`; una cuenta borrada responde 403, nunca `DELETED`). Su uso queda decidido en la regla 13.

13. **Lectura de estado de cuenta de Clavaris (ADR-0047 de Clavaris): complemento de conciliación, no de la prueba de vida (decidido el 20-nov-2026).** La prueba de vida de las reglas 1–12 sigue siendo el refresh token, sin cambios; la lectura de estado **no** se llama por petición ni por renovación.
    - **Dónde sí se usa:** (a) **conciliación** de cuentas cuyo estado local pudo quedar desfasado por webhooks perdidos (endpoint desactivado, entrega agotada, evento fuera de orden): un *job* programado y, además, al reactivar el endpoint o tras un `:replay`; (b) **comprobación previa** a una acción de segundo plano con efecto sobre una persona sin sesión activa (p. ej. notificaciones salientes), donde no hay refresh token que gastar.
    - **Dónde no:** en la cadena `/api/v1/**` (por petición: comparte el límite de tasa de la API de administración y añade una dependencia de Clavaris por llamada; la ventana tras una suspensión es la vida del access token, 5 min por defecto, y si hace falta menos se fija por cliente, ADR-0049 de Clavaris) ni en `/app/**` (ya cubierto por el refresh).
    - **Un 403 no significa «borrada».** Clavaris responde 403 igual para cuenta borrada, de otra organización, id inexistente, scope ausente y credencial mal configurada o rotada. Regla: un 403 **nunca dispara por sí solo una acción destructiva** (anonimizar, cerrar sesiones, ocultar el perfil); la cuenta pasa a «por verificar» y la confirma `account.deleted` o la solicitud de borrado propia (§6c). Un cortacircuitos detiene el *job* y alerta si una proporción alta de las consultas de una pasada da 403 (síntoma de credencial rota, no de borrados masivos).
    - **Credencial propia y mínima:** una Secret Key **solo** con `platform:accounts:read-status`, distinta de la del borrado de cuentas (`platform:accounts:delete`, decisión G1 pendiente del responsable), en el gestor de secretos y rotable sin despliegue.
    - **Presupuesto de tasa:** el *job* se limita a una fracción del límite de la API de administración (configurable, nunca fija) y reintenta con *backoff* ante 429. **El valor del límite no está medido**: se mide en el Sprint 0 antes de fijar el ritmo.
    - **Fuera del camino crítico:** su ausencia no bloquea Sprint 0 ni la Ola 1; se implementa junto al receptor de webhooks y su conciliación puede diferirse a la Ola 2 sin riesgo, porque el refresh ya cubre a quien tiene sesión.

### Resolución de identidad y capacidades (común a ambas)

1. En el **primer login** se hace *upsert* de `accounts` por `sub` de Clavaris (aprovisionamiento perezoso; sin webhook obligatorio).
2. En **cada petición** se reconstruye un `Actor(accountId, capabilities, organizationId?)` **desde la base de datos local** (`is_candidate_enabled`, `is_recruiter_enabled`, estado de eliminación), con una caché corta por sesión invalidada al cambiar capacidades. **Nunca** se leen capacidades de negocio de claims del token (coherente con `security-architecture.md` §2 y BR-ACCT-04).
3. Una cuenta en `PENDING_DELETION` o `ANONYMIZED` es rechazada por el *filtro de actor* salvo para cancelar el borrado (BR-DATA-04).
4. Cambiar de vista candidato ↔ reclutador es navegación; cada caso de uso exige explícitamente su capacidad.

### Pruebas obligatorias

- Una prueba recorre **todas** las rutas mutantes de `/app/**` y verifica que sin token CSRF responden 403.
- Una prueba recorre el spec OpenAPI y verifica 401 sin Bearer, y 403/404 con otra cuenta o capacidad equivocada.
- Prueba de expiración de sesión inactiva y de rotación de ID al login.

## Alternativas consideradas

| Alternativa | Por qué se rechazó |
|---|---|
| Bearer desde el navegador (estado actual de la documentación) | Exige JS para adjuntar el token y almacenarlo en el navegador (XSS); contradice ADR-0005 |
| Guardar el JWT de Clavaris en una cookie y validarlo en cada petición | Mezcla identidad y sesión de aplicación, complica la renovación del token y no permite revocar la sesión de JobSeeker de forma independiente |
| Una sola cadena de seguridad para web y API | Obliga a elegir entre CSRF activo (rompe clientes Bearer) o desactivado (deja sin protección la web) |
| Sesión en JDBC (Postgres) en lugar de Redis | Viable, pero añade carga de escritura a la base compartida con el dominio; Redis ya existe en el stack (ADR-0011) |

## Consecuencias

**Positivas**
- Los tokens nunca llegan al navegador; menor superficie XSS.
- Modelo estándar, bien soportado por Spring Security y por Clavaris (ya probado en su propio flujo).
- La API queda lista para clientes programáticos sin tocar la web.

**Negativas / trade-offs aceptados**
- Hay estado de sesión que operar (Redis); su caída cierra sesiones de la web (se acepta para el MVP; ver `02-hld` §9).
- Más configuración de seguridad (dos cadenas, CSRF en HTMX, CSP estricta) que probar.
- La CSP estricta limita el uso de scripts/estilos inline: toda la interactividad debe ser HTMX o archivos estáticos con hash/nonce.

## Acciones derivadas

- Probar en la integración: dos peticiones simultáneas de una misma sesión no deben provocar `reuse_detected` (prueba de carga del filtro de renovación).

- ✅ Hecho el 18-nov-2026: `security-architecture.md` §2 (reescrita con las tres superficies, la prueba de vida y la degradación) y `api-contract-overview.md` §3 y §7.
- ✅ Hecho el 18-nov-2026: `threat-model-stride.md` §2 (diagrama), §3.1, §4 y §5, con CSRF, suspensión no propagada, renovación concurrente, validación de `aud` y webhooks.
- ✅ Hecho el 18-nov-2026: NFR-SEC-07 trasladado a Clavaris en `nfr-quality-attributes.md`; en JobSeeker, un límite sobre el callback de login y sobre la creación de sesiones.
- ✅ Hecho el 18-nov-2026: `NFR-SEC-08` (CSRF) incorporado a `nfr-quality-attributes.md` §5 y como criterio #16 de `definition-of-done.md`.

## Preguntas abiertas

- **Resuelto en el spike del 14-nov-2026** (`architecture-review-2026-11/06-spike-contrato-clavaris.md`): Clavaris **no** soporta *back-channel logout* (su discovery no lo declara) y **sí** existe `account.email_changed` (corrección del 16-nov-2026: el spike inicial lo daba por inexistente por una búsqueda incompleta). Consecuencias para este ADR: (1) **decisión B (16-nov-2026): se solicita `offline_access`** y el refresh token se usa como prueba de vida, no como medio para prolongar la sesión; (2) la sesión web sigue acotada por inactividad y por el límite absoluto de 8 h; (3) una suspensión o borrado en Clavaris hace fallar la renovación con `invalid_grant` (verificado, 0,5 s), pero **no** cierra la sesión de JobSeeker, que debe invalidarla ella misma; el webhook `account.suspended`/`account.deleted` es la vía rápida.
- ¿Los endpoints `/app/**` que devuelven fragmentos deben aceptar también Bearer (p. ej. para pruebas E2E sin navegador)? Propuesta: no; las pruebas usan sesión real.
- Valores finales de los tiempos de sesión (30 min/8 h son un punto de partida).
- ~~Margen cuando Clavaris no responde~~ **Resuelta el 19-nov-2026: 2 horas** (por defecto, configurable, acotado por las 8 h absolutas), tras medir el comportamiento de la renovación con Clavaris real; ver regla 2 y regla 12.

## Validación previa a la aprobación (18-nov-2026)

**Veredicto: aprobar con los ajustes ya aplicados.** Es el ADR con más evidencia de ejecución real detrás.

- **Verificado con código y Clavaris real:** Authorization Code + PKCE con el cliente de Spring Security 7; renovación con cerrojo por sesión (8/8 y 24/24, también con dos instancias y sesión en Redis); detección de suspensión por `invalid_grant`; revocación y cierre de sesión (TD-SEC-100); gestión de cuentas con Secret Key (TD-SEC-101); receptor de webhooks bajo concurrencia; comportamiento con Redis caído (ADR-0045 de Clavaris). Detalle en `06-spike-contrato-clavaris.md`, addenda 1 a 7.
- **Corregido en esta revisión:** «dos cadenas» (son tres); el logout seguía hablando de *back-channel* ya descartado; la regla de orden de eventos estaba obsoleta frente a lo verificado; `aud` presentado como si el decodificador lo validara (no lo hace, G6) y como valor único; el cuerpo del webhook (con PII) se enviaba al *outbox* contra ADR-0016; la regla 9 solo cubría una de las dos causas del fallo de arranque; faltaba el margen cuando Clavaris no responde y la rotación de la clave del refresh token.
- **Decidido por el responsable el 19-nov-2026:** margen de 2 h cuando Clavaris no responde (reglas 2 y 12). Ya no bloquea implementar el filtro.
- **No verificado:** sesiones web de JobSeeker con Redis caído (el ADR ya acepta que se cierran), la CSP estricta con HTMX real y el tope de `Secure`/`__Host-` en el navegador, que se probarán en el *walking skeleton*.

