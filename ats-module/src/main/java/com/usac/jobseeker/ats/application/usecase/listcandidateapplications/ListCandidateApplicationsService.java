package com.usac.jobseeker.ats.application.usecase.listcandidateapplications;

import com.usac.jobseeker.ats.application.support.ApplicationStorePort;
import com.usac.jobseeker.ats.domain.model.Application;
import com.usac.jobseeker.ats.domain.model.ApplicationId;
import com.usac.jobseeker.ats.domain.model.StatusChange;
import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). Dos consultas, sea cual sea
 * el número de postulaciones: las del candidato y el historial de todas juntas (coding-standards
 * §8.1). En una transacción de solo lectura, para que postulación e historial coincidan.
 */
public class ListCandidateApplicationsService implements ListCandidateApplicationsUseCase {

  private static final String USE_CASE = "list_candidate_applications";

  private final ApplicationStorePort store;
  private final UseCaseMetricsPort metrics;

  public ListCandidateApplicationsService(ApplicationStorePort store, UseCaseMetricsPort metrics) {
    this.store = Objects.requireNonNull(store, "store");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  @Override
  @Transactional(readOnly = true)
  public List<CandidateApplication> execute(ListCandidateApplicationsQuery query) {
    List<Application> applications = store.findByCandidate(query.candidate());
    Map<ApplicationId, List<StatusChange>> history =
        store.historyOf(applications.stream().map(Application::id).toList());
    metrics.count(USE_CASE, applications.isEmpty() ? "empty" : "found");
    return applications.stream()
        .map(a -> CandidateApplication.of(a, history.getOrDefault(a.id(), List.of())))
        .toList();
  }
}
