package com.usac.jobseeker.matching.infrastructure;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Un único Postgres 17 con pgvector y la migración del módulo para todas sus pruebas de
 * infraestructura (un contenedor por JVM, no por clase). La extensión `vector` la crea en
 * producción la V1 de `app`, que no está en el classpath de este módulo, así que aquí se crea antes
 * de migrar.
 */
public final class MatchingPostgres {

  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
          DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
  private static DataSource dataSource;

  private MatchingPostgres() {}

  public static synchronized DataSource dataSource() {
    if (dataSource == null) {
      POSTGRES.start();
      dataSource =
          new DriverManagerDataSource(
              POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
      try (Connection connection = dataSource.getConnection();
          Statement statement = connection.createStatement()) {
        statement.execute("create extension if not exists vector");
      } catch (SQLException failure) {
        throw new IllegalStateException("no se pudo crear la extensión vector", failure);
      }
      Flyway.configure()
          .dataSource(dataSource)
          .locations("classpath:db/migration")
          .load()
          .migrate();
    }
    return dataSource;
  }
}
