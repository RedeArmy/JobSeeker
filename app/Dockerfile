# syntax=docker/dockerfile:1
#
# Imagen del único desplegable (ADR-0026, ADR-0014: imagen JVM, sin GraalVM Native en el MVP).
# Construir desde la raíz del repositorio:  docker build -f app/Dockerfile -t jobseeker-app .
#
# Las imágenes base van fijadas por digest: una etiqueta se puede mover y cambiar lo que se despliega
# sin que cambie este archivo. Dependabot propone el digest nuevo (ecosistema `docker`).

# ---- etapa de compilación ----
# Temurin 25 (LTS, ADR-0014), la misma que usa el CI y la imagen de ejecución. Dependabot propuso una vez
# cambiar a Temurin 26 y a una etiqueta flotante (`3.9-…`): se rechazó a propósito, no es LTS.
FROM maven:3.9-eclipse-temurin-26-noble@sha256:b2c1ad85954592f9928e84327c65201f308ad9b5d8ed7d5b823717c97bf23fbb AS build
WORKDIR /src
COPY . .
# La calidad (formato, PMD, pruebas) ya la valida el CI con `mvn verify`; aquí solo se empaqueta.
# La caché de Maven evita descargar las dependencias en cada construcción.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -q -pl app -am -DskipTests -Dspotless.skip=true -Dpmd.skip=true -Djacoco.skip=true package \
 && cp app/target/app-*-SNAPSHOT.jar /tmp/app.jar \
 && java -Djarmode=tools -jar /tmp/app.jar extract --layers --launcher --destination /layers

# ---- etapa de ejecución ----
FROM eclipse-temurin:25.0.5_8-jre-noble@sha256:23481dc33a759b79d62814ceb1ec27a0d1a84da9d7aab0609577427176fbb1b7 AS runtime
# Fuentes empaquetadas: la generación de PDF (openhtmltopdf) no debe depender de las del anfitrión.
RUN apt-get update \
 && apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 10001 jobseeker \
 && useradd --system --uid 10001 --gid jobseeker --no-create-home --shell /usr/sbin/nologin jobseeker
WORKDIR /app
# Una capa por tipo de contenido: las dependencias casi no cambian y se reutilizan entre construcciones.
COPY --from=build --chown=10001:10001 /layers/dependencies/ ./
COPY --from=build --chown=10001:10001 /layers/spring-boot-loader/ ./
COPY --from=build --chown=10001:10001 /layers/snapshot-dependencies/ ./
COPY --from=build --chown=10001:10001 /layers/application/ ./
# Usuario sin privilegios (UID numérico, para que Kubernetes y similares puedan verificarlo).
USER 10001:10001
EXPOSE 8080
# --enable-native-access evita los avisos de la JVM 24+ (hallazgo 3 del spike R13). El 75 % de la
# memoria del contenedor para el heap deja margen al resto de la JVM.
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
