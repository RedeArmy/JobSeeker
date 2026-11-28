# ADR-0014: Línea base de versiones — Spring Boot 4.1.x y depuración de dependencias
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | ✅ Aprobado — 18-nov-2026, por el responsable del proyecto, tras la validación completa descrita al final de este documento |
| **Versión** | 1.2 — 28-nov-2026: springdoc 3.2.1 con swagger 3.0.0 (ver ADR-0010, enmienda 1) |
| **Fecha** | Noviembre 2026 |
| **Documento relacionado** | `architecture-review-2026-11/02-hld-system-design.md` §11, las reglas del proyecto §3, ADR-0009 (retirado), `security-architecture.md` §2 |

---

## Contexto

las reglas del proyecto §3 fija **Spring Boot 3.4**, **Spring AI 1.0** y **JJWT 0.12.6** como decisiones bloqueadas. La revisión de arquitectura de noviembre 2026 encontró que esa línea base ya no es la correcta para un proyecto que aún no tiene código:

- **Spring Boot 3.4 salió de soporte OSS el 31-dic-2025 y la 3.5 el 30-jun-2026** (política de 13 meses por *minor*; verificado el 18-nov-2026, ver §Fuentes). La 4.0 termina el 31-dic-2026 y la **4.1 el 31-jul-2027**.
- **JJWT es innecesario.** Desde ADR-0009 (retirado) JobSeeker no emite tokens: valida tokens RS256 de Clavaris vía JWKS, y eso lo resuelve Spring Security (`oauth2-resource-server` / `oauth2-client`, que usa Nimbus internamente).
- **Spring AI** tiene ramas posteriores a la 1.0 (las fuentes reportan una 2.0 publicada en 2026); la versión debe elegirse según compatibilidad con la versión de Boot, no heredarse.
- El repositorio hermano **Clavaris ya compila con Spring Boot 4.1.2** (verificado en `../Clavaris/pom.xml`), de modo que existe experiencia y una resolución de BOM probada en el mismo equipo.
- No hay código de aplicación todavía: el costo de elegir bien la base es el mínimo que tendrá nunca.

## Decisión

1. **Spring Boot 4.1.x** (patch: el vigente al iniciar la implementación; hoy 4.1.2, el usado por Clavaris) importado vía `spring-boot-dependencies` en el `dependencyManagement` del `pom.xml` padre. Es la única fuente de versiones de bibliotecas gestionadas.
2. **Eliminar JJWT** del stack. La validación de tokens y el login OIDC usan `spring-boot-starter-oauth2-resource-server` y `spring-boot-starter-oauth2-client`.
3. **Spring AI**: se adopta únicamente dentro de `infrastructure/adapter/out/ai/` (adaptador de Ollama/LLM externo). La versión exacta se fija en el primer sprint que lo necesite, verificando compatibilidad con la versión de Boot elegida; hasta entonces no se declara en el `pom.xml`.
4. **Java 25 (LTS)** como nivel de lenguaje y runtime (`maven.compiler.release=25`), alineado con Clavaris. Sustituye a Java 21 de las reglas del proyecto §3. Fundamento: consistencia de JDK entre repos y vida útil posterior a la tesis (no la ventana de soporte: Temurin 21 está soportado hasta ≥ dic-2029). Verificación y condiciones (versiones mínimas de JaCoCo/Spotless/PMD/Testcontainers, spike de bibliotecas, imagen fijada por digest): `architecture-review-2026-11/05-verificacion-riesgos-java-25.md`. **Precisión del 21-nov-2026:** la imagen de *compilación* del Dockerfile sigue la etiqueta más nueva de Maven y hoy usa Temurin 26 (no LTS), aceptado por el responsable al fusionarse una actualización de Dependabot. Es compatible porque `maven.compiler.release=25` limita el lenguaje, la API y el bytecode a Java 25, y la construcción de la imagen falla si el bytecode no es de Java 25; el CI y la imagen de ejecución siguen en Java 25 LTS. Riesgo aceptado: una incompatibilidad que solo ocurra al *compilar* con 26 no la vería el CI, que compila con 25. **Precisión del 27-nov-2026 (TD-024):** la imagen de compilación vuelve a Java 25 LTS (`maven:3.9-eclipse-temurin-25-noble`, fijada por *digest*), así que compilación, CI y ejecución usan el mismo JDK y el riesgo anterior desaparece. La etiqueta fija la versión de Java: Dependabot solo propondrá *digests* nuevos de esa misma línea.
5. **GraalVM Native no se compromete en el MVP.** Se conserva la consideración de compatibilidad ("no introducir reflection innecesaria"), pero la imagen de producción es JVM (+ AppCDS opcional). Motivo: openhtmltopdf/PDFBox (fuentes, reflection), Hibernate y Spring AI exigen configuración de *hints* con riesgo alto y beneficio bajo para este alcance.
6. Cualquier dependencia nueva requiere: versión gestionada por el BOM (o fijada con justificación), licencia compatible y revisión de CVE en CI (OWASP Dependency-Check / Dependabot).
7. **Política de actualización de Boot.** El soporte OSS de una *minor* dura ~13 meses: 4.1 termina el 31-jul-2027, dentro de la ejecución del roadmap (~10 meses desde el inicio). Se planifica una subida a la *minor* vigente antes de esa fecha (reservar capacidad al inicio de la Ola 3 o antes), y los parches de seguridad de la *minor* en uso se aplican con Dependabot sin esperar a la subida.
8. **Sobrescrituras conocidas del BOM:** Testcontainers debe ser ≥ 2.0.5 (la 1.x que trae el BOM de Boot es rechazada por Docker 29, verificado en `07-spike-bibliotecas-java-25.md`). Se fija en el `pom.xml` padre con comentario.

