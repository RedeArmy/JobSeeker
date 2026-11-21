# 05 — Verificación de riesgos de Java 25

| Campo | Valor |
|---|---|
| **Estado** | 🟡 En revisión |
| **Versión** | 1.0 · 14-nov-2026 |
| **Relacionado** | ADR-0014 (pregunta abierta Java 21 vs 25), `02-hld-system-design.md` §11 |
| **Método** | (1) Proyecto de prueba desechable con JDK 25.0.4 (`release=25`) y las bibliotecas críticas del diseño; (2) las mismas pruebas dentro de imágenes `eclipse-temurin:25-jre` y `25-jre-alpine` (JDK 25.0.5+8); (3) evidencia del repo hermano `../Clavaris`, que ya ejecuta Spring Boot 4.1.2 sobre Java 25 en build, Docker y CI; (4) fuentes externas |

> Convención: ✅ verificado por mí en ejecución · 🔎 evidencia de Clavaris (ejecución real en su CI, no repetida por mí) · 📄 solo fuentes externas · ⚠️ no verificado.

---

## 1. Corrección a mi recomendación anterior

Antes argumenté que "la 21 sale de soporte antes". **Era incorrecto para el horizonte de este proyecto**: Adoptium indica soporte de Temurin 21 hasta al menos dic-2029 y de Temurin 25 hasta al menos sep-2031. Ambas cubren sobradamente los ~45 sprints (hasta 2027). La ventaja de 25 es otra: **consistencia con Clavaris, el JDK ya instalado y dos años más de vida útil si JobSeeker continúa como producto tras la tesis** — no un riesgo inminente de la 21.

## 2. Resultados por riesgo

| # | Riesgo | Resultado | Evidencia y matiz |
|---|---|---|---|
| R1 | **openhtmltopdf + PDFBox + Thymeleaf no funcionan en 25** (el que más me preocupaba) | ✅ **No ocurre** | `openhtmltopdf-pdfbox 1.1.113` (Maven Central, publicado 12-nov-2026: el proyecto **está activo**; mi búsqueda previa citaba 1.1.31 de sep-2025, desactualizada). Renderizó HTML→PDF con "María José Pérez Ñandú", y con fuente TTF incrustada (DejaVu) también `→ ✓ Łódź Ελλάδα`, en Ubuntu **y** Alpine. El texto se re-extrajo del PDF con PDFBox y coincide. Tiempos: primer render 1.6–2.8 s (frío), siguientes **50–76 ms** ⇒ NFR-PERF-03 (≤ 4 s) se cumple si se hace *warm-up* al arrancar |
| R2 | ArchUnit no soporta classfile 69 | ✅ Sin riesgo | `archunit-junit5 1.5.1` (1.4.1 ya añadió Java 25). Prueba **negativa**: una clase de dominio que importa Thymeleaf hace fallar la regla (`was violated (1 times)`) — la regla de ADR-0015 realmente detecta violaciones |
| R3 | JaCoCo no instrumenta clases del JDK 25 | ✅/🔎 Depende de la versión | `0.8.16` funciona (informe generado). 🔎 Clavaris documentó que **0.8.12 falla** con `Unsupported class file major version 69` (degradación silenciosa). Fijar ≥ 0.8.14 (primera con soporte oficial de 25 según sus notas) |
| R4 | PIT (mutation testing) en 25 | ✅ | `pitest-maven 1.30.2` + `pitest-junit5-plugin 1.2.3` generó y mató mutantes (un mutante sobrevivió, comportamiento normal). 🔎 Clavaris ejecuta `mutation.yml` con Java 25 |
| R5 | **Spotless / PMD / google-java-format** | 🔎 Riesgo real de versión (no probado por mí: JobSeeker aún no los configura) | Clavaris encontró que Spotless 2.44.3 falla en 25 (`NoSuchMethodError … DeferredDiagnosticHandler`) y que PMD plugin 3.26.0 no parsea classfile 69 y **degrada reglas en silencio**; funcionan con `spotless 3.11.0` y `pmd-plugin 3.28.0`. Reutilizar esas versiones |
| R6 | Spring Boot 4.1 / Framework 7 sobre Java 25 | 🔎/📄 | Docs de Spring (página de requisitos, versión milestone) indican Java 17 como mínimo y compatibilidad hasta 25; otras fuentes listan hasta 26. 🔎 Clavaris compila, prueba (Testcontainers) y despliega 4.1.2 en Java 25 con JPA, Flyway, Security y springdoc. Evidencia fuerte; no repetida con la aplicación de JobSeeker |
| R7 | Imagen de contenedor | ✅ | `eclipse-temurin:25-jre` y `25-jre-alpine` existen y ejecutan el render. Matiz 🔎: la base actual de `25-jre` es Ubuntu 26.04; Clavaris tuvo que parchear paquetes del SO y eliminar un binario innecesario que Trivy marcaba (8 CVE altas). **No es específico de Java 25**, pero implica escanear la imagen en CI (Trivy) y fijar la versión |
| R8 | **Testcontainers vs. Docker moderno** | 🔎 Riesgo de la base Boot 4.1, **independiente de Java** | La versión de Testcontainers que gestiona el BOM (1.20.4) usa API Docker 1.32 y es rechazada por Docker Engine 29.5.2 (este mismo equipo). Clavaris la sobrescribe a `2.0.5` (artefactos renombrados). JobSeeker tendrá el mismo problema con Java 21 o 25 |
| R9 | **Retraso del BOM frente a CVE** (netty, tomcat, jackson, pgjdbc) | 🔎 Riesgo de la base Boot 4.1, **independiente de Java** | Clavaris mantiene sobrescrituras de netty, tomcat, jackson y postgresql forzadas por OWASP Dependency-Check/Trivy. Esperar un flujo recurrente de actualizaciones; ya está contemplado en NFR-SEC-09 |
| R10 | Advertencias de JVM 24+ (`sun.misc.Unsafe`, acceso nativo) | ⚠️ **Corregido el 14-nov-2026** | En la prueba inicial (solo openhtmltopdf/ArchUnit) no hubo advertencias, pero con el *stack* completo (spike de bibliotecas, `07-spike-bibliotecas-java-25.md` §5) aparecen: Netty/Lettuce llama a `System::loadLibrary` (restringido en JDK 24+; se bloqueará en el futuro) y Mockito/ByteBuddy carga un agente dinámicamente. Remedio: `--enable-native-access=ALL-UNNAMED` y Mockito como agente en Surefire |
| R11 | Funciones del lenguaje | ✅ | `sealed interface` + `record` + `switch` con patrones (la base del evaluador de elegibilidad del LLD §3.2) compilan y funcionan; classfile verificado como versión **69**. No se usan funciones *preview* |
| R12 | Ventana de soporte | 📄 | 21: ≥ dic-2029. 25: ≥ sep-2031 |
| R13 | **Bibliotecas propias de JobSeeker** | ✅ **Cerrado el 14-nov-2026** (`07-spike-bibliotecas-java-25.md`) | Spring AI 2.0.2, Hibernate 7.4 + `hibernate-vector`/pgvector 0.8.7, Bucket4j 8.22 + Lettuce 7.5, Flyway 12.4, springdoc 3.2, Spring Session indexado, Actuator/Prometheus y AWS SDK funcionan sobre Java 25 + Boot 4.1.2 (10 pruebas verdes). Pendiente solo lo que exige infraestructura externa: Ollama real, Supabase, ClamAV |
| R14 | IDE | ⚠️ | No verificado qué versión de IntelliJ usas; las recientes soportan 25 |
| R15 | GraalVM Native | n/a | Ya diferido por ADR-0014; no se evalúa |

