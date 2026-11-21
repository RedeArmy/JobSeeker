# ADR-0026: Topología de despliegue, hospedaje de Ollama y recuperación ante desastres
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | 🟡 En revisión — **propuesto**, con **decisiones pendientes del responsable** marcadas en §"Datos que el responsable debe aportar" |
| **Versión** | 1.1 — ajustado el 18-nov-2026 tras la validación completa; **sigue sin poder aprobarse** (faltan datos del responsable) |
| **Fecha** | Noviembre 2026 |
| **Documento relacionado** | ADR-0001, ADR-0002, ADR-0004, ADR-0011, ADR-0012, `integration-design.md` §5, `security-architecture.md` §4–5, `architecture-review-2026-11/02-hld-system-design.md` §2, §8, §9 |

---

## Contexto

La documentación fija **dónde vive la base de datos de producción** (Supabase) pero deja sin definir:

- dónde corre el monolito, Redis, Ollama y el stack de observabilidad;
- cómo se despliega (el `cd-staging.yml` es un placeholder y `security-architecture.md` §4 habla de un "destino de despliegue" sin nombrarlo);
- qué ocurre si falla algo (sin RTO/RPO, sin copias verificadas, sin DR);
- cuánto cuesta operar el sistema una vez terminada la tesis.

Además, el `docker-compose.yml` y las reglas del proyecto asumían un servicio y un `Dockerfile` por módulo, lo que contradice ADR-0001 (un solo desplegable, un OpenAPI agregado, un solo `/actuator/prometheus`).

## Decisión

### 1. Un solo desplegable: el módulo `app`

- Los módulos Maven (`auth`, `cv`, `job`, `matching`, `ats`, `tracker`, `common`) son **bibliotecas**.
- Un módulo nuevo **`app`** contiene `@SpringBootApplication`, la configuración transversal (cadenas de seguridad de ADR-0020, filtros de rate limit y `traceId`, ejecución de Flyway, registro del *outbox* de ADR-0016, pruebas ArchUnit de ADR-0015) y el **único `Dockerfile`**.
- Se eliminan los `Dockerfile` placeholder por módulo.

### 2. Topología de producción (inicio): una VM con Docker Compose

| Componente | Dónde | Notas |
|---|---|---|
| Proxy inverso + TLS | Caddy (contenedor, mismo host) | Certificados automáticos, HSTS, límites de tamaño de cuerpo |
| `jobseeker-app` | Contenedor JVM, mismo host | Un proceso; diseñado sin estado local (*stateless*) para poder duplicar (ver §6) |
| Redis 7 | Contenedor, mismo host | Arranca con `--notify-keyspace-events Egx` (la aplicación ya no configura el servidor, ADR-0020 regla 9). Sin persistencia obligatoria; `maxmemory` + `volatile-lru`: **todas** las claves (sesiones, caché y buckets) tienen TTL y por tanto son expulsables, así que bajo presión de memoria se cerrarían sesiones; se dimensiona con margen y se alerta por memoria (ADR-0012). Sesión (ADR-0020), caché y buckets (ADR-0011) |
| Ollama | Contenedor, mismo host, con límites de CPU/RAM (cgroups) | **Solo embeddings** en la ruta crítica; sin generación de texto por defecto |
| ClamAV | Contenedor, mismo host | Escaneo de archivos subidos |
| Prometheus, Grafana, Alertmanager | Contenedores, mismo host, **solo en red interna** | Acceso por túnel/VPN o autenticación; retención 15 d |
| PostgreSQL 16 + pgvector | **Supabase** (decisión previa) | **Dos conexiones distintas** (documentación de Supabase, consultada el 18-nov-2026): la aplicación usa el *pooler* en **modo transacción** (puerto 6543) con `prepareThreshold=0`, solo transacciones, `SET LOCAL` y sin bloqueos de sesión; **Flyway usa la conexión directa o el modo sesión (puerto 5432)**, porque toma un `pg_advisory_lock` de sesión que en modo transacción se filtra entre clientes (reproducido con PgBouncer como emulación). Supabase documenta que el modo transacción no admite sentencias preparadas, `SET` de sesión, `LISTEN/NOTIFY` ni bloqueos de sesión. `SKIP LOCKED` dentro de una transacción funciona (emulación). **Versión de pgvector:** `hnsw.iterative_scan` exige 0.8.0 o posterior, y en 0.7.x fijarlo hace fallar la consulta; hay reportes de proyectos de Supabase en 0.7.4 que requieren subir la versión de Postgres. La aplicación comprueba `select extversion from pg_extension where extname='vector'` al arrancar y activa `iterative_scan` solo si es ≥ 0.8.0. **No es opcional para la calidad:** con un filtro selectivo y sin `iterative_scan` el recall del índice cayó a 0,38 en la prueba con vectores reales (`09-…` §8), así que un proyecto de Supabase en pgvector 0.7.x no cumple el requisito |
| Almacenamiento de archivos | **Supabase Storage** (S3-compatible) | URLs firmadas de corta vida; MinIO en desarrollo |
| Correo | SMTP/API de un proveedor transaccional | Mailpit en desarrollo |
| Identidad | Clavaris (despliegue propio, repo hermano) | Misma red/host en staging para pruebas E2E. **Dependencia de disponibilidad:** si Clavaris cae, las sesiones de JobSeeker sobreviven el margen de la prueba de vida (**2 h**, decidido el 19-nov-2026, ADR-0020 regla 2) y después se cierran; Clavaris compromete ≥ 99,5 % de disponibilidad (hasta ~3,6 h al mes) y su arranque medido fue de 40–64 s, así que el margen cubre reinicios, despliegues y la mayoría de los incidentes. **Requisito de despliegue:** el operador de Clavaris debe fijar la ventana de reutilización del refresh token de la organización de JobSeeker (`refreshTokenReuseGraceSeconds` = 30 s, ADR-0046 de Clavaris); por defecto vale 0. El RTO de Clavaris limita el de JobSeeker y debe fijarse junto a este ADR. Quién lo hospeda y dónde es parte de los datos que faltan |

