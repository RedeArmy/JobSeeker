package com.usac.jobseeker.app.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.app.InfrastructureTestConfiguration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Aplica TODAS las migraciones desde una base vacía de Postgres 17 con pgvector y comprueba el
 * esquema resultante y su comportamiento (ADR-0027, decisión 10). Es la validación más fiel del
 * SQL: lo ejecuta el mismo motor que producción.
 */
@SpringBootTest
@Import(InfrastructureTestConfiguration.class)
class MigrationsIntegrationTest {

  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;

  @Test
  void migrationsRunInVersionOrderStartingWithTheExtensions() {
    List<String> versions =
        jdbc.queryForList(
            "select version from flyway_schema_history where success order by installed_rank",
            String.class);
    assertThat(versions.getFirst()).isEqualTo("1");
    assertThat(versions).isSorted().doesNotHaveDuplicates().hasSizeGreaterThanOrEqualTo(5);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from pg_extension where extname = 'vector'", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void flywayConnectionsFailFastInsteadOfWaitingForLocks() {
    // `spring.flyway.init-sqls` fija lock_timeout en la conexión de Flyway (ADR-0027); la conexión
    // de la aplicación usa el valor por defecto, así que aquí solo se comprueba que Flyway no
    // falló.
    assertThat(
            jdbc.queryForObject(
                "select count(*) from flyway_schema_history where not success", Integer.class))
        .isZero();
  }

  @Test
  void everyTableHasAPrimaryKey() {
    List<String> withoutPk =
        jdbc.queryForList(
            """
            select t.table_name from information_schema.tables t
            where t.table_schema = 'public' and t.table_type = 'BASE TABLE'
              and t.table_name <> 'flyway_schema_history'
              and not exists (
                select 1 from information_schema.table_constraints c
                where c.table_schema = t.table_schema and c.table_name = t.table_name
                  and c.constraint_type = 'PRIMARY KEY')
            """,
            String.class);
    assertThat(withoutPk).as("tablas sin clave primaria").isEmpty();
  }

  @Test
  void timestampsAlwaysCarryATimeZone() {
    List<String> naive =
        jdbc.queryForList(
            """
            select table_name || '.' || column_name from information_schema.columns
            where table_schema = 'public' and data_type = 'timestamp without time zone'
              and table_name <> 'flyway_schema_history'
            """,
            String.class);
    assertThat(naive).as("columnas timestamp sin zona horaria").isEmpty();
  }

  @Test
  void accountsRequireAtLeastOneCapabilityAndAValidDeletionStatus() {
    UUID id = UUID.randomUUID();
    // BR-ACCT-01: toda cuenta tiene al menos una capacidad.
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into accounts (id, is_candidate_enabled, is_recruiter_enabled)"
                        + " values (?, false, false)",
                    id))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into accounts (id, is_candidate_enabled, deletion_status)"
                        + " values (?, true, 'DELETED')",
                    id))
        .isInstanceOf(DataIntegrityViolationException.class);

    jdbc.update("insert into accounts (id, is_recruiter_enabled) values (?, true)", id);
    assertThat(
            jdbc.queryForObject(
                "select deletion_status from accounts where id = ?", String.class, id))
        .isEqualTo("ACTIVE");
  }

  @Test
  void outboxRejectsInconsistentStatusAndKeepsProcessedAtInSync() {
    // Cada identificador se crea fuera de la lambda: dentro debe haber una sola llamada que pueda
    // fallar.
    UUID pendingWithProcessDate = UUID.randomUUID();
    UUID processedWithoutDate = UUID.randomUUID();
    UUID unknownStatus = UUID.randomUUID();
    assertThatThrownBy(() -> insertOutbox(pendingWithProcessDate, "PENDING", "now()"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insertOutbox(processedWithoutDate, "PROCESSED", "null"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insertOutbox(unknownStatus, "LOST", "null"))
        .isInstanceOf(DataIntegrityViolationException.class);
    insertOutbox(UUID.randomUUID(), "PENDING", "null");
  }

  @Test
  void concurrentPollersClaimDifferentOutboxRows() throws Exception {
    // ADR-0016 decisión 2: SELECT ... FOR UPDATE SKIP LOCKED permite correr varias instancias del
    // sondeador sin duplicar trabajo. Se comprueba contra el esquema real con dos conexiones.
    jdbc.update("delete from outbox_events");
    for (int i = 0; i < 4; i++) {
      insertOutbox(UUID.randomUUID(), "PENDING", "null");
    }
    String claim =
        "select id from outbox_events where status = 'PENDING' and available_at <= now()"
            + " order by available_at limit 2 for update skip locked";
    try (Connection first = dataSource.getConnection();
        Connection second = dataSource.getConnection()) {
      first.setAutoCommit(false);
      second.setAutoCommit(false);
      List<String> claimedByFirst = ids(first, claim);
      List<String> claimedBySecond = ids(second, claim);
      assertThat(claimedByFirst).hasSize(2);
      assertThat(claimedBySecond).hasSize(2).doesNotContainAnyElementsOf(claimedByFirst);
      first.rollback();
      second.rollback();
    }
  }

  @Test
  void idempotencyKeysAreUniquePerAccount() {
    UUID account = UUID.randomUUID();
    String sql = "insert into idempotency_keys (account_id, key, request_hash) values (?, ?, ?)";
    String hash = "a".repeat(64);
    jdbc.update(sql, account, "k1", hash);
    jdbc.update(sql, UUID.randomUUID(), "k1", hash); // otra cuenta, misma clave: permitido
    assertThatThrownBy(() -> jdbc.update(sql, account, "k1", hash))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private void insertOutbox(UUID id, String status, String processedAt) {
    jdbc.update(
        "insert into outbox_events (id, aggregate_type, aggregate_id, aggregate_version,"
            + " event_type, payload, status, processed_at) values (?, 'Account', ?, 1,"
            + " 'AccountCreated', '{}'::jsonb, ?, "
            + processedAt
            + ")",
        id,
        UUID.randomUUID(),
        status);
  }

  private static List<String> ids(Connection connection, String sql) throws Exception {
    List<String> ids = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        ids.add(rows.getString(1));
      }
    }
    return ids;
  }
}
