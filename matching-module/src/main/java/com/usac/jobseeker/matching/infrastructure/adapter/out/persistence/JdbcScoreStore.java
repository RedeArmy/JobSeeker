package com.usac.jobseeker.matching.infrastructure.adapter.out.persistence;

import com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore.ScoreStorePort;
import com.usac.jobseeker.matching.domain.model.AlgorithmVersion;
import com.usac.jobseeker.matching.domain.model.ApplicationProfileSnapshot;
import com.usac.jobseeker.matching.domain.model.CandidateProfileId;
import com.usac.jobseeker.matching.domain.model.CompatibilityScore;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import com.usac.jobseeker.matching.domain.model.ScoreReason;
import com.usac.jobseeker.matching.domain.model.ScoreSnapshot;
import com.usac.jobseeker.matching.domain.model.StoredScore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Adaptador JDBC de `compatibility_scores_current`, `compatibility_score_snapshots` y
 * `application_profile_snapshots` (`data-model.md` §4.7). Dos decisiones:
 *
 * <ul>
 *   <li>El *upsert* de varios pares es **una sentencia** (`unnest` sobre arreglos paralelos) y no
 *       una por par: guardar los 50 sugeridos de una petición no puede costar 50 viajes a la base.
 *   <li>El motivo se conserva el **más fuerte** (`APPLIED` > `CANDIDATE_VIEW` > `SUGGESTED`), así
 *       un recálculo o un nuevo vistazo como sugerido nunca baja un par postulado.
 * </ul>
 */
public class JdbcScoreStore implements ScoreStorePort {

  private static final String PROFILE = "profile";
  private static final String JOB = "job";
  private static final String REASON_RANK = "array['SUGGESTED', 'CANDIDATE_VIEW', 'APPLIED']";

  private final JdbcClient jdbc;

  public JdbcScoreStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void upsertCurrent(Collection<StoredScore> scores) {
    // Un par repetido en la misma sentencia haría fallar el ON CONFLICT: se queda con el último.
    Map<String, StoredScore> unique = new LinkedHashMap<>();
    for (StoredScore stored : scores) {
      unique.put(stored.profile().value() + ":" + stored.job().value(), stored);
    }
    if (unique.isEmpty()) {
      return;
    }
    List<StoredScore> rows = new ArrayList<>(unique.values());
    jdbc.sql(
            "insert into compatibility_scores_current (candidate_profile_id, job_posting_id,"
                + " overall, completeness, calibrated, algorithm_version, breakdown, reason,"
                + " computed_at)"
                + " select p, j, round(cast(o as numeric), 2), round(cast(c as numeric), 3), cal, v,"
                + " cast(b as jsonb), r, cast(t as timestamptz)"
                + " from unnest(cast(:profiles as uuid[]), cast(:jobs as uuid[]),"
                + " cast(:overall as float8[]), cast(:completeness as float8[]),"
                + " cast(:calibrated as boolean[]), cast(:versions as text[]),"
                + " cast(:breakdowns as text[]), cast(:reasons as text[]), cast(:at as text[]))"
                + " as x(p, j, o, c, cal, v, b, r, t)"
                + " on conflict (candidate_profile_id, job_posting_id) do update set"
                + " overall = excluded.overall, completeness = excluded.completeness,"
                + " calibrated = excluded.calibrated, algorithm_version = excluded.algorithm_version,"
                + " breakdown = excluded.breakdown, computed_at = excluded.computed_at,"
                + " reason = ("
                + REASON_RANK
                + ")[greatest(array_position("
                + REASON_RANK
                + ", compatibility_scores_current.reason), array_position("
                + REASON_RANK
                + ", excluded.reason))]")
        .param("profiles", rows.stream().map(r -> r.profile().value()).toArray(UUID[]::new))
        .param("jobs", rows.stream().map(r -> r.job().value()).toArray(UUID[]::new))
        .param("overall", rows.stream().mapToDouble(r -> r.score().overall()).toArray())
        .param(
            "completeness",
            rows.stream().mapToDouble(r -> r.score().breakdown().completeness()).toArray())
        .param("calibrated", toBooleans(rows))
        .param(
            "versions", rows.stream().map(r -> r.score().version().value()).toArray(String[]::new))
        .param(
            "breakdowns",
            rows.stream()
                .map(r -> ScoreBreakdownJson.write(r.score().breakdown()))
                .toArray(String[]::new))
        .param("reasons", rows.stream().map(r -> r.reason().name()).toArray(String[]::new))
        .param("at", rows.stream().map(r -> r.computedAt().toString()).toArray(String[]::new))
        .update();
  }

  private static boolean[] toBooleans(List<StoredScore> rows) {
    boolean[] calibrated = new boolean[rows.size()];
    for (int i = 0; i < calibrated.length; i++) {
      calibrated[i] = rows.get(i).score().calibrated();
    }
    return calibrated;
  }

  @Override
  public Optional<StoredScore> findCurrent(CandidateProfileId profile, JobPostingId job) {
    return jdbc.sql(
            selectCurrent() + " where candidate_profile_id = :profile and job_posting_id = :job")
        .param(PROFILE, profile.value())
        .param(JOB, job.value())
        .query((rows, n) -> storedOf(rows))
        .optional();
  }

  @Override
  public List<JobPostingId> jobsOfProfile(CandidateProfileId profile, int limit) {
    return jdbc.sql(
            "select job_posting_id from compatibility_scores_current"
                + " where candidate_profile_id = :profile order by computed_at desc limit :limit")
        .param(PROFILE, profile.value())
        .param("limit", limit)
        .query((rows, n) -> new JobPostingId(rows.getObject(1, UUID.class)))
        .list();
  }

