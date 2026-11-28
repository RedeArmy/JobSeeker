package com.usac.jobseeker.job.infrastructure.adapter.out.persistence;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.job.application.support.JobPostingStorePort;
import com.usac.jobseeker.job.domain.model.JobDetails;
import com.usac.jobseeker.job.domain.model.JobPosting;
import com.usac.jobseeker.job.domain.model.JobPostingId;
import com.usac.jobseeker.job.domain.model.JobStatus;
import com.usac.jobseeker.job.domain.model.RecruitingStage;
import com.usac.jobseeker.job.domain.model.SalaryPeriod;
import com.usac.jobseeker.job.domain.model.SalaryRange;
import com.usac.jobseeker.job.domain.model.WorkModality;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;

/**
 * Adaptador JDBC de `job_postings` (`data-model.md` §4.6). Las lecturas **excluyen** las eliminadas
 * (BR-JOB-09): la fila se conserva por integridad referencial, pero para el resto del sistema ya no
 * existe. La actualización escribe la fila completa condicionada a la versión: es el
 * *compare-and-set* que hace seguras a la vez la edición, las transiciones manuales y, en la Ola 3,
 * los lotes (TDD de job §5, regla 1).
 */
public class JdbcJobPostingRepository implements JobPostingStorePort {

  private static final String ID = "id";
  private static final String COLUMNS =
      "id, recruiter_account_id, title, description, modality, country, salary_min, salary_max,"
          + " salary_currency, salary_period, status, accepting_applications, recruiting_stage,"
          + " version, created_at, updated_at, published_at, closed_at, archived_at, deleted_at";

  private final JdbcClient jdbc;

  public JdbcJobPostingRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<JobPosting> findById(JobPostingId id) {
    return jdbc.sql(
            "select " + COLUMNS + " from job_postings where id = :id and status <> 'DELETED'")
        .param(ID, id.value())
        .query((rows, n) -> postingOf(rows))
        .optional();
  }

