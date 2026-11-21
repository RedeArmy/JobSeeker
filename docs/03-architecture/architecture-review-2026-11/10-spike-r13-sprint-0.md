# Spike R13 del Sprint 0 — el conjunto completo con las versiones fijadas
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | ✅ Ejecutado el 20-nov-2026 |
| **Relacionado** | `05-verificacion-riesgos-java-25.md` (R13), `07-spike-bibliotecas-java-25.md`, ADR-0014, ADR-0015, ADR-0027 |
| **Archivos** | `anexos/spike-r13/` (poms, migraciones, pruebas y configuración) |

---

## 1. Veredicto

**El conjunto completo funciona sobre Java 25 con Spring Boot 4.1.2 y no hay bloqueo para el Sprint 0; el plan B de bajar a Java 21 no hace falta.** Se construyó un proyecto de dos módulos que reproduce la estructura real (padre con versiones fijadas, `cv-module` con su migración y `app` con `V1`) y `mvn verify` terminó en verde: **8 pruebas, 0 fallos**, 1 min 19 s. El jar ejecutable arrancó contra Postgres y Redis reales, aplicó las dos migraciones en orden y sirvió salud, métricas, OpenAPI y logs JSON.

El informe 07 había probado las bibliotecas **una a una**. Este spike prueba lo que no se había probado: las versiones **fijadas juntas como las usará el repositorio**, las herramientas de calidad del CI (JaCoCo, Spotless, PMD, ArchUnit), el empaquetado, y la estructura de migraciones del ADR-0027.

**Lo que NO se pudo probar** está en el §6; lo más importante es que falta un proyecto real de Supabase.

## 2. Qué se ejecutó y qué resultado dio

| Comprobación | Resultado |
|---|---|
| Contexto de Spring Boot 4.1.2 con JPA, Flyway, Redis, sesiones indexadas, Thymeleaf, Actuator, springdoc, Bucket4j, openhtmltopdf y Spring AI | ✅ Carga completo |
| **Migraciones del ADR-0027:** `V1__enable_extensions.sql` (módulo `app`) y después `V20261121000100__cv_create_candidate_profiles.sql` (módulo `cv`) | ✅ Flyway las aplica en ese orden; la extensión `vector` existe antes de la tabla; `spring.flyway.postgresql.transactional-lock=false` es aceptado |
| Índice HNSW parcial (`WHERE discoverable AND embedding IS NOT NULL`) | ✅ Creado; `pg_indexes` lo muestra con `USING hnsw` y `WHERE` |
| Restricción todo-o-nada del bloque `embedding*` (`data-model.md` §5.1) | ✅ Una fila con `embedding_model` sin el resto es rechazada |
| Hibernate 7.4: `@JdbcTypeCode(SqlTypes.VECTOR) @Array(length = 1024)`, `ddl-auto=validate`, `cosine_distance` por JPQL, `char(64)` | ✅ El vecino más cercano sale primero y un perfil no descubrible queda fuera |
| Bucket4j 8.22.0 + Lettuce sobre Redis | ✅ De 5 intentos concede 3; otra clave es independiente |
| Spring Session con repositorio indexado (`@EnableRedisIndexedHttpSession`, `ConfigureRedisAction.NO_OP`) | ✅ `findByPrincipalName` devuelve las dos sesiones y se borran por id |
| **Thymeleaf + openhtmltopdf dentro de la aplicación** | ✅ Genera un PDF (`%PDF-`, más de 1 KB) desde una plantilla con acentos y ñ. ⚠️ No se inspeccionó el PDF visualmente: solo cabecera y tamaño |
| Actuator + Prometheus | ✅ `/actuator/prometheus` incluye `jvm_memory_used_bytes` y `hikaricp_connections`; con el jar real salen 57 líneas de métricas `jvm_` |
| springdoc 3.2.0 | ✅ `/v3/api-docs` lista `/api/v1/spike/ping` |
| Spring AI 2.0.2 | ✅ El contexto carga y existe el bean `EmbeddingModel`. ⚠️ No se llamó a Ollama |
| Logging estructurado nativo de Boot (`logging.structured.format.console=logstash`) | ✅ Con el jar real, las 38 líneas de log eran JSON válido (`@timestamp`, `message`, `logger_name`), sin `logstash-logback-encoder` |
| ArchUnit 1.5.1 sobre bytecode de Java 25 | ✅ La regla «el dominio no depende de Spring/JPA/Hibernate» pasa sobre clases reales y **falla** sobre una clase de dominio con `@Component` (prueba negativa) |
| JaCoCo 0.8.16 | ✅ Genera `jacoco.xml` en `app` |
| Spotless 3.11.0 con google-java-format 1.36.1 | ✅ Detecta violaciones, las corrige con `apply` y luego pasa |
| PMD 7.29.0 (plugin 3.28.0) con el conjunto `errorprone` | ✅ Analiza Java 25 y detectó un olor real (`NullAssignment`) en mi entidad de prueba |
| Testcontainers 2.0.5 con la imagen `pgvector/pgvector:pg16` y `redis:7-alpine` | ✅ |
| Jar ejecutable contra contenedores | ✅ Arranca en ~22 s en esta máquina; `health` UP |

