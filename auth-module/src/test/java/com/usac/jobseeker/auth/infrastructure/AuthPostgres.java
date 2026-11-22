package com.usac.jobseeker.auth.infrastructure;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Un único Postgres 17 con la migración del módulo para todas sus pruebas de infraestructura.
 *
 * <p>Arrancar un contenedor por clase de prueba costaría unos diez segundos cada vez; se levanta
 * una sola vez por JVM y Testcontainers lo retira al terminar. Cada prueba usa identificadores
 * nuevos, así que compartir la base no las acopla.
 */
public final class AuthPostgres {

  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
          DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
  private static DataSource dataSource;

  private AuthPostgres() {}

  public static synchronized DataSource dataSource() {
    if (dataSource == null) {
      POSTGRES.start();
      dataSource =
          new DriverManagerDataSource(
              POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
      // Solo se migra lo de este módulo: en su classpath únicamente está su propia carpeta.
      Flyway.configure()
          .dataSource(dataSource)
          .locations("classpath:db/migration")
          .load()
          .migrate();
    }
    return dataSource;
  }
}
