package com.usac.jobseeker.job.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.job.domain.model.EducationLevel;
import com.usac.jobseeker.job.domain.model.Initiator;
import com.usac.jobseeker.job.domain.model.JobDetails;
import com.usac.jobseeker.job.domain.model.JobPosting;
import com.usac.jobseeker.job.domain.model.JobPostingId;
import com.usac.jobseeker.job.domain.model.JobRequirement;
import com.usac.jobseeker.job.domain.model.JobStatus;
import com.usac.jobseeker.job.domain.model.LanguageProficiency;
import com.usac.jobseeker.job.domain.model.RecruitingStage;
import com.usac.jobseeker.job.domain.model.RequirementType;
import com.usac.jobseeker.job.domain.model.SalaryPeriod;
import com.usac.jobseeker.job.domain.model.WorkModality;
import com.usac.jobseeker.job.infrastructure.JobPostgres;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Los adaptadores JDBC de la vacante contra la tabla real que su migración crea (Postgres 17): el
 * *compare-and-set*, las lecturas que excluyen las eliminadas, el reemplazo de requisitos y las
 * restricciones que la base repite como última defensa.
 */
class JdbcJobRepositoriesTest {

  private static final Instant T0 = Instant.parse("2026-11-24T10:00:00Z");
  private static final Instant T1 = T0.plusSeconds(60);

  private final JdbcClient jdbc = JdbcClient.create(JobPostgres.dataSource());
  private final JdbcJobPostingRepository postings = new JdbcJobPostingRepository(jdbc);
  private final JdbcJobRequirementsRepository requirements =
      new JdbcJobRequirementsRepository(jdbc);
  private AccountId owner;

  @BeforeEach
  void newOwner() {
    owner = new AccountId(UUID.randomUUID());
    jdbc.sql("insert into accounts (id, is_recruiter_enabled) values (:id, true)")
        .param("id", owner.value())
        .update();
  }

  private JobPosting draft() {
    JobDetails details =
        JobDetails.of(
            "Backend",
            "Descripción",
            WorkModality.HYBRID,
            "GT",
            new BigDecimal("3000"),
            new BigDecimal("4500"),
            "USD",
            SalaryPeriod.MONTH);
    return JobPosting.create(new JobPostingId(UUID.randomUUID()), owner, details, T0);
  }

  @Test
  void aSavedPostingIsReadBackIdentical() {
    JobPosting posting = draft();

    postings.insert(posting);

    assertThat(postings.findById(posting.id())).contains(posting);
  }

  @Test
  void aPostingWithoutSalaryOrModalityRoundTripsWithNulls() {
    JobDetails bare = JobDetails.of("Solo título", "d", null, null, null, null, null, null);
    JobPosting posting = JobPosting.create(new JobPostingId(UUID.randomUUID()), owner, bare, T0);

    postings.insert(posting);

    JobPosting read = postings.findById(posting.id()).orElseThrow();
    assertThat(read.details().salary()).isNull();
    assertThat(read.details().modality()).isNull();
    assertThat(read.details().country()).isNull();
  }

  @Test
  void anUnknownIdIsEmpty() {
    assertThat(postings.findById(new JobPostingId(UUID.randomUUID()))).isEmpty();
  }

  @Test
  void aSingleEndedSalaryRoundTrips() {
    JobDetails details =
        JobDetails.of("t", "d", null, null, null, new BigDecimal("9000"), "USD", SalaryPeriod.YEAR);
    JobPosting posting = JobPosting.create(new JobPostingId(UUID.randomUUID()), owner, details, T0);

    postings.insert(posting);

    assertThat(postings.findById(posting.id()).orElseThrow().details().salary().min()).isNull();
  }

  // Compare-and-set: el segundo que llega con la misma versión esperada pierde.
  @Test
  void theUpdateOnlyWinsFromTheExpectedVersion() {
    JobPosting posting = draft();
    postings.insert(posting);
    JobPosting first = posting.touched(T1);
    JobPosting second =
        posting.edit(JobDetails.of("Otro", "d", null, null, null, null, null, null), T1);

    assertThat(postings.updateIfVersion(first, posting.version())).isTrue();
    assertThat(postings.updateIfVersion(second, posting.version())).isFalse();
    assertThat(postings.findById(posting.id()).orElseThrow().details().title())
        .isEqualTo("Backend");
    assertThat(postings.findById(posting.id()).orElseThrow().version()).isEqualTo(2);
  }

  @Test
  void aTransitionIsWrittenWithItsTimestamps() {
    JobPosting posting = draft();
    postings.insert(posting);
    JobPosting published = posting.transition(JobStatus.PUBLISHED, Initiator.OWNER, true, T1);
    postings.updateIfVersion(published, posting.version());

    JobPosting closed =
        published.transition(JobStatus.CLOSED, Initiator.OWNER, true, T1.plusSeconds(1));
    postings.updateIfVersion(closed, published.version());

    JobPosting read = postings.findById(posting.id()).orElseThrow();
    assertThat(read.status()).isEqualTo(JobStatus.CLOSED);
    assertThat(read.publishedAt()).isEqualTo(T1);
    assertThat(read.closedAt()).isEqualTo(T1.plusSeconds(1));
    assertThat(read.recruitingStage()).isEqualTo(RecruitingStage.HIRING);
  }

  // BR-JOB-09: la fila se conserva, pero para las lecturas ya no existe.
  @Test
  void aDeletedPostingKeepsItsRowButIsNoLongerFound() {
    JobPosting posting = draft();
    postings.insert(posting);
    JobPosting deleted = posting.transition(JobStatus.DELETED, Initiator.OWNER, false, T1);
    postings.updateIfVersion(deleted, posting.version());

    assertThat(postings.findById(posting.id())).isEmpty();
    assertThat(
            jdbc.sql("select count(*) from job_postings where id = :id")
                .param("id", posting.id().value())
                .query(Long.class)
                .single())
        .isOne();
  }

