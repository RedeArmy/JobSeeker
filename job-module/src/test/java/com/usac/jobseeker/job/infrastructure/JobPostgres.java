package com.usac.jobseeker.job.infrastructure;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Un único Postgres 17 con la migración del módulo para todas sus pruebas de infraestructura (mismo
 * criterio que en `auth`: un contenedor por JVM, no por clase). Como `job` depende de `auth`, en el
 * classpath está también la migración real de `accounts` y la clave foránea se prueba contra ella.
 */
public final class JobPostgres {

  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
          DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
  private static DataSource dataSource;

  private JobPostgres() {}

  public static synchronized DataSource dataSource() {
    if (dataSource == null) {
      POSTGRES.start();
      dataSource =
          new DriverManagerDataSource(
              POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
      Flyway.configure()
          .dataSource(dataSource)
          .locations("classpath:db/migration")
          .load()
          .migrate();
    }
    return dataSource;
  }
}