Tamaño de partida: **4–8 vCPU y 16 GB RAM** (a validar con las pruebas de carga del Sprint 2). Sin GPU por defecto: el modelo de embeddings de ADR-0018 (provisional `qwen3-embedding:0.6b`, 1024 dimensiones) debería ser viable en CPU para el volumen declarado (≈ 10⁴ perfiles), pero **no se ha medido en hardware con AVX2** (la VM de prueba no lo expone y sus tiempos son poco fiables, `08-…`); se reevalúa con datos medidos antes de fijar el tamaño de la VM.

### 3. Ambientes

| Ambiente | Composición |
|---|---|
| Local | `docker-compose` con Postgres+pgvector, Redis, Ollama, Mailpit, MinIO, ClamAV y observabilidad |
| Staging | Misma VM-plantilla (o más pequeña) + **proyecto Supabase separado** (mismo motor que producción) |
| Producción | Como §2 |

Flyway se aplica **automáticamente en el pipeline**, nunca manualmente en Supabase (regla ya vigente en `data-model.md` §2).

### 4. Copias de seguridad y recuperación (DR)

| Objetivo | Valor propuesto | Mecanismo |
|---|---|---|
| **RPO** | ≤ 24 h como mínimo; ≤ 15 min si el plan de Supabase incluye PITR | Respaldo automático de Supabase (verificar qué ofrece el plan contratado) **más** `pg_dump` programado diario a almacenamiento externo cifrado |
| **RTO** | ≤ 4 h | Procedimiento operativo escrito (*runbook*) de restauración de BD + redespliegue de la imagen por tag |
| **Prueba de restauración** | **Trimestral**, con evidencia (fecha, tiempo, resultado) | Restaurar en un proyecto de pruebas y ejecutar un *smoke test* |
| Archivos | Versionado/replicación de Storage según plan; el BD guarda solo referencias | — |
| Configuración | Todo en repositorio (compose, Caddyfile, dashboards, reglas de alerta); VM reproducible desde cero | — |
| Respaldos y borrado de datos | Tras restaurar un respaldo, **reaplicar** las anonimizaciones posteriores a su fecha (BR-DATA-05) | Registro de `anonymized_at` consultable |

### 5. Pipeline de entrega (GitHub Actions)

`ci-backend` (build, pruebas unitarias, ArchUnit, análisis estático, escaneo de dependencias) → `ci-integration` (Testcontainers) → `cd-staging` (construir imagen con tag = SHA, firmar/escanear, migraciones Flyway contra Supabase staging, `docker compose pull && up -d`, *health check*, *rollback* automático al tag anterior si falla) → promoción manual a producción con el mismo artefacto. Secretos solo vía GitHub Actions Secrets (ratificado en `security-architecture.md` §4).

### 6. Preparación para más de una instancia (sin desplegarla aún)

Sin estado local: sesión y caché en Redis, trabajos con `SKIP LOCKED`, archivos en Storage. Una prueba de integración levanta **dos** instancias contra el mismo Postgres/Redis para mantenerlo cierto. Escalar horizontalmente solo exige un balanceador y `docker compose --scale` o una segunda VM.

### 7. Degradación controlada