## Alternativas consideradas

| Alternativa | Por qué se rechazó |
|---|---|
| Mantener Spring Boot 3.4 | Sin parches de seguridad OSS; migrar después tiene un costo creciente con cada módulo implementado |
| Spring Boot 3.5.x como destino | **Ya no es un plan B válido:** su soporte OSS terminó el 30-jun-2026, antes de empezar a implementar. Si una dependencia crítica no soportara Boot 4, la salida es un *fork* o una alternativa a esa dependencia, no volver a una línea sin soporte |
| Mantener JJWT "por si acaso" | Duplica la superficie de validación de tokens y contradice la delegación a Clavaris |
| Fijar Spring AI 1.0 ahora | Puede quedar incompatible con Boot 4; decisión innecesariamente temprana |

## Consecuencias

**Positivas**
- Soporte OSS vigente al inicio de la implementación y durante ~8 de los ~10 meses del roadmap (4.1 hasta el 31-jul-2027); después hace falta una subida planificada (decisión 7). Misma base que Clavaris.
- Menos dependencias y menos código de seguridad propio.

**Negativas / trade-offs aceptados**
- Boot 4 implica Spring Framework 7 y cambios de API respecto a la serie 3.x; la documentación y ejemplos de la web siguen mayormente en 3.x. Se mitiga consultando las notas de migración oficiales y reutilizando los patrones ya probados en Clavaris.
- Si una biblioteca necesaria (p. ej. openhtmltopdf) no es compatible con Jakarta/Framework 7 en su versión actual, se evalúa un *fork*/alternativa antes de ceder en la versión de Boot.

## Acciones derivadas (aplicadas con este ADR)

- `pom.xml` padre: BOM de Spring Boot 4.1.2, `maven.compiler.release=25`, módulo `app` (ver ADR-0026).
- las reglas del proyecto §3 y §10 actualizados (18-nov-2026: ADR aprobado).
- `README.md`: referencia a Spring Boot corregida.

## Preguntas abiertas

- **Java 25 decidido por el responsable (14-nov-2026).** La prueba técnica exploratoria de bibliotecas **se ejecutó el 14-nov-2026** (`architecture-review-2026-11/07-spike-bibliotecas-java-25.md`): Spring AI 2.0.2, Hibernate 7.4 + pgvector, Bucket4j + Lettuce, Flyway, springdoc, Spring Session y Actuator funcionan sobre Java 25 + Boot 4.1.2. Pendiente únicamente lo que requiere infraestructura externa: Ollama real, Supabase (pooler, pgvector, Storage) y ClamAV. Volver a `release=21` ya no tiene motivo técnico conocido.
- ~~Versión de Spring AI~~ **Verificada:** 2.0.2 funciona con Boot 4.1.2 y Java 25 (`07-spike-bibliotecas-java-25.md`). Se fija al declararla. Sigue abierta la compatibilidad de Spring Modulith 2.x, solo si se reconsidera en ADR-0015.
- ¿Existe versión de openhtmltopdf compatible con la base elegida? Verificar con una prueba exploratoria de 1 día antes de la Ola 3.

## Fuentes

- Política de soporte de Spring (13 meses por *minor*): <https://spring.io/support-policy/> y <https://enterprise.spring.io/lts-releases/>.
- Fechas por versión (3.4: 31-dic-2025; 3.5: 30-jun-2026; 4.0: 31-dic-2026; 4.1: 31-jul-2027): <https://endoflife.date/spring-boot>, consultado el 18-nov-2026. No se pudo leer la tabla de fechas de spring.io directamente: las fechas vienen de ese rastreador de terceros, que coincide con la política publicada.

## Validación previa a la aprobación (18-nov-2026)

**Veredicto: aprobar con los ajustes ya aplicados.** No hay bloqueo técnico.

- `mvn validate` sobre el POM padre resuelve el BOM 4.1.2 con `release=25` y el módulo `app`.
- Línea base probada con código: Boot 4.1.2/Java 25 con Spring AI 2.0.2, Hibernate 7.4 + pgvector, Flyway, Bucket4j + Lettuce, springdoc 3.2.0, Spring Session y Actuator (`07-…`), más el cliente OAuth2 de Spring Security 7 contra Clavaris real (`06-…`).
- Corregido: fecha de GA de Boot 4.0 no verificada (eliminada); el plan B de Boot 3.5 estaba caducado; faltaba la política de actualización y la sobrescritura de Testcontainers.
- Referencias obsoletas fuera de las ADRs: `system-design-document.md` (diagrama «Spring Boot 3.4») y ADR-0010 (menciona Boot 3.x; springdoc 3.x es lo verificado). Se corrigen aparte.

## Actualización de la línea base (28-nov-2026)

- springdoc **3.2.1** con swagger **3.0.0** (Jakarta y Jackson 3), en lugar de springdoc 3.2.0. Una versión de parche de springdoc cambió de versión mayor a swagger-core, y la actualización solo de springdoc impedía arrancar la aplicación. El detalle y el cambio de la herramienta de validación del spec están en la enmienda 1 del ADR-0010.
- Dependencia nueva, solo en pruebas: `com.networknt:json-schema-validator` 3.0.9 (Apache 2.0), fijada en el POM padre con su justificación, como pide la decisión 6.

