package com.usac.jobseeker.app.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.app.database.MigrationRules.Migration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Convenciones del ADR-0027 sobre los archivos SQL reales, y pruebas de que el comprobador detecta
 * cada infracción (sin las negativas, un comprobador roto dejaría pasar todo en silencio).
 */
class MigrationConventionsTest {

  @Test
  void theRealMigrationsFollowTheConventions() throws IOException {
    List<Migration> migrations = realMigrations();
    assertThat(migrations).as("migraciones encontradas").hasSizeGreaterThanOrEqualTo(5);
    assertThat(MigrationRules.check(migrations)).isEmpty();
  }

  @ParameterizedTest(name = "{0}: {2}")
  @CsvSource(
      delimiter = '|',
      value = {
        "NAME|auth|V2__accounts.sql|create table accounts (id uuid primary key);",
        "NAME|auth|v20261121100300__auth_create_accounts.sql|create table accounts (id uuid primary key);",
        "MODULE|cv|V20261121100300__auth_create_accounts.sql|create table accounts (id uuid primary key);",
        "OWNER|cv|V20261121100300__cv_create_accounts.sql|create table accounts (id uuid primary key);",
        "OWNER|auth|V20261121100300__auth_create_widgets.sql|create table widgets (id uuid primary key);",
        "OWNER|job|V20261121100300__job_alter_accounts.sql|alter table accounts add column x text;",
        "INDEX|auth|V20261121100300__auth_index_accounts.sql|create index idx_a on accounts (id);",
        "FK-ORDER|auth|V20261121100300__auth_ref_job.sql|create table accounts (id uuid primary key, j uuid references job_postings (id));",
        "FK-ORDER|app|V20261121100300__app_ref_accounts.sql|create table audit_log (id uuid primary key, a uuid references accounts (id));",
      })
  void eachConventionIsEnforced(String rule, String module, String fileName, String sql) {
    List<String> violations = MigrationRules.check(List.of(new Migration(module, fileName, sql)));
    assertThat(violations).as("infracciones de %s", fileName).anyMatch(v -> v.startsWith(rule));
  }

  @Test
  void aRepeatedTimestampIsRejected() {
    String sql = "create table accounts (id uuid primary key);";
    List<String> violations =
        MigrationRules.check(
            List.of(
                new Migration("auth", "V20261121100300__auth_create_accounts.sql", sql),
                new Migration(
                    "app",
                    "V20261121100300__app_create_audit_log.sql",
                    "create table audit_log (id uuid primary key);")));
    assertThat(violations).anyMatch(v -> v.startsWith("TIMESTAMP"));
  }

  @Test
  void aForeignKeyMustPointToATableCreatedEarlier() {
    List<String> violations =
        MigrationRules.check(
            List.of(
                new Migration(
                    "job",
                    "V20261121100100__job_create_postings.sql",
                    "create table job_postings (id uuid primary key, c uuid references"
                        + " candidate_profiles (id));"),
                new Migration(
                    "cv",
                    "V20261121100200__cv_create_candidates.sql",
                    "create table candidate_profiles (id uuid primary key);")));
    assertThat(violations).anyMatch(v -> v.startsWith("FK-ORDER") && v.contains("anterior"));
  }

  @Test
  void allowedPatternsPass() {
    List<String> violations =
        MigrationRules.check(
            List.of(
                new Migration("app", "V1__enable_extensions.sql", "create extension vector;"),
                new Migration(
                    "cv",
                    "V20261121100100__cv_create_candidates.sql",
                    "-- referencia a job en un comentario: references job_postings\n"
                        + "create table candidate_profiles (id uuid primary key);\n"
                        + "create index idx_c on candidate_profiles (id);"),
                new Migration(
                    "job",
                    "V20261121100200__job_create_postings.sql",
                    "create table job_postings (id uuid primary key, c uuid references"
                        + " candidate_profiles (id));"),
                new Migration(
                    "cv",
                    "V20261121100300__cv_index_candidates.sql",
                    "create index concurrently idx_c2 on candidate_profiles (id);")));
    assertThat(violations).isEmpty();
  }

  private static List<Migration> realMigrations() throws IOException {
    Path root = repositoryRoot();
    List<Migration> migrations = new ArrayList<>();
    try (Stream<Path> dirs = Files.list(root)) {
      for (Path dir : dirs.sorted().toList()) {
        Path migrationDir = dir.resolve("src/main/resources/db/migration");
        if (!Files.isDirectory(migrationDir)) {
          continue;
        }
        String module = dir.getFileName().toString().replaceFirst("-module$", "");
        try (Stream<Path> files = Files.list(migrationDir)) {
          for (Path file : files.filter(f -> f.toString().endsWith(".sql")).sorted().toList()) {
            migrations.add(
                new Migration(module, file.getFileName().toString(), Files.readString(file)));
          }
        }
      }
    }
    return migrations;
  }

  /** La raíz del repositorio: el primer directorio ascendente que contiene `auth-module`. */
  private static Path repositoryRoot() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null && !Files.isDirectory(current.resolve("auth-module"))) {
      current = current.getParent();
    }
    assertThat(current).as("raíz del repositorio").isNotNull();
    return current;
  }
}
