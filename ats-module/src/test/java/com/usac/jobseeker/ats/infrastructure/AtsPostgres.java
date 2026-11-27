package com.usac.jobseeker.ats.infrastructure;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Un único Postgres 17 con pgvector para las pruebas de infraestructura de `ats` (un contenedor por
 * JVM). `ats` depende de `auth`, `cv`, `job` y `matching`, así que sus migraciones están en el
 * classpath y las claves foráneas de `applications` se prueban contra las tablas reales.
 */
public final class AtsPostgres {

  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
          DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
  private static DataSource dataSource;

  private AtsPostgres() {}

  public static synchronized DataSource dataSource() {
    if (dataSource == null) {
      POSTGRES.start();
      dataSource =
          new DriverManagerDataSource(
              POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
      // La extensión la crea en producción la V1 de `app`, que no está en este classpath.
      try (Connection connection = dataSource.getConnection();
          Statement statement = connection.createStatement()) {
        statement.execute("create extension if not exists vector");
      } catch (SQLException failure) {
        throw new IllegalStateException("no se pudo crear la extensión vector", failure);
      }
      // Como en `app` (ADR-0027, decisión 8): `matching` crea un índice CONCURRENTLY.
      Flyway.configure()
          .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
          .dataSource(dataSource)
          .locations("classpath:db/migration")
          .load()
          .migrate();
    }
    return dataSource;
  }

  /** Lo mínimo que una postulación referencia: candidato con perfil y CV, y una vacante ajena. */
  public record Parties(UUID account, UUID profile, UUID cvFile, UUID job) {}

  public static Parties parties(JdbcClient jdbc) {
    UUID candidate = account(jdbc, true, false);
    UUID recruiter = account(jdbc, false, true);
    UUID profile = UUID.randomUUID();
    jdbc.sql(
            "insert into candidate_profiles (id, account_id, contact_email)"
                + " values (:id, :account, 'a@b.co')")
        .param("id", profile)
        .param("account", candidate)
        .update();
    UUID cv = UUID.randomUUID();
    jdbc.sql(
            "insert into candidate_cv_files (id, candidate_profile_id, object_key, display_name,"
                + " file_type, size_bytes, sha256, is_current, uploaded_at)"
                + " values (:id, :profile, :key, 'cv.pdf', 'PDF', 10, repeat('a', 64), true, now())")
        .param("id", cv)
        .param("profile", profile)
        .param("key", "cv/" + UUID.randomUUID())
        .update();
    return new Parties(candidate, profile, cv, job(jdbc, recruiter));
  }

  /** Otra vacante del mismo tipo, para probar que una postulación no bloquea las demás. */
  public static UUID job(JdbcClient jdbc, UUID recruiter) {
    UUID job = UUID.randomUUID();
    jdbc.sql(
            "insert into job_postings (id, recruiter_account_id, title, description, status,"
                + " published_at) values (:id, :recruiter, 'Backend', 'Java', 'PUBLISHED', :at)")
        .param("id", job)
        .param("recruiter", recruiter)
        .param("at", java.sql.Timestamp.from(Instant.parse("2026-11-20T10:00:00Z")))
        .update();
    return job;
  }

  public static UUID account(JdbcClient jdbc, boolean candidate, boolean recruiter) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "insert into accounts (id, is_candidate_enabled, is_recruiter_enabled)"
                + " values (:id, :candidate, :recruiter)")
        .param("id", id)
        .param("candidate", candidate)
        .param("recruiter", recruiter)
        .update();
    return id;
  }
}