  @Override
  public List<JobPosting> findAllByIds(Collection<JobPostingId> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            "select " + COLUMNS + " from job_postings where id = any(:ids) and status <> 'DELETED'")
        .param("ids", ids.stream().map(JobPostingId::value).toArray(UUID[]::new))
        .query((rows, n) -> postingOf(rows))
        .list();
  }

  // BR-JOB-09: la fila de una eliminada se conserva justo para esto, que el candidato la siga
  // viendo.
  @Override
  public List<JobPosting> findAllByIdsIncludingDeleted(Collection<JobPostingId> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return jdbc.sql("select " + COLUMNS + " from job_postings where id = any(:ids)")
        .param("ids", ids.stream().map(JobPostingId::value).toArray(UUID[]::new))
        .query((rows, n) -> postingOf(rows))
        .list();
  }

  // El candado de transacción por reclutador pone en fila las publicaciones de una misma cuenta:
  // sin
  // él, dos simultáneas contarían ambas «una menos que el tope» y pasarían las dos. Es por cuenta,
  // así que no frena a otros reclutadores, y se libera solo al terminar la transacción.
  @Override
  public long lockAndCountPublished(AccountId owner) {
    jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))")
        .param("key", "job_publish:" + owner)
        .query()
        .singleRow();
    return jdbc.sql(
            "select count(*) from job_postings where recruiter_account_id = :owner"
                + " and status = 'PUBLISHED'")
        .param("owner", owner.value())
        .query(Long.class)
        .single();
  }

  @Override
  public void insert(JobPosting posting) {
    bind(
            jdbc.sql(
                "insert into job_postings (id, recruiter_account_id, title, description, modality,"
                    + " country, salary_min, salary_max, salary_currency, salary_period, status,"
                    + " accepting_applications, recruiting_stage, version, created_at, updated_at,"
                    + " published_at, closed_at, archived_at, deleted_at)"
                    + " values (:id, :owner, :title, :description, :modality, :country, :salaryMin,"
                    + " :salaryMax, :currency, :period, :status, :accepting, :stage, :version,"
                    + " :createdAt, :updatedAt, :publishedAt, :closedAt, :archivedAt, :deletedAt)"),
            posting)
        .param("owner", posting.recruiterAccountId().value())
        .param("createdAt", utc(posting.createdAt()))
        .update();
  }

  // La condición sobre `version` es el bloqueo optimista: si otra edición o transición ya la
  // avanzó, no se actualiza ninguna fila y quien llama sabe que perdió la carrera. El dueño y la
  // fecha de creación no se escriben: no cambian nunca.
  @Override
  public boolean updateIfVersion(JobPosting posting, long expectedVersion) {
    return bind(
                jdbc.sql(
                    "update job_postings set title = :title, description = :description,"
                        + " modality = :modality, country = :country, salary_min = :salaryMin,"
                        + " salary_max = :salaryMax, salary_currency = :currency,"
                        + " salary_period = :period, status = :status,"
                        + " accepting_applications = :accepting, recruiting_stage = :stage,"
                        + " version = :version, updated_at = :updatedAt,"
                        + " published_at = :publishedAt, closed_at = :closedAt,"
                        + " archived_at = :archivedAt, deleted_at = :deletedAt"
                        + " where id = :id and version = :expected"),
                posting)
            .param("expected", expectedVersion)
            .update()
        == 1;
  }

  private static StatementSpec bind(StatementSpec sql, JobPosting posting) {
    JobDetails d = posting.details();
    SalaryRange salary = d.salary();
    return sql.param(ID, posting.id().value())
        .param("title", d.title())
        .param("description", d.description())
        .param("modality", d.modality() == null ? null : d.modality().name())
        .param("country", d.country())
        .param("salaryMin", salary == null ? null : salary.min())
        .param("salaryMax", salary == null ? null : salary.max())
        .param("currency", salary == null ? null : salary.currency())
        .param("period", salary == null ? null : salary.period().name())
        .param("status", posting.status().name())
        .param("accepting", posting.acceptingApplications())
        .param("stage", posting.recruitingStage().name())
        .param("version", posting.version())
        .param("updatedAt", utc(posting.updatedAt()))
        .param("publishedAt", utc(posting.publishedAt()))
        .param("closedAt", utc(posting.closedAt()))
        .param("archivedAt", utc(posting.archivedAt()))
        .param("deletedAt", utc(posting.deletedAt()));
  }

  private static JobPosting postingOf(ResultSet rows) throws SQLException {
    String modality = rows.getString("modality");
    String period = rows.getString("salary_period");
    SalaryRange salary =
        period == null
            ? null
            : new SalaryRange(
                rows.getBigDecimal("salary_min"),
                rows.getBigDecimal("salary_max"),
                rows.getString("salary_currency"),
                SalaryPeriod.valueOf(period));
    JobDetails details =
        new JobDetails(
            rows.getString("title"),
            rows.getString("description"),
            modality == null ? null : WorkModality.valueOf(modality),
            rows.getString("country"),
            salary);
    return new JobPosting(
        new JobPostingId(rows.getObject(ID, UUID.class)),
        new AccountId(rows.getObject("recruiter_account_id", UUID.class)),
        details,
        JobStatus.valueOf(rows.getString("status")),
        rows.getBoolean("accepting_applications"),
        RecruitingStage.valueOf(rows.getString("recruiting_stage")),
        rows.getLong("version"),
        instant(rows, "created_at"),
        instant(rows, "updated_at"),
        instant(rows, "published_at"),
        instant(rows, "closed_at"),
        instant(rows, "archived_at"),
        instant(rows, "deleted_at"));
  }

  private static Instant instant(ResultSet rows, String column) throws SQLException {
    OffsetDateTime value = rows.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private static OffsetDateTime utc(Instant instant) {
    return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
