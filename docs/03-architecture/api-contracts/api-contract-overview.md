# API Contract Overview
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | 🟡 En revisión |
| **Versión** | 1.1 — §3, §6, §7 y §10 ajustadas según ADR-0020 (aprobado el 18-nov-2026) |
| **Fecha** | Agosto 2026 · actualizado en noviembre 2026 |
| **Documento relacionado** | System Design Document, Security Architecture (§2), ADR-0020 |

---

## 1. Propósito del documento

Definir las convenciones transversales que gobernarán todo endpoint del sistema desde el primer endpoint implementado (Ola 1) — no como una tarea diferida a cada módulo. Este documento no contiene endpoints concretos — es el contrato de convenciones que la documentación OpenAPI generada automáticamente (ADR-0010) hereda sin redefinir.

## 2. Estilo y formato

- **Estilo:** REST sobre HTTP, orientado a recursos.
- **Formato:** JSON (`application/json`) para toda request/response con cuerpo. Los endpoints que sirven fragmentos HTML para HTMX (ver ADR-0005) son la excepción explícita — devuelven `text/html` y viven bajo una convención de ruta separada (sección 7).
- **Codificación:** UTF-8 en toda comunicación.

## 3. Autenticación y autorización

JobSeeker es **cliente OIDC de Clavaris** (proveedor de identidad externo, repositorio separado), nunca emisor. La autenticación **no es uniforme**: depende de quién llama y por qué ruta (ADR-0020; detalle en `security-architecture.md` §2).

| Quién llama | Rutas | Credencial | Notas |
|---|---|---|---|
| **Navegador** (Thymeleaf + HTMX) | `/app/**` | Cookie de sesión (`HttpOnly`, `Secure`, `SameSite=Lax`) tras un login OIDC con PKCE | Toda petición mutante (`POST/PUT/PATCH/DELETE`) lleva el token CSRF en la cabecera `X-CSRF-TOKEN`. El navegador **nunca** envía un JWT ni lo guarda |
| **Cliente programático** (integración, futura app móvil) | `/api/v1/**` | `Authorization: Bearer <token>` firmado RS256 por Clavaris | Sin estado ni cookies; sin CSRF. Se valida firma, `iss`, `exp` y **`aud`** (lista configurable de audiencias; por defecto el `client_id` de JobSeeker). Un token emitido para otra aplicación de la misma organización se rechaza |
| **Clavaris** (eventos) | `/api/v1/webhooks/clavaris` | Firma `Clavaris-Signature` (HMAC-SHA256), sin Bearer ni sesión | Solo lo invoca Clavaris; se documenta en el OpenAPI con el esquema de seguridad `ClavarisSignature` y la etiqueta `webhooks`, y no forma parte del contrato público para consumidores |

- Sin credencial válida, acceso denegado (NFR-SEC-01).
- La autorización por capacidad de cuenta (candidato/reclutador/ambas, ADR-0008) y por propiedad de datos (BR-VIS-01 a 08, BR-CVGEN-01, BR-ACCT-04) se resuelve **desde la base local** en cada petición y se aplica en la capa de aplicación, no solo ocultando elementos en la UI (NFR-SEC-03). Las capacidades **nunca** se leen de claims del token.
- Una sesión se cierra cuando Clavaris deja de aceptar su refresh token (cuenta suspendida o borrada) o tras el margen sin respuesta de Clavaris; el cliente lo ve como un `401` (`/api/v1`) o como una redirección a login / `HX-Redirect` (`/app`).
- Todo endpoint autenticado está sujeto a rate limiting (sección 10, ADR-0011) — la autenticación exitosa no exime del límite de tasa.

## 4. Versionado

- Versionado por URI: `/api/v1/...` — decisión cerrada desde el diseño inicial (no una convención diferida), aplicada al primer endpoint de la Ola 1.
- Un cambio incompatible (breaking change) incrementa la versión mayor (`/api/v2/...`); cambios aditivos (nuevos campos opcionales) no requieren nueva versión.
- Ninguna versión se retira sin período de convivencia documentado en el TDD del módulo correspondiente.
- El spec OpenAPI agregado (sección 11, ADR-0010) expone la versión vigente y cualquier versión en período de convivencia simultáneamente — el versionado de URI y el versionado del documento OpenAPI son la misma fuente de verdad, nunca dos artefactos que puedan divergir.

