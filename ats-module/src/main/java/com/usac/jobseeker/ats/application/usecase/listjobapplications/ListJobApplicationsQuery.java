package com.usac.jobseeker.ats.application.usecase.listjobapplications;

import com.usac.jobseeker.ats.domain.model.ApplicationStatus;
import com.usac.jobseeker.ats.domain.model.PipelineStage;
import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * El pipeline de una vacante propia, filtrado, ordenado y paginado (tdd/ats-tracker-modules.md
 * §4.1). El reclutador sale del token.
 *
 * @param status filtro opcional por estado
 * @param stage filtro opcional por columna
 * @param minScore filtro opcional de 0 a 100; deja fuera a los de puntaje pendiente
 * @param page desde 0
 * @param size de 1 a {@link #MAX_SIZE}
 */
public record ListJobApplicationsQuery(
    AccountId recruiter,
    UUID jobPostingId,
    ApplicationStatus status,
    PipelineStage stage,
    Double minScore,
    Sort sort,
    int page,
    int size) {

  public static final int MAX_SIZE = 100;

  /** Por puntaje (mayor primero; los pendientes al final) o por fecha (más reciente primero). */
  public enum Sort {
    SCORE,
    DATE
  }

  public ListJobApplicationsQuery {
    Objects.requireNonNull(recruiter, "recruiter");
    Objects.requireNonNull(jobPostingId, "jobPostingId");
    Objects.requireNonNull(sort, "sort");
    List<FieldViolation> violations = new ArrayList<>();
    if (minScore != null && (minScore.isNaN() || minScore < 0 || minScore > 100)) {
      violations.add(new FieldViolation("minScore", "OUT_OF_RANGE"));
    }
    if (page < 0) {
      violations.add(new FieldViolation("page", "OUT_OF_RANGE"));
    }
    if (size < 1 || size > MAX_SIZE) {
      violations.add(new FieldViolation("size", "OUT_OF_RANGE"));
    }
    if (!violations.isEmpty()) {
      throw new ValidationFailedException(violations);
    }
  }
}
