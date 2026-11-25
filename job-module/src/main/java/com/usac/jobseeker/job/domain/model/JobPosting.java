package com.usac.jobseeker.job.domain.model;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.InvalidStateException;
import com.usac.jobseeker.common.domain.error.InvalidTransitionException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.model.AccountId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * La vacante: raíz del agregado (los requisitos son entidades hijas que no se tocan por separado).
 * Es inmutable: cada cambio produce la versión siguiente, y la versión es la del bloqueo optimista
 * y del *compare-and-set* de toda transición (TDD de job §5, regla 1). La primera versión guardada
 * es 1.
 *
 * <p>El dueño sale del token y nunca cambia: una vacante ajena no existe para quien no es su dueño
 * (BR-JOB-01, BR-VIS-03). `acceptingApplications` y `recruitingStage` son ejes independientes del
 * estado y solo tienen efecto con la vacante publicada (BR-JOB-10, BR-JOB-04).
 */
public record JobPosting(
    JobPostingId id,
    AccountId recruiterAccountId,
    JobDetails details,
    JobStatus status,
    boolean acceptingApplications,
    RecruitingStage recruitingStage,
    long version,
    Instant createdAt,
    Instant updatedAt,
    Instant publishedAt,
    Instant closedAt,
    Instant archivedAt,
    Instant deletedAt) {

  public static final long FIRST_VERSION = 1;

  public JobPosting {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(recruiterAccountId, "recruiterAccountId");
    Objects.requireNonNull(details, "details");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(recruitingStage, "recruitingStage");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (version < FIRST_VERSION) {
      throw new IllegalArgumentException("la versión de una vacante guardada empieza en 1");
    }
  }

  /** Una vacante nueva: siempre nace en borrador, aceptando postulaciones y contratando. */
  public static JobPosting create(
      JobPostingId id, AccountId owner, JobDetails details, Instant now) {
    return new JobPosting(
        id,
        owner,
        details,
        JobStatus.DRAFT,
        true,
        RecruitingStage.HIRING,
        FIRST_VERSION,
        now,
        now,
        null,
        null,
        null,
        null);
  }

  /** Los datos nuevos. Solo se editan en borrador, publicada o cerrada (TDD de job §4.1). */
  public JobPosting edit(JobDetails newDetails, Instant now) {
    requireEditable();
    return next(
        newDetails,
        status,
        acceptingApplications,
        recruitingStage,
        now,
        publishedAt,
        closedAt,
        archivedAt,
        deletedAt);
  }

  /**
   * La versión siguiente sin cambiar los datos: la usa quien reemplaza los requisitos, que son
   * parte del agregado pero se guardan aparte.
   */
  public JobPosting touched(Instant now) {
    requireEditable();
    return next(
        details,
        status,
        acceptingApplications,
        recruitingStage,
        now,
        publishedAt,
        closedAt,
        archivedAt,
        deletedAt);
  }

  /**
   * Ejecuta una transición de la máquina de estados (§3.1). Publicar exige al menos una habilidad o
   * formación (BR-JOB-02); reabrir limpia el cierre y con él el conteo del archivado (BR-JOB-07).
   *
   * @throws InvalidTransitionException si el par no existe o `by` no lo puede disparar
   * @throws ValidationFailedException si se publica sin un requisito estructurado
   */
  public JobPosting transition(
      JobStatus to, Initiator by, boolean hasStructuredRequirement, Instant now) {
    if (!status.canMoveTo(to, by)) {
      throw new InvalidTransitionException();
    }
    if (to == JobStatus.PUBLISHED && !hasStructuredRequirement) {
      throw new ValidationFailedException(
          List.of(new FieldViolation("requirements", "REQUIRED_TO_PUBLISH")));
    }
    Instant published = to == JobStatus.PUBLISHED && publishedAt == null ? now : publishedAt;
    Instant closed =
        switch (to) {
          case CLOSED -> now;
          case PUBLISHED -> null;
          default -> closedAt;
        };
    Instant archived = to == JobStatus.ARCHIVED ? now : archivedAt;
    Instant deleted = to == JobStatus.DELETED ? now : deletedAt;
    return next(
        details,
        to,
        acceptingApplications,
        recruitingStage,
        now,
        published,
        closed,
        archived,
        deleted);
  }

  /** Pausa o reanuda la recepción de postulaciones; solo con la vacante publicada (BR-JOB-10). */
  public JobPosting withAcceptingApplications(boolean accepting, Instant now) {
    requirePublished();
    return next(
        details,
        status,
        accepting,
        recruitingStage,
        now,
        publishedAt,
        closedAt,
        archivedAt,
        deletedAt);
  }

  /** Cambia la etapa de reclutamiento; solo con la vacante publicada (BR-JOB-04). */
  public JobPosting withRecruitingStage(RecruitingStage stage, Instant now) {
    requirePublished();
    return next(
        details,
        status,
        acceptingApplications,
        Objects.requireNonNull(stage, "stage"),
        now,
        publishedAt,
        closedAt,
        archivedAt,
        deletedAt);
  }

  public boolean isOwnedBy(AccountId account) {
    return recruiterAccountId.equals(account);
  }

  private void requireEditable() {
    if (!status.isEditable()) {
      throw new InvalidStateException();
    }
  }

  private void requirePublished() {
    if (status != JobStatus.PUBLISHED) {
      throw new InvalidStateException();
    }
  }

  private JobPosting next(
      JobDetails newDetails,
      JobStatus newStatus,
      boolean accepting,
      RecruitingStage stage,
      Instant now,
      Instant published,
      Instant closed,
      Instant archived,
      Instant deleted) {
    return new JobPosting(
        id,
        recruiterAccountId,
        newDetails,
        newStatus,
        accepting,
        stage,
        version + 1,
        createdAt,
        now,
        published,
        closed,
        archived,
        deleted);
  }
}