  @Test
  void theOwnerAndCreationDateAreNeverRewrittenByAnUpdate() {
    JobPosting posting = draft();
    postings.insert(posting);

    postings.updateIfVersion(posting.touched(T1), posting.version());

    JobPosting read = postings.findById(posting.id()).orElseThrow();
    assertThat(read.recruiterAccountId()).isEqualTo(owner);
    assertThat(read.createdAt()).isEqualTo(T0);
    assertThat(read.updatedAt()).isEqualTo(T1);
  }

  // ---- requisitos ----

  private static List<JobRequirement> sample() {
    return List.of(
        new JobRequirement(RequirementType.SKILL, true, "Java", 3, null, null, null, null),
        new JobRequirement(
            RequirementType.EDUCATION,
            false,
            null,
            null,
            EducationLevel.BACHELOR,
            null,
            null,
            null),
        new JobRequirement(RequirementType.EXPERIENCE_YEARS, true, null, null, null, 5, null, null),
        new JobRequirement(
            RequirementType.LANGUAGE, false, null, null, null, null, "en", LanguageProficiency.B2));
  }

  @Test
  void requirementsAreReadBackInTheOrderTheyWereWritten() {
    JobPosting posting = draft();
    postings.insert(posting);

    requirements.replace(posting.id(), sample());

    assertThat(requirements.findByPosting(posting.id())).containsExactlyElementsOf(sample());
  }

  @Test
  void replacingTheSetSubstitutesItCompletely() {
    JobPosting posting = draft();
    postings.insert(posting);
    requirements.replace(posting.id(), sample());

    requirements.replace(posting.id(), sample().subList(0, 1));
    assertThat(requirements.findByPosting(posting.id())).hasSize(1);

    requirements.replace(posting.id(), List.of());
    assertThat(requirements.findByPosting(posting.id())).isEmpty();
  }

  @Test
  void aSkillWithoutALevelKeepsItNull() {
    JobPosting posting = draft();
    postings.insert(posting);
    JobRequirement noLevel =
        new JobRequirement(RequirementType.SKILL, false, "SQL", null, null, null, null, null);

    requirements.replace(posting.id(), List.of(noLevel));

    assertThat(requirements.findByPosting(posting.id())).containsExactly(noLevel);
  }

  @Test
  void eachPostingKeepsItsOwnRequirements() {
    JobPosting a = draft();
    JobPosting b = draft();
    postings.insert(a);
    postings.insert(b);

    requirements.replace(a.id(), sample());

    assertThat(requirements.findByPosting(b.id())).isEmpty();
  }

  // ---- la base como última defensa ----

  private void rawInsert(String sql) {
    jdbc.sql(sql).param("owner", owner.value()).param("id", UUID.randomUUID()).update();
  }

  private static final String COLUMNS =
      "insert into job_postings (id, recruiter_account_id, title, description, %s) values"
          + " (:id, :owner, 'T', 'D', %s)";

  @Test
  void theDatabaseRejectsWhatTheDomainWouldAlreadyRefuse() {
    assertThatThrownBy(() -> rawInsert(COLUMNS.formatted("status", "'ARCHIVEDX'")))
        .isInstanceOf(DataIntegrityViolationException.class);
    // Moneda sin importe ni periodo (BR-JOB-21).
    assertThatThrownBy(() -> rawInsert(COLUMNS.formatted("salary_currency", "'USD'")))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> rawInsert(COLUMNS.formatted("country", "'gt'")))
        .isInstanceOf(DataIntegrityViolationException.class);
    // Publicada sin fecha de publicación: el estado y sus marcas deben ser coherentes.
    assertThatThrownBy(() -> rawInsert(COLUMNS.formatted("status", "'PUBLISHED'")))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> rawInsert(COLUMNS.formatted("status", "'DELETED'")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void aRequirementCannotCarryColumnsOfAnotherType() {
    JobPosting posting = draft();
    postings.insert(posting);
    String sql =
        "insert into job_requirements (id, job_posting_id, requirement_type, position, %s)"
            + " values (:id, :posting, %s, 0, %s)";

    assertThatThrownBy(
            () ->
                jdbc.sql(sql.formatted("education_level", "'SKILL'", "'BACHELOR'"))
                    .param("id", UUID.randomUUID())
                    .param("posting", posting.id().value())
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.sql(sql.formatted("min_years", "'EXPERIENCE_YEARS'", "0"))
                    .param("id", UUID.randomUUID())
                    .param("posting", posting.id().value())
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void twoRequirementsCannotShareAPosition() {
    JobPosting posting = draft();
    postings.insert(posting);
    String sql =
        "insert into job_requirements (id, job_posting_id, requirement_type, position, skill_name)"
            + " values (:id, :posting, 'SKILL', 0, 'x')";
    jdbc.sql(sql).param("id", UUID.randomUUID()).param("posting", posting.id().value()).update();

    assertThatThrownBy(
            () ->
                jdbc.sql(sql)
                    .param("id", UUID.randomUUID())
                    .param("posting", posting.id().value())
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void aPostingNeedsARealOwnerAccount() {
    JobPosting posting =
        JobPosting.create(
            new JobPostingId(UUID.randomUUID()),
            new AccountId(UUID.randomUUID()),
            JobDetails.of("t", "d", null, null, null, null, null, null),
            T0);

    assertThatThrownBy(() -> postings.insert(posting))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