## 5. Convención de rutas

| Patrón | Ejemplo | Uso |
|---|---|---|
| `/api/v1/{módulo}/{recurso}` | `/api/v1/job/postings` | Colección de recursos |
| `/api/v1/{módulo}/{recurso}/{id}` | `/api/v1/job/postings/{id}` | Recurso individual |
| `/api/v1/{módulo}/{recurso}/{id}/{sub-recurso}` | `/api/v1/matching/scores/{id}/breakdown` | Sub-recurso anidado |

## 6. Formato estándar de error

Toda respuesta de error sigue el mismo sobre (envelope), independientemente del módulo:

```json
{
  "error": {
    "code": "JOB_POSTING_NOT_FOUND",
    "message": "La vacante solicitada no existe o no está disponible.",
    "traceId": "a1b2c3d4-...",
    "timestamp": "2026-08-06T10:00:00Z"
  }
}
```

| Campo | Descripción |
|---|---|
| `code` | Código de error estable, en mayúsculas, propio del dominio — no un código HTTP genérico repetido |
| `message` | Mensaje legible, sin datos sensibles (BR-DATA-01) |
| `traceId` | Correlaciona con el logging estructurado (NFR-OBS-01) |
| `timestamp` | Momento del error en UTC, formato ISO-8601 |

Códigos de estado HTTP usados de forma consistente: `400` (validación, o una petición con el Bearer mal formado: `TOKEN_REQUEST_INVALID`), `401` (sin autenticación; en `/api/v1` con cabecera `WWW-Authenticate: Bearer`, y con `error="invalid_token"` cuando se presentó un token que no vale; códigos `AUTHENTICATION_REQUIRED` y `TOKEN_INVALID`, y el cuerpo nunca dice por qué se rechazó el token), `503` (Clavaris no responde y no se puede comprobar el token, o el login: `IDENTITY_PROVIDER_UNAVAILABLE`, con `Retry-After`; un token basura sigue recibiendo `401` porque no necesita a Clavaris para rechazarse), `403` (autenticado sin autorización — p. ej. BR-VIS-03 — o petición mutante de `/app` sin token CSRF válido, código `CSRF_TOKEN_INVALID`), `404` (recurso no encontrado o no visible para el solicitante), `409` (conflicto, p. ej. BR-TRACK-01 postulación duplicada), `422` (un archivo subido no se acepta: `FILE_REJECTED`, con el motivo `TOO_LARGE`, `UNSUPPORTED_TYPE`, `MALWARE_DETECTED` o `LIMIT_REACHED` en `details`, ADR-0021), `429` (rate limit excedido, código `RATE_LIMIT_EXCEEDED`, sección 10), `500` (error no controlado), `503` (un servicio externo del que depende la operación no responde, como el almacén de archivos o el antivirus: `UPSTREAM_UNAVAILABLE`; el resto del sistema sigue y nunca se completa la operación a medias, ADR-0021). El receptor de webhooks de Clavaris (`/api/v1/webhooks/clavaris`) responde `204` tras confirmar la transacción y, si no, `401 WEBHOOK_SIGNATURE_INVALID`, `400 WEBHOOK_PAYLOAD_INVALID`, `405 METHOD_NOT_ALLOWED`, `413 PAYLOAD_TOO_LARGE`, `503 WEBHOOK_NOT_CONFIGURED` (sin secreto) o `503 WEBHOOK_UNAVAILABLE` (fallo al aplicar; Clavaris reintenta); detalle en `docs/03-architecture/tdd/auth-module.md` §11.

## 7. Endpoints HTMX (fragmentos HTML)

Ruta separada por convención (`/app/...` en vez de `/api/v1/...`) para las vistas que Thymeleaf + HTMX consumen directamente como fragmentos HTML, evitando mezclar el contrato JSON con el contrato de fragmentos de UI. Estas rutas no forman parte del contrato de API pública ni requieren versionado semántico — son parte de la implementación del frontend server-side, sujetas a cambio junto con la vista que sirven. **Autenticación propia:** usan la sesión de la superficie A (no aceptan Bearer; las pruebas de extremo a extremo usan una sesión real), y toda petición mutante exige el token CSRF, que HTMX adjunta como `X-CSRF-TOKEN` desde una `<meta>` de la página. Ante una sesión caducada responden `401` con `HX-Redirect` hacia el login, en vez de devolver HTML de error dentro del fragmento.

