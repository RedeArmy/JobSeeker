# 07 — Spike de bibliotecas sobre Java 25 (resultado)

| Campo | Valor |
|---|---|
| **Estado** | 🟡 En revisión |
| **Versión** | 1.0 · 14-nov-2026 |
| **Relacionado** | ADR-0014 (línea base y riesgo R13), `05-verificacion-riesgos-java-25.md`, `02-hld-system-design.md` §5.2, ADR-0011, ADR-0012, ADR-0020 |
| **Método** | Proyecto desechable Spring Boot **4.1.2 / Java 25.0.4** con las bibliotecas que el diseño usa y que aún no estaban probadas, ejecutado con **Testcontainers 2.0.5** contra `pgvector/pgvector:pg16` (PostgreSQL 16.16, pgvector 0.8.7) y `redis:8` reales. 10 pruebas, todas verdes. El proyecto se eliminó al terminar |

> Convención: ✅ ejecutado y observado · 🔎 evidencia del repo hermano Clavaris (misma base) · 📄 solo documentación · ⚠️ no verificado.

---

## 1. Veredicto

**El riesgo R13 queda cerrado para las bibliotecas probadas: funcionan sobre Java 25 + Boot 4.1.2.** No apareció ningún bloqueo para volver a Java 21. Aparecieron **ocho diferencias de Boot 4 / versiones** que conviene conocer antes de escribir código (§3), **una corrección a mi HLD sobre pgvector** (§4) y **dos advertencias de JVM** que mi informe anterior daba por inexistentes (§5).

Lo que **no** se pudo verificar (§7) incluye **Ollama real**: el disco estaba al 98 %, no descargué la imagen (varios GB) y probé Spring AI contra un servidor simulado.

## 2. Resultado por biblioteca

| Biblioteca (versión efectiva) | Prueba | Resultado |
|---|---|---|
| **Spring Boot 4.1.2** / Spring Framework 7.0.10 | Contexto completo con 12 starters | ✅ |
| **Hibernate 7.4.13 + `hibernate-vector`** (gestionado por el BOM) | Entidad con `@JdbcTypeCode(SqlTypes.VECTOR) @Array(length=768) float[]`; `ddl-auto=validate` contra `vector(768)`; ida y vuelta; kNN por JPQL (`cosine_distance`) y por SQL nativo (`<=>`) | ✅ Ambas consultas devuelven el mismo vecino más cercano |
| **Flyway 12.4.0** + `flyway-database-postgresql` | Migración con `CREATE EXTENSION vector`, tabla `vector(768)` e índice HNSW **parcial** (`WHERE discoverable`) | ✅ Índice creado con método `hnsw` |
| **PostgreSQL JDBC 42.7.14** | Todas las pruebas de BD | ✅ |
| **Bucket4j `bucket4j_jdk17-lettuce` 8.22.0** + **Lettuce 7.5.2** | Cubo de capacidad 3: de 5 intentos concede 3; otra clave (otra cuenta) es independiente | ✅ (ADR-0011 viable tal cual; Clavaris eligió un limitador propio, no es obligatorio imitarlo) |
| **Spring Session Data Redis** (repositorio indexado) | Dos sesiones con el mismo `PRINCIPAL_NAME_INDEX_NAME`; `findByPrincipalName` las devuelve y se borran por id | ✅ Es el mecanismo para cerrar todas las sesiones de una cuenta al llegar un webhook (spike de Clavaris, G2) |
| **Spring AI 2.0.2** (`spring-ai-starter-model-ollama`) | `EmbeddingModel.embed()` contra un servidor HTTP simulado de `/api/embed` | ✅ 768 dimensiones. ⚠️ **No** contra Ollama real |
| **springdoc 3.2.0** | `/v3/api-docs` lista el endpoint | ✅ (la serie 2.x no soporta Boot 4; 3.0.0 es la primera que sí) |
| **Actuator + Micrometer Prometheus** | `/actuator/prometheus` incluye `jvm_memory_used_bytes` y `hikaricp_connections*` | ✅ |
| **logstash-logback-encoder 9.0** | Codifica un evento a JSON | ✅, pero ver §6: **innecesario** |
| **AWS SDK v2 `s3` 2.57.12** + `url-connection-client` | Construcción del cliente con `endpointOverride` y credenciales estáticas | ✅ (⚠️ sin llamada real a Supabase Storage) |
| **Testcontainers 2.0.5** con Docker 29.5.2 | Postgres y Redis efímeros | ✅ (🔎 la 1.x que trae el BOM de Boot es rechazada por Docker 29; ver informe 05, R8) |
| Thymeleaf starter | Presente en el *classpath*; el contexto carga | ✅ (render no probado; ver informe 05 para openhtmltopdf) |
| **Resilience4j 2.4.0** | — | 🔎 Probado en Clavaris, no por mí |

