package com.usac.jobseeker.tracker.application.support;

import com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.tracker.application.usecase.listmyapplications.MyApplication;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Lo que comparten la lista y el detalle del tracker: comprobar la capacidad y componer las
 * postulaciones de `ats` con las vacantes de `job`. Dos lecturas, sea cual sea el número de
 * postulaciones (coding-standards §8.1). `tracker` consulta en cada lectura y no guarda una
 * proyección propia (TDD §2): son pocas filas por candidato y no hay retraso que explicar.
 */
public final class MyApplications {

  private final CandidateCapabilityPort capabilities;
  private final CandidateApplicationsPort applications;
  private final PostingSummariesPort postings;

  public MyApplications(
      CandidateCapabilityPort capabilities,
      CandidateApplicationsPort applications,
      PostingSummariesPort postings) {
    this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    this.applications = Objects.requireNonNull(applications, "applications");
    this.postings = Objects.requireNonNull(postings, "postings");
  }

  /** Las del candidato, ya compuestas; lanza si la cuenta no puede actuar como candidato. */
  public List<MyApplication> of(AccountId candidate) {
    if (!capabilities.isCandidate(candidate)) {
      throw new ForbiddenCapabilityException();
    }
    List<CandidateApplicationsPort.Tracked> mine = applications.of(candidate);
    if (mine.isEmpty()) {
      return List.of();
    }
    Map<UUID, PostingSummariesPort.Summary> summaries =
        postings.of(mine.stream().map(CandidateApplicationsPort.Tracked::jobPostingId).toList());
    return mine.stream().map(a -> MyApplication.of(a, summaries.get(a.jobPostingId()))).toList();
  }
}
