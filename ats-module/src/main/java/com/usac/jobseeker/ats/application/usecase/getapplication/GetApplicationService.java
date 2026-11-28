package com.usac.jobseeker.ats.application.usecase.getapplication;

import com.usac.jobseeker.ats.application.support.ActorCapabilityPort;
import com.usac.jobseeker.ats.application.support.ApplicantProfilesPort;
import com.usac.jobseeker.ats.application.support.ApplicantScoresPort;
import com.usac.jobseeker.ats.application.support.ApplicationStorePort;
import com.usac.jobseeker.ats.application.support.OwnedJobPort;
import com.usac.jobseeker.ats.domain.model.Application;
import com.usac.jobseeker.ats.domain.model.ApplicationId;
import com.usac.jobseeker.ats.domain.model.StatusChange;
import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import java.util.List;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). Se autoriza por la vacante
 * de la postulación **antes** de pedir nada a `cv`: el contacto de un candidato nunca sale para
 * quien no es el dueño (REQ-C-07, regla 1).
 */
public class GetApplicationService implements GetApplicationUseCase {

  private static final String USE_CASE = "get_application";

  private final ActorCapabilityPort capabilities;
  private final OwnedJobPort jobs;
  private final ApplicationStorePort store;
  private final ApplicantScoresPort scores;
  private final ApplicantProfilesPort profiles;
  private final UseCaseMetricsPort metrics;

  public GetApplicationService(
      ActorCapabilityPort capabilities,
      OwnedJobPort jobs,
      ApplicationStorePort store,
      ApplicantScoresPort scores,
      ApplicantProfilesPort profiles,
      UseCaseMetricsPort metrics) {
    this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    this.jobs = Objects.requireNonNull(jobs, "jobs");
    this.store = Objects.requireNonNull(store, "store");
    this.scores = Objects.requireNonNull(scores, "scores");
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  @Override
  @Transactional(readOnly = true)
  public ApplicationDetail execute(GetApplicationQuery query) {
    if (!capabilities.isRecruiter(query.recruiter())) {
      throw failed("forbidden", new ForbiddenCapabilityException());
    }
    ApplicationId id = new ApplicationId(query.applicationId());
    Application application =
        store
            .findById(id)
            .filter(a -> jobs.owns(query.recruiter(), a.jobPostingId()))
            .orElseThrow(() -> failed("not_found", new NotFoundException()));
    ApplicantScoresPort.Score score =
        scores
            .of(application.jobPostingId(), List.of(application.candidateProfileId()))
            .getOrDefault(
                application.candidateProfileId(), ApplicantScoresPort.Score.notCalculatedYet());
    ApplicantProfilesPort.Profile candidate =
        profiles
            .of(List.of(application.candidateAccountId()))
            .get(application.candidateAccountId());
    List<StatusChange> history = store.historyOf(List.of(id)).getOrDefault(id, List.of());
    metrics.count(USE_CASE, "found");
    return new ApplicationDetail(
        application.id().value(),
        application.jobPostingId(),
        candidate,
        application.status().name(),
        application.stage().name(),
        name(application.rejectionReason()),
        application.version(),
        application.appliedAt(),
        application.updatedAt(),
        score,
        history.stream().map(GetApplicationService::changeOf).toList());
  }

  private static ApplicationDetail.Change changeOf(StatusChange change) {
    return new ApplicationDetail.Change(
        name(change.fromStatus()),
        name(change.fromStage()),
        change.toStatus().name(),
        change.toStage().name(),
        change.initiator().name(),
        name(change.rejectionReason()),
        change.changedAt());
  }

  private static String name(Enum<?> value) {
    return value == null ? null : value.name();
  }

  private <E extends RuntimeException> E failed(String outcome, E error) {
    metrics.count(USE_CASE, outcome);
    return error;
  }
}