## 3. Hallazgos que cambian el `pom` real (los tres que importan)

1. **El padre actual no gestiona los plugins.** `pom.xml` importa el BOM de Spring Boot en lugar de heredar de `spring-boot-starter-parent` (decisión del ADR-0014 para conservar la lista de módulos). Consecuencia: **ninguna versión de plugin queda fijada y el compilador no recibe `release 25`**. Hay que declarar `pluginManagement` en el padre: `maven-compiler-plugin` 3.16.0 con `<release>25</release>` y `<parameters>true`, `maven-surefire-plugin` 3.6.1, JaCoCo 0.8.16, Spotless 3.11.0, PMD 3.28.0 y el plugin de Spring Boot 4.1.2. Está resuelto en `anexos/spike-r13/pom.xml`.
2. **Sin la ejecución `repackage`, el jar no es ejecutable.** Por la misma razón (no hay `starter-parent`), `spring-boot-maven-plugin` no trae su ejecución por defecto: el jar salió sin manifiesto (`no main manifest attribute`). Hay que declarar la ejecución `repackage` en el `pom` de `app`. Está resuelto en `anexos/spike-r13/app/pom.xml`.
3. **Advertencias de JVM 24+.** Netty llama a `System::loadLibrary` y la JVM avisa; la biblioteca ByteBuddy (que trae Mockito) carga un agente de forma dinámica. Con `--enable-native-access=ALL-UNNAMED` el jar real no emitió **ninguna** advertencia. Ese indicador debe ir en el `ENTRYPOINT` del `Dockerfile` y en el `argLine` de Surefire (junto con `-XX:+EnableDynamicAgentLoading` para las pruebas, o se acepta el aviso). Es el R10 del informe 05.

Otros detalles menores a tener presentes:
- **Objetivos de Maven sueltos** (`pmd:check`) en un proyecto multimódulo no resuelven los módulos hermanos: hay que invocarlos junto a una fase (`package pmd:check`), igual que ya documenta Clavaris.
- Spotless imprime avisos de `sun.misc.Unsafe` que son suyos y no afectan al código de la aplicación.
- Al terminar las pruebas aparecen mensajes de reconexión de Lettuce porque el contenedor de Redis se detiene antes que el cliente: son ruido, no un fallo.

## 4. Qué confirma frente a las decisiones aprobadas

- **ADR-0027:** el esquema de carpetas por módulo con una sola instancia de Flyway y versiones por marca de tiempo funciona tal cual; `V1` de `app` queda siempre primero.
- **ADR-0012:** el logging nativo de Boot sustituye a `logstash-logback-encoder` sin perder el esquema.
- **ADR-0015:** ArchUnit corre como una prueba más de `mvn verify`, sobre Java 25, y la regla de dependencia detecta una violación real.
- **ADR-0014:** el conjunto de versiones fijado (Spring AI 2.0.2, springdoc 3.2.0, Bucket4j 8.22.0, Testcontainers 2.0.5, ArchUnit 1.5.1, openhtmltopdf 1.1.113) resuelve y funciona junto.

## 5. Qué hacer con esto en el Sprint 0

El paso siguiente es el módulo `app` real. Partir de `anexos/spike-r13/pom.xml` y `app/pom.xml` evita redescubrir los hallazgos 1 y 2. Las pruebas del anexo sirven de plantilla: la de integración con Testcontainers y la de arquitectura con su fixture negativa.

## 6. No verificado

- **Supabase real:** la versión de pgvector del proyecto (se necesita ≥ 0.8.0 para `iterative_scan`), Flyway por la conexión directa (5432), el *pooler* en modo transacción (6543) con `prepareThreshold=0`, y llamadas reales a Supabase Storage. **No hay un proyecto de Supabase disponible**; todo se probó contra un Postgres local equivalente. Es lo que más conviene cerrar.
- **Cadenas de seguridad y OIDC contra Clavaris:** no forman parte de este spike (verificadas en los addenda de `06-spike-contrato-clavaris.md`).
- **Ollama real:** el bean `EmbeddingModel` carga pero no se hizo ninguna llamada (ver `08-…`).
- **CSP estricta y cookie `__Host-` con HTMX en un navegador real.**
- **Arranque con Redis caído** (ADR-0020 regla 9): la configuración del anexo usa `ConfigureRedisAction.NO_OP`, pero no se probó el arranque sin Redis (ver `09-…` §6).
- **Aspecto del PDF** generado y rendimiento bajo carga.
