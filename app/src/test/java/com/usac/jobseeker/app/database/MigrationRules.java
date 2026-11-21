package com.usac.jobseeker.app.database;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Convenciones de migraciones del ADR-0027, comprobadas sobre los archivos SQL: nombre, módulo
 * dueño, propiedad de las tablas, orden de las claves foráneas entre contextos e índices sin
 * bloqueo. Devuelve las infracciones en vez de lanzar, para poder probar que cada una se detecta.
 */
final class MigrationRules {

  /** Un archivo de migración: módulo en cuya carpeta vive, nombre y contenido. */
  record Migration(String module, String fileName, String sql) {}

  /** Dueño de cada tabla (ADR-0027, decisión 4). Si el modelo cambia, se actualiza aquí y allí. */
  static final Map<String, String> OWNER = owners();

  /**
   * Cadena de dependencias entre contextos (ADR-0027, decisión 5): una FK solo apunta hacia atrás.
   */
  static final List<String> CHAIN = List.of("auth", "cv", "job", "matching", "ats");

  private static final Pattern NAME = Pattern.compile("^V(\\d{14})__([a-z]+)_[a-z0-9_]+\\.sql$");
  private static final String EXTENSIONS = "V1__enable_extensions.sql";
  private static final Pattern CREATE_TABLE =
      Pattern.compile("create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?(?:\\w+\\.)?\"?(\\w+)\"?");
  private static final Pattern ALTER_TABLE =
      Pattern.compile(
          "alter\\s+table\\s+(?:if\\s+exists\\s+)?(?:only\\s+)?(?:\\w+\\.)?\"?(\\w+)\"?");
  private static final Pattern REFERENCES =
      Pattern.compile("references\\s+(?:\\w+\\.)?\"?(\\w+)\"?");
  private static final Pattern CREATE_INDEX =
      Pattern.compile(
          "create\\s+(?:unique\\s+)?index\\s+(concurrently\\s+)?(?:if\\s+not\\s+exists\\s+)?"
              + "\\w+\\s+on\\s+(?:only\\s+)?(?:\\w+\\.)?\"?(\\w+)\"?");

  private MigrationRules() {}

  static List<String> check(List<Migration> migrations) {
    List<String> violations = new ArrayList<>();
    Map<String, Long> versions = new HashMap<>();
    Set<Long> seenTimestamps = new HashSet<>();
    Map<String, Long> createdAt = new HashMap<>();

    // Primera pasada: nombres y versiones, y qué tabla se crea en qué versión.
    for (Migration migration : migrations) {
      long version = versionOf(migration, violations);
      versions.put(migration.fileName(), version);
      if (version > 1 && !seenTimestamps.add(version)) {
        violations.add("TIMESTAMP: versión repetida en " + migration.fileName());
      }
      String sql = clean(migration.sql());
      for (String table : matches(CREATE_TABLE, sql)) {
        createdAt.put(table, version);
      }
    }

    // Segunda pasada: propiedad, claves foráneas e índices.
    for (Migration migration : migrations) {
      String sql = clean(migration.sql());
      long version = versions.get(migration.fileName());
      Set<String> createdHere = new HashSet<>(matches(CREATE_TABLE, sql));

      for (String table : createdHere) {
        String owner = OWNER.get(table);
        if (owner == null) {
          violations.add(
              "OWNER: la tabla "
                  + table
                  + " no tiene dueño en el ADR-0027 ("
                  + migration.fileName()
                  + ")");
        } else if (!owner.equals(migration.module())) {
          violations.add(
              "OWNER: la tabla "
                  + table
                  + " es del módulo "
                  + owner
                  + " pero la crea "
                  + migration.module()
                  + " ("
                  + migration.fileName()
                  + ")");
        }
      }
      for (String table : matches(ALTER_TABLE, sql)) {
        String owner = OWNER.get(table);
        if (owner != null && !owner.equals(migration.module())) {
          violations.add(
              "OWNER: "
                  + migration.module()
                  + " modifica la tabla "
                  + table
                  + " que es de "
                  + owner
                  + " ("
                  + migration.fileName()
                  + ")");
        }
      }
      for (String target : matches(REFERENCES, sql)) {
        checkForeignKey(migration, target, version, createdAt, createdHere, violations);
      }
      Matcher index = CREATE_INDEX.matcher(sql);
      while (index.find()) {
        boolean concurrently = index.group(1) != null;
        String table = index.group(2);
        if (!concurrently && !createdHere.contains(table)) {
          violations.add(
              "INDEX: índice sobre la tabla existente "
                  + table
                  + " sin CONCURRENTLY ("
                  + migration.fileName()
                  + ")");
        }
      }
    }
    return violations;
  }