## 8. Paginación

- Paginación por cursor u offset (a decidir por endpoint según patrón de acceso), usando parámetros `page` y `size` como convención por defecto (offset-based) salvo que el TDD del módulo justifique cursor-based (p. ej. para pipelines muy grandes).
- Toda respuesta paginada incluye metadatos: `{"content": [...], "page": 0, "size": 20, "totalElements": 134}`.

## 9. Idempotencia y seguridad de métodos

- `GET` no tiene efectos secundarios.
- `POST` crea recursos; no se garantiza idempotencia salvo que el endpoint lo declare explícitamente.
- `PUT`/`PATCH` para actualización; `DELETE` para eliminación (p. ej. BR-DATA-02, solicitud de eliminación de perfil).

## 10. Rate limiting (ADR-0011)

- Todo endpoint (`/api/v1/...` y fragmentos HTMX `/app/...`) está sujeto a un límite de tasa configurable — Bucket4j sobre Redis, algoritmo de token bucket, capacidad y tasa de recarga definidas en configuración de aplicación, nunca hardcodeadas.
- Clave del límite: `accountId` autenticado, o IP para endpoints previos a autenticación (inicio del flujo OIDC, *callback* de login, receptor de webhooks). El formulario de login vive en Clavaris, que aplica su propio límite de fuerza bruta.
- Respuesta al exceder: `429 Too Many Requests`, sobre de error de la sección 6 con `code: "RATE_LIMIT_EXCEEDED"`, más encabezados `Retry-After` (segundos hasta poder reintentar) y `X-RateLimit-Limit`/`X-RateLimit-Remaining`.
- Los valores concretos de límite por endpoint no son parte de este contrato transversal — se calibran por política y se documentan en el TDD del módulo correspondiente (ver ADR-0011, preguntas abiertas).

## 11. Documentación OpenAPI (ADR-0010)

- Generada automáticamente por `springdoc-openapi` a partir de los controladores anotados (`@Operation`, `@ApiResponse`, `@Schema`) — código como única fuente de verdad, no un YAML mantenido en paralelo.
- **Un único documento agregado** para todo el sistema (un solo desplegable, ADR-0001), organizado internamente por `@Tag` por módulo de negocio (`auth`, `cv`, `job`, `matching`, `ats`, `tracker`) — no un archivo separado por módulo.
- Anotado obligatorio desde el primer endpoint de la Ola 1 (`definition-of-done.md`), validado en CI contra un ruleset de linting (Spectral o equivalente) que verifica, entre otras reglas, que todo endpoint documentado cumple las convenciones de este documento (versionado de la sección 4, sobre de error de la sección 6).
- Swagger UI sin restricción en desarrollo/staging; protegida detrás de autenticación en producción (`security-architecture.md` §12). El spec declara tres esquemas de seguridad (`bearerAuth` para `/api/v1`, `ClavarisSignature` para el receptor de webhooks y, para documentación interna, la sesión de `/app`); una prueba recorre el spec y verifica `401` sin Bearer y `403`/`404` con otra cuenta o capacidad equivocada.

## 12. Alcance de este documento vs. TDD por módulo

Este documento define las reglas transversales, vigentes desde el primer endpoint (Ola 1). Cada módulo, al implementarse, produce los endpoints concretos que heredan estas convenciones sin redefinirlas — el TDD por módulo documenta decisiones específicas de ese módulo (p. ej. paginación cursor-based si aplica, sección 8), nunca reglas ya fijadas aquí.

## 13. Preguntas abiertas

- ¿Qué ruleset exacto de linting de OpenAPI se adopta (sección 11)? Misma pregunta abierta de ADR-0010.
- ¿Los valores concretos de las políticas de rate limiting (sección 10) se fijan antes del cierre de la Ola 1 o se calibran empíricamente con tráfico real en la Ola 3 (`roadmap-and-release-plan.md` §5)? Misma pregunta abierta de ADR-0011.