Tabla de fallos y comportamiento esperado en `02-hld-system-design.md` §9 (Ollama, Redis, Clavaris, Supabase, Storage, ClamAV, correo). Todas las integraciones salientes con tiempo límite (*timeout*), reintentos acotados y *circuit breaker*.

## Alternativas consideradas

| Alternativa | Por qué se rechazó |
|---|---|
| Kubernetes (EKS/GKE/k3s) | Operación desproporcionada para una persona y ~10² usuarios concurrentes |
| PaaS (Render, Fly.io, Railway) para la app | Viable; se descarta como defecto porque Ollama y ClamAV necesitan recursos y control de CPU/RAM que no encajan bien con instancias pequeñas de PaaS. Sigue siendo una opción si el costo de la VM resulta mayor |
| Ollama en un servicio gestionado/GPU dedicada desde el inicio | Costo recurrente alto sin evidencia de necesidad; se decide con métricas de latencia y cola |
| Desplegar un contenedor por módulo | Contradice ADR-0001/0010 y multiplica la operación |
| Sin PITR ni pruebas de restauración | Un respaldo no probado no es un respaldo |

## Consecuencias

**Positivas**
- Operación comprensible por una persona; costo predecible; entorno reproducible desde el repositorio.
- RPO/RTO explícitos que pasan a ser NFR verificables (`NFR-DR-01`).
- La ruta de crecimiento (separar Ollama, segunda instancia) no requiere rediseño.

**Negativas / trade-offs aceptados**
- Una sola VM es un único punto de fallo del plano de aplicación; 99 % mensual (≈ 7,2 h) es alcanzable con alertas y procedimientos operativos, no con redundancia.
- Ollama y la app compiten por CPU/RAM en el mismo host; se mitiga con límites cgroups, cola con espera de agrupación (*debounce*) y métricas de saturación.
- Dependencia de dos servicios gestionados (Supabase, proveedor de correo) con sus propios límites de plan.

## Datos que el responsable debe aportar (bloquean la aceptación de este ADR)

1. **Proveedor y presupuesto mensual** de la VM (Hetzner/DigitalOcean/AWS/otro) y tope de gasto total mensual (hosting + Supabase + correo).
2. **Plan de Supabase** previsto para producción y staging, y si incluye PITR/retención de respaldos.
3. **Dominio y DNS** de producción/staging.
4. **Proveedor de correo transaccional** (o SMTP institucional de la USAC, si está permitido).
5. **Responsable de guardia** y canal de alertas (Alertmanager → email + Telegram/Discord u otro).

## Acciones derivadas (aplicadas con este ADR)

- `pom.xml` padre: módulo `app` añadido.
- `app/Dockerfile` placeholder único; eliminados los `Dockerfile` placeholder de los módulos.
- `docker-compose.yml`: comentarios actualizados a un único servicio `app` + dependencias.
- las reglas del proyecto §3 y §4: contenedorización corregida.

## Preguntas abiertas

- ¿Staging comparte VM con producción o es una máquina separada? (Propuesta: separada y más pequeña, apagable fuera de uso.)
- ¿Se publica la métrica de disponibilidad (página de estado) a los usuarios de la cohorte?
- ¿Cuándo se reevalúa mover Ollama a otra máquina? Propuesta: cuando el *p95* de embeddings supere 3 s sostenidos o el uso de CPU de la app supere 70 % por Ollama.

## Validación previa a la aprobación (18-nov-2026)

**Veredicto: no corresponde aprobar todavía.** El propio ADR declara que los cinco datos de «Datos que el responsable debe aportar» bloquean su aceptación, y siguen sin existir: proveedor y presupuesto, plan de Supabase y su PITR, dominio, correo transaccional y responsable de guardia con canal de alertas. Sin ellos, RPO/RTO, costo y el tamaño de la VM no pueden fijarse.

- **Lo que ya está en vigor sin necesidad de este ADR:** el módulo `app` como único desplegable (decisión 1) está aplicado en el repositorio y es coherente con el ADR-0001 bloqueado. Si se quiere desbloquear el trabajo de código, esa decisión (y la 6, estado local nulo) puede aceptarse por separado, y las decisiones 2 a 5 y 7 esperar a los datos.
- **Corregido en esta revisión:** una afirmación de viabilidad de embeddings en CPU que citaba el modelo descartado por ADR-0018 y que no estaba medida; la regla de Redis no incluía `notify-keyspace-events` ni advertía que `volatile-lru` expulsa sesiones; el requisito de *pooler* para `SKIP LOCKED` era una suposición no verificada; y faltaba la dependencia de disponibilidad de Clavaris.
- **Pendientes que no dependen de ti:** medir embeddings en AVX2, probar `SKIP LOCKED` y Flyway contra Supabase real, y el RTO de Clavaris.