Versiones efectivas relevantes: Jackson **2.21.7 y 3.1.7 conviven** (Boot 4 usa 3.x; springdoc/Spring AI arrastran 2.x). Clavaris ya tuvo que fijar CVEs en **ambas** líneas; esperar lo mismo.

## 3. Diferencias de Boot 4 / versiones encontradas

| # | Diferencia | Acción |
|---|---|---|
| 1 | `@AutoConfigureMockMvc` cambió de paquete: ahora `org.springframework.boot.webmvc.test.autoconfigure` y requiere `spring-boot-starter-webmvc-test` | Usar el starter nuevo en las pruebas de controladores |
| 2 | **Spring Session: `spring.session.*` no configura nada** en Boot 4; hace falta `@EnableRedisIndexedHttpSession` explícito (🔎 Clavaris lo documentó como TD-ARCH-002 y ✅ lo confirmé) | Clase `DistributedSessionConfig` en `app`; **repositorio indexado** (necesario para `findByPrincipalName`) |
| 3 | Testcontainers 2.x renombró artefactos y clases: `testcontainers-postgresql`, `org.testcontainers.postgresql.PostgreSQLContainer` (ya no genérica) | Fijar `testcontainers.version=2.0.5` en el BOM del padre |
| 4 | Starters renombrados: `spring-boot-starter-flyway` (ya no basta `flyway-core`), `spring-boot-starter-webmvc-test` | Declararlos así en el `pom` |
| 5 | **Dos Jackson** en el *classpath* (2.x y 3.x) | Gestionar CVE en ambos (pin en el padre) |
| 6 | Un repositorio Spring Data o una entidad **anidada** dentro de otra clase no se detecta / toma el nombre `Clase$Entidad` | Solo afecta a pruebas improvisadas; en el código real cada una va en su archivo |
| 7 | Spring AI: la línea compatible con Boot 4 es la **2.x** (estable 2.0.2; 2.1.0 está en RC) | Fijar `spring-ai-bom:2.0.2` cuando se use por primera vez |
| 8 | springdoc debe ser ≥ 3.0 | Fijar `3.2.0` |

## 4. pgvector a la escala de NFR-SCAL-02 (10.000 perfiles × 768 dimensiones)

Datos **sintéticos** (60 centroides gaussianos + ruido, normalizados): sirven para medir costes e índices, **no** para estimar calidad de recuperación real.

| Medida | Resultado |
|---|---|
| Carga de 10.000 vectores | 3,6 s |
| Construcción del índice HNSW parcial (≈ 9.000 vectores) | 4,1 s |
| Tamaño del índice | **35 MB** (≈ 3,9 KB por vector) |
| ¿El plan usa el índice con `WHERE discoverable`? | ✅ Sí |
| Latencia kNN top-10 (200 consultas, tras calentamiento) | **p50 3,3 ms · p95 6,2 ms · máx 10 ms** |
| Recall@10 frente al exacto (`ef_search` 40 y 100) | 1,000 (datos agrupados; en datos reales será menor) |

**Corrección a `02-hld-system-design.md` §5.2.** Afirmé que un filtro selectivo dejaba el resultado incompleto "con HNSW". Lo medí con un filtro residual del 10 %:

| Escenario | Filas devueltas (pedidas 10) | Latencia |
|---|---|---|
| Plan por defecto | **10** — el planificador **abandona el índice** y hace un escaneo exacto con ordenación | ≈ 110 ms |
| Índice forzado, **sin** *iterative scan* | **4** (el fallo que describí) | — |
| Índice forzado, con `hnsw.iterative_scan = relaxed_order` | **10** | — |

