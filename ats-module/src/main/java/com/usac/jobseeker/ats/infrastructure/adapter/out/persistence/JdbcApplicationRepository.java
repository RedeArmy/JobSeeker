package com.usac.jobseeker.ats.infrastructure.adapter.out.persistence;

import com.usac.jobseeker.ats.application.support.ApplicationStorePort;
import com.usac.jobseeker.ats.domain.model.Application;
import com.usac.jobseeker.ats.domain.model.ApplicationId;
import com.usac.jobseeker.ats.domain.model.ApplicationStatus;
import com.usac.jobseeker.ats.domain.model.PipelineStage;
import com.usac.jobseeker.ats.domain.model.RejectionReason;
import com.usac.jobseeker.ats.domain.model.StatusChange;
import com.usac.jobseeker.common.domain.model.AccountId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Adaptador JDBC de `applications` y `application_status_history` (tdd/ats-tracker-modules.md §7).
 * SQL constante y solo valores como parámetros (ADR-0032).
 */
public class JdbcApplicationRepository implements ApplicationStorePort {

  private static final String ID = "id";
  private static final String PROFILE = "profile";
  private static final String JOB = "job";
  private static final String COLUMNS =
      "id, candidate_profile_id, candidate_account_id, job_posting_id, cv_file_id, status, stage,"
          + " rejection_reason, version, applied_at, updated_at";

  private final JdbcClient jdbc;

  public JdbcApplicationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // El ON CONFLICT nombra el índice parcial por sus columnas y su predicado: una postulación sin
  // retirar a la misma vacante hace que esta no se escriba, también si la otra aún no confirmó
  // (Postgres espera a que lo haga). Solo si se escribió se añade su historial.
  @Override
  public boolean insertIfNoneOpen(Application application, StatusChange change) {
    int inserted =
        jdbc.sql(
                "insert into applications ("
                    + COLUMNS
                    + ") values (:id, :profile, :account, :job, :cv, :status, :stage, :reason,"
                    + " :version, :appliedAt, :updatedAt)"
                    + " on conflict (candidate_profile_id, job_posting_id)"
                    + " where status <> 'WITHDRAWN' do nothing")
            .param(ID, application.id().value())
            .param(PROFILE, application.candidateProfileId())
            .param("account", application.candidateAccountId().value())
            .param(JOB, application.jobPostingId())
            .param("cv", application.cvFileId())
            .param("status", application.status().name())
            .param("stage", application.stage().name())
            .param("reason", name(application.rejectionReason()))
            .param("version", application.version())
            .param("appliedAt", utc(application.appliedAt()))
            .param("updatedAt", utc(application.updatedAt()))
            .update();
    if (inserted == 0) {
      return false;
    }
    insertChange(change);
    return true;
  }

  @Override
  public boolean hasOpenApplication(UUID candidateProfileId, UUID jobPostingId) {
    return jdbc.sql(
            "select exists (select 1 from applications where candidate_profile_id = :profile"
                + " and job_posting_id = :job and status <> 'WITHDRAWN')")
        .param(PROFILE, candidateProfileId)
        .param(JOB, jobPostingId)
        .query(Boolean.class)
        .single();
  }

  @Override
  public Optional<Application> findById(ApplicationId id) {
    return jdbc.sql("select " + COLUMNS + " from applications where id = :id")
        .param(ID, id.value())
        .query((rows, n) -> applicationOf(rows))
        .optional();
  }

  private void insertChange(StatusChange change) {
    jdbc.sql(
            "insert into application_status_history (id, application_id, from_status, to_status,"
                + " from_stage, to_stage, actor, rejection_reason, changed_at) values (:id,"
                + " :application, :fromStatus, :toStatus, :fromStage, :toStage, :actor, :reason,"
                + " :changedAt)")
        .param(ID, change.id())
        .param("application", change.applicationId().value())
        .param("fromStatus", name(change.fromStatus()))
        .param("toStatus", change.toStatus().name())
        .param("fromStage", name(change.fromStage()))
        .param("toStage", change.toStage().name())
        .param("actor", change.initiator().name())
        .param("reason", name(change.rejectionReason()))
        .param("changedAt", utc(change.changedAt()))
        .update();
  }

  private static Application applicationOf(ResultSet rows) throws SQLException {
    String reason = rows.getString("rejection_reason");
    return new Application(
        new ApplicationId(rows.getObject(ID, UUID.class)),
        rows.getObject("candidate_profile_id", UUID.class),
        new AccountId(rows.getObject("candidate_account_id", UUID.class)),
        rows.getObject("job_posting_id", UUID.class),
        rows.getObject("cv_file_id", UUID.class),
        ApplicationStatus.valueOf(rows.getString("status")),
        PipelineStage.valueOf(rows.getString("stage")),
        reason == null ? null : RejectionReason.valueOf(reason),
        rows.getLong("version"),
        instant(rows, "applied_at"),
        instant(rows, "updated_at"));
  }

  private static String name(Enum<?> value) {
    return value == null ? null : value.name();
  }

  private static Instant instant(ResultSet rows, String column) throws SQLException {
    return rows.getObject(column, OffsetDateTime.class).toInstant();
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
