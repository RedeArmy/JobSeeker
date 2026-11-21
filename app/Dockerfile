# Dockerfile multi-etapa de marcador de posición para el único desplegable (ADR-0026); aún sin instrucciones reales.
# Reemplaza los Dockerfile de marcador por módulo, que contradecían ADR-0001/0010
# (un desplegable, un spec OpenAPI agregado, un solo endpoint /actuator/prometheus).

# ---- etapa de compilación ----
# FROM <imagen eclipse-temurin:25-jdk de compilación>
# copiar fuentes y ejecutar `mvn -pl app -am package` (compila app y los módulos de los que depende)

# ---- etapa de ejecución ----
# FROM <imagen eclipse-temurin:25-jre de ejecución, fijada por digest, escaneada con Trivy,
#       usuario no root y fuentes empaquetadas — ver 05-verificacion-riesgos-java-25.md>
# copiar el jar de app desde la etapa de compilación; imagen JVM, sin GraalVM Native en el MVP (ADR-0014)
# entrypoint: java -jar