## 3. Limitaciones de la prueba (lo que **no** demuestra)

- El *template* de CV era mínimo: sin imágenes, sin CSS complejo ni plantillas de marca reales. La **resolución de recursos externos en el render** (riesgo SSRF, `04` §1) no se probó.
- Fuente CJK: DejaVu no tiene esos glifos (`日本` salió como `##`); no es un problema de Java sino de qué fuente se incrusta. Los CV con caracteres no latinos requieren una fuente con cobertura (p. ej. Noto) empaquetada en la imagen, **no** depender de fuentes del sistema (la imagen JRE no trae fuentes).
- No se ejecutó la aplicación Spring de JobSeeker (no existe aún): la compatibilidad de Boot 4.1 con Java 25 se apoya en Clavaris.
- Tiempos medidos en este equipo, no en la VM de producción; revalidar con la prueba de carga del Sprint 2.

## 4. Conclusión y condiciones

**El riesgo técnico de pasar a Java 25 es bajo.** Lo que parecía el mayor riesgo (openhtmltopdf/PDFBox) no se materializó, y la experiencia de Clavaris muestra que los problemas reales son de **versiones de herramientas de build** (JaCoCo, Spotless, PMD), ya resueltas con versiones concretas.

Recomendación: **adoptar Java 25**, con estas condiciones:

| Condición | Detalle |
|---|---|
| Fijar versiones mínimas | JaCoCo ≥ 0.8.14 (usar 0.8.16), ArchUnit ≥ 1.4.1 (usar 1.5.1), PIT 1.30.x + `pitest-junit5-plugin` 1.2.3, Spotless 3.x, `maven-pmd-plugin` 3.28.0, Testcontainers 2.0.x, `openhtmltopdf-pdfbox` 1.1.113 |
| Spike de 1 día en el Sprint 0 | Matriz de R13: levantar un contexto Spring Boot 4.1.2 / Java 25 mínimo con pgvector (Hibernate), Redis (Lettuce + Bucket4j), Flyway contra Supabase y el cliente de Storage; resultado documentado antes de escribir módulos |
| Imagen | `eclipse-temurin:25-jre` fijada por *digest*, escaneo Trivy en CI, usuario no root, fuentes empaquetadas |
| Plan B | Si el spike R13 revela una biblioteca crítica sin soporte en 25, bajar `release` a 21 es un cambio de una línea mientras no haya código |

**Revertir a 21 solo se justificaría** si ese spike falla de forma irreparable; no hay ninguna evidencia hoy que lo sugiera.