  @Override
  public List<CandidateProfileId> profilesOfJob(JobPostingId job, int limit) {
    return jdbc.sql(
            "select candidate_profile_id from compatibility_scores_current"
                + " where job_posting_id = :job order by computed_at desc limit :limit")
        .param(JOB, job.value())
        .param("limit", limit)
        .query((rows, n) -> new CandidateProfileId(rows.getObject(1, UUID.class)))
        .list();
  }

  @Override
  public List<StoredScore> staleVersion(String currentVersion, int limit) {
    return jdbc.sql(
            selectCurrent()
                + " where algorithm_version <> :version and anonymized_at is null"
                + " order by computed_at limit :limit")
        .param("version", currentVersion)
        .param("limit", limit)
        .query((rows, n) -> storedOf(rows))
        .list();
  }

  @Override
  public void deleteCurrent(CandidateProfileId profile, JobPostingId job) {
    jdbc.sql(
            "delete from compatibility_scores_current"
                + " where candidate_profile_id = :profile and job_posting_id = :job")
        .param(PROFILE, profile.value())
        .param(JOB, job.value())
        .update();
  }

  @Override
  public void touch(CandidateProfileId profile, JobPostingId job, Instant at) {
    jdbc.sql(
            "update compatibility_scores_current set computed_at = :at"
                + " where candidate_profile_id = :profile and job_posting_id = :job")
        .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
        .param(PROFILE, profile.value())
        .param(JOB, job.value())
        .update();
  }

  @Override
  public void insertSnapshot(ScoreSnapshot snapshot) {
    CompatibilityScore score = snapshot.score();
    jdbc.sql(
            "insert into compatibility_score_snapshots (id, candidate_profile_id, job_posting_id,"
                + " reason, overall, completeness, calibrated, algorithm_version, breakdown,"
                + " created_at) values (:id, :profile, :job, :reason, round(cast(:overall as"
                + " numeric), 2), round(cast(:completeness as numeric), 3), :calibrated, :version,"
                + " cast(:breakdown as jsonb), :at)")
        .param("id", snapshot.id())
        .param(PROFILE, snapshot.profile().value())
        .param(JOB, snapshot.job().value())
        .param("reason", snapshot.reason().name())
        .param("overall", score.overall())
        .param("completeness", score.breakdown().completeness())
        .param("calibrated", score.calibrated())
        .param("version", score.version().value())
        .param("breakdown", ScoreBreakdownJson.write(score.breakdown()))
        .param("at", OffsetDateTime.ofInstant(snapshot.createdAt(), ZoneOffset.UTC))
        .update();
  }

  // Idempotente por postulación: repetir la llamada no duplica la copia del perfil.
  @Override
  public void insertApplicationSnapshot(ApplicationProfileSnapshot snapshot) {
    jdbc.sql(
            "insert into application_profile_snapshots (id, candidate_profile_id, job_posting_id,"
                + " application_id, canonical_text, canonical_hash, algorithm_version, created_at)"
                + " values (:id, :profile, :job, :application, :text, :hash, :version, :at)"
                + " on conflict (application_id) where application_id is not null do nothing")
        .param("id", snapshot.id())
        .param(PROFILE, snapshot.profile().value())
        .param(JOB, snapshot.job().value())
        .param("application", snapshot.applicationId())
        .param("text", snapshot.canonicalText())
        .param("hash", snapshot.canonicalHash())
        .param("version", snapshot.algorithmVersion())
        .param("at", OffsetDateTime.ofInstant(snapshot.createdAt(), ZoneOffset.UTC))
        .update();
  }

  // BR-DATA-05: se vacía lo derivado del perfil y se conserva el metadato (par, puntaje y versión).
  @Override
  public void anonymizeProfile(CandidateProfileId profile, Instant at) {
    OffsetDateTime when = OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    for (String table : List.of("compatibility_scores_current", "compatibility_score_snapshots")) {
      jdbc.sql(
              "update "
                  + table
                  + " set breakdown = cast('{}' as jsonb), anonymized_at = :at"
                  + " where candidate_profile_id = :profile and anonymized_at is null")
          .param("at", when)
          .param(PROFILE, profile.value())
          .update();
    }
    jdbc.sql(
            "update application_profile_snapshots set canonical_text = null, canonical_hash = null,"
                + " anonymized_at = :at where candidate_profile_id = :profile"
                + " and anonymized_at is null")
        .param("at", when)
        .param(PROFILE, profile.value())
        .update();
  }

  @Override
  public int purgeSnapshotsOlderThan(Instant before) {
    return jdbc.sql("delete from compatibility_score_snapshots where created_at < :before")
        .param("before", OffsetDateTime.ofInstant(before, ZoneOffset.UTC))
        .update();
  }

  private static String selectCurrent() {
    return "select candidate_profile_id, job_posting_id, overall, completeness, calibrated,"
        + " algorithm_version, breakdown, reason, computed_at from compatibility_scores_current";
  }

  private static StoredScore storedOf(ResultSet rows) throws SQLException {
    double completeness = rows.getDouble("completeness");
    CompatibilityScore score =
        new CompatibilityScore(
            rows.getDouble("overall"),
            ScoreBreakdownJson.read(rows.getString("breakdown"), completeness),
            AlgorithmVersion.parse(rows.getString("algorithm_version")),
            rows.getBoolean("calibrated"));
    return new StoredScore(
        new CandidateProfileId(rows.getObject("candidate_profile_id", UUID.class)),
        new JobPostingId(rows.getObject("job_posting_id", UUID.class)),
        score,
        ScoreReason.valueOf(rows.getString("reason")),
        rows.getObject("computed_at", OffsetDateTime.class).toInstant());
  }
}