  private static void checkForeignKey(
      Migration migration,
      String target,
      long version,
      Map<String, Long> createdAt,
      Set<String> createdHere,
      List<String> violations) {
    String targetOwner = OWNER.get(target);
    String file = migration.fileName();
    if (targetOwner == null) {
      violations.add("FK-ORDER: " + file + " referencia la tabla " + target + " sin dueño");
      return;
    }
    if (migration.module().equals("app") || targetOwner.equals("app")) {
      violations.add(
          "FK-ORDER: las tablas de app no participan en claves foráneas ("
              + file
              + " -> "
              + target
              + ")");
      return;
    }
    int from = CHAIN.indexOf(migration.module());
    int to = CHAIN.indexOf(targetOwner);
    if (from < 0 || to < 0 || to > from) {
      violations.add(
          "FK-ORDER: "
              + migration.module()
              + " no puede referenciar "
              + target
              + " de "
              + targetOwner
              + " ("
              + file
              + ")");
    }
    Long created = createdAt.get(target);
    if (!createdHere.contains(target) && (created == null || created > version)) {
      violations.add("FK-ORDER: " + target + " debe crearse en una versión anterior a " + file);
    }
  }

  private static long versionOf(Migration migration, List<String> violations) {
    String name = migration.fileName();
    if (name.equals(EXTENSIONS)) {
      if (!migration.module().equals("app")) {
        violations.add("MODULE: " + name + " solo puede vivir en app");
      }
      return 1;
    }
    Matcher matcher = NAME.matcher(name);
    if (!matcher.matches()) {
      violations.add("NAME: " + name + " no sigue V<aaaaMMddHHmmss>__<modulo>_<descripcion>.sql");
      return Long.MAX_VALUE;
    }
    if (!matcher.group(2).equals(migration.module())) {
      violations.add(
          "MODULE: "
              + name
              + " lleva el prefijo "
              + matcher.group(2)
              + " pero vive en la carpeta del módulo "
              + migration.module());
    }
    return Long.parseLong(matcher.group(1));
  }

  /** Quita comentarios y pasa a minúsculas para que las expresiones no se confundan con ellos. */
  private static String clean(String sql) {
    return sql.replaceAll("(?s)/\\*.*?\\*/", " ")
        .replaceAll("--[^\\n]*", " ")
        .toLowerCase(Locale.ROOT);
  }

  private static List<String> matches(Pattern pattern, String sql) {
    List<String> found = new ArrayList<>();
    Matcher matcher = pattern.matcher(sql);
    while (matcher.find()) {
      found.add(matcher.group(matcher.groupCount()));
    }
    return found;
  }

  private static Map<String, String> owners() {
    Map<String, String> owners = new HashMap<>();
    put(owners, "auth", "accounts");
    put(
        owners,
        "cv",
        "professional_profiles",
        "experiences",
        "education_entries",
        "certifications",
        "projects",
        "candidate_profiles",
        "recruiter_profiles",
        "candidate_availability_exclusions",
        "candidate_work_authorizations",
        "candidate_languages",
        "recruiter_templates",
        "branded_cv_exports");
    put(
        owners,
        "job",
        "job_postings",
        "eligibility_rules",
        "candidate_invitations",
        "job_posting_scheduled_transitions",
        "application_questions");
    put(owners, "matching", "compatibility_scores");
    put(
        owners,
        "ats",
        "applications",
        "application_answers",
        "application_follow_up_requests",
        "application_follow_up_items");
    put(owners, "app", "outbox_events", "processed_events", "idempotency_keys", "audit_log");
    return Map.copyOf(owners);
  }

  private static void put(Map<String, String> owners, String module, String... tables) {
    for (String table : tables) {
      owners.put(table, module);
    }
  }
}
