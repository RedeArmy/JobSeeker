package com.usac.jobseeker.app;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Postgres 17 con pgvector para las pruebas (ADR-0028): la misma versión mayor que Supabase ofrece
 * en producción, para que lo que pasa aquí valga allí. Un solo contenedor por ejecución de pruebas.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfiguration {

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgres() {
    return new PostgreSQLContainer(
        DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
  }
}