Conclusiones: (a) el problema es real, pero solo aparece **si el planificador elige el índice**; a 10.000 filas no lo elige y el escaneo exacto es correcto y cuesta ~110 ms (muy por debajo de NFR-PERF-04, 3 s); (b) al crecer la tabla el planificador pasará al índice, por lo que **conviene fijar siempre `SET LOCAL hnsw.iterative_scan = relaxed_order`** en las consultas de sugerencia, como defensa y sin costo medible; (c) mi estimación de "≈ 34 MB crudos + 1,5–2× de índice" era conservadora: el índice completo ocupa 35 MB.

⚠️ No se verificó qué versión de pgvector ofrece **Supabase** ni si habilita `hnsw.iterative_scan` (existe desde pgvector 0.8.0; aquí 0.8.7). Comprobar con `select * from pg_available_extensions where name='vector'` en el proyecto de Supabase.

## 5. Advertencias de JVM 24+ (corrige el informe 05, R10)

Mi informe de Java 25 decía "sin advertencias". Con el *stack* completo aparecen dos:

| Advertencia | Origen | Acción |
|---|---|---|
| `System::loadLibrary has been called by io.netty.util.internal.NativeLibraryUtil … Use --enable-native-access=ALL-UNNAMED` | Netty (dependencia de Lettuce) | Añadir `--enable-native-access=ALL-UNNAMED` al `ENTRYPOINT` del `Dockerfile` y a `argLine`. Hoy es un aviso; el propio JDK indica que se **bloqueará** en una versión futura |
| `A Java agent has been loaded dynamically` / Mockito se *auto-adjunta* | Mockito/ByteBuddy en pruebas | Configurar Mockito como agente en `argLine` de Surefire (o `-XX:+EnableDynamicAgentLoading` como paliativo) |

## 6. Enmiendas propuestas a ADRs

| ADR | Propuesta |
|---|---|
| **ADR-0012** (bloqueado, requiere ADR nuevo/enmienda) | Sustituir `logstash-logback-encoder` por el **structured logging nativo de Boot** (`logging.structured.format.console=logstash`). ✅ Se comprobó que emite el mismo esquema JSON (`@timestamp`, `@version`, `message`, `logger_name`, `thread_name`, `level`, `level_value`) sin dependencia extra, y evita arrastrar una tercera pieza del *classpath* de Jackson 2. las reglas del proyecto §3 menciona el codificador de forma explícita, por eso es una enmienda formal y no un cambio silencioso |
| ADR-0020 (propuesto) | Añadir: `@EnableRedisIndexedHttpSession` explícito; repositorio **indexado** por cuenta. 📄 Spring Session necesita notificaciones de claves de Redis (`notify-keyspace-events`); en Redis administrado donde `CONFIG SET` esté bloqueado hay que activarlo en el servidor. No lo verifiqué: en Redis propio dentro de la VM (ADR-0026) no es problema |
| ADR-0014 (propuesto) | Fijar: Spring AI **2.0.2**, springdoc **3.2.0**, Bucket4j **8.22.0**, Testcontainers **2.0.5**, AWS SDK **2.57.12** (todos ya verificados en esta base) |
| ADR-0026 (propuesto) | `--enable-native-access=ALL-UNNAMED` en el `ENTRYPOINT` |

## 7. Qué **no** se verificó

- **Ollama real:** compatibilidad del contrato `/api/embed` con la versión de Ollama que se despliegue, latencia de embeddings en CPU y calidad en español de `nomic-embed-text` frente a `bge-m3`/`qwen3-embedding` (ADR-0018). Es la medición más importante que sigue pendiente; requiere liberar disco o ejecutarla en otra máquina.
- **Supabase:** pooler de conexiones con Flyway y `SKIP LOCKED`, versión de pgvector, Storage por S3.
- **ClamAV** (cliente), **Thymeleaf + HTMX** renderizados, `openhtmltopdf` dentro de la aplicación Boot (probado aparte, informe 05).
- **Datos reales:** el recall y las latencias de §4 son sintéticos.
- **Carga concurrente:** una sola conexión secuencial; NFR-PERF/NFR-SCAL requieren una prueba con k6/Gatling.
- **Docker del equipo:** el disco está al 98 % (≈ 3 GB libres). Docker reporta ≈ 15 GB de imágenes y ≈ 13 GB de caché de compilación recuperables; un `docker system prune` de tu parte lo resolvería (no lo ejecuté por ser destructivo).
