package com.usac.jobseeker.ats.application.usecase.listjobapplications;

import com.usac.jobseeker.ats.application.support.ActorCapabilityPort;
import com.usac.jobseeker.ats.application.support.ApplicantProfilesPort;
import com.usac.jobseeker.ats.application.support.ApplicantScoresPort;
import com.usac.jobseeker.ats.application.support.ApplicationStorePort;
import com.usac.jobseeker.ats.application.support.OwnedJobPort;
import com.usac.jobseeker.ats.domain.model.Application;
import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). Regla 6 del TDD: los
 * puntajes viven en `matching`, así que el pipeline lee las postulaciones de la vacante (hasta el
 * tope), pide sus puntajes en un lote, filtra y ordena en memoria. Los datos del candidato se piden
 * a `cv` solo para la página que se devuelve: cuatro lecturas sea cual sea el tamaño (§8.1).
 */
public class ListJobApplicationsService implements ListJobApplicationsUseCase {

  private static final String USE_CASE = "list_job_applications";

  // Más reciente primero; desempata el identificador, para que la paginación sea estable.
  private static final Comparator<Row> BY_DATE =
      Comparator.comparing((Row r) -> r.application().appliedAt())
          .reversed()
          .thenComparing(r -> r.application().id().value());

  // Mayor puntaje primero y los pendientes al final; a igual puntaje, quien postuló antes.
  private static final Comparator<Row> BY_SCORE =
      Comparator.comparing(
              (Row r) -> r.score().overall(), Comparator.nullsLast(Comparator.reverseOrder()))
          .thenComparing(r -> r.application().appliedAt())
          .thenComparing(r -> r.application().id().value());

  private final ActorCapabilityPort capabilities;
  private final OwnedJobPort jobs;
  private final ApplicationStorePort store;
  private final ApplicantScoresPort scores;
  private final ApplicantProfilesPort profiles;
  private final UseCaseMetricsPort metrics;
  private final int cap;

  /**
   * @param cap cuántas postulaciones se ordenan como mucho (regla 6; disparador de D4)
   */
  public ListJobApplicationsService(
      ActorCapabilityPort capabilities,
      OwnedJobPort jobs,
      ApplicationStorePort store,
      ApplicantScoresPort scores,
      ApplicantProfilesPort profiles,
      UseCaseMetricsPort metrics,
      int cap) {
    this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    this.jobs = Objects.requireNonNull(jobs, "jobs");
    this.store = Objects.requireNonNull(store, "store");
    this.scores = Objects.requireNonNull(scores, "scores");
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    if (cap <= 0) {
      throw new IllegalArgumentException("el tope del pipeline es positivo");
    }
    this.cap = cap;
  }

  private record Row(Application application, ApplicantScoresPort.Score score) {}

  @Override
  @Transactional(readOnly = true)
  public PipelinePage execute(ListJobApplicationsQuery query) {
    if (!capabilities.isRecruiter(query.recruiter())) {
      throw failed("forbidden", new ForbiddenCapabilityException());
    }
    if (!jobs.owns(query.recruiter(), query.jobPostingId())) {
      throw failed("not_found", new NotFoundException());
    }
    // Se pide una de más para saber si la vacante pasa del tope sin contarlas todas.
    List<Application> read = store.findByJob(query.jobPostingId(), cap + 1);
    boolean truncated = read.size() > cap;
    List<Application> applications =
        read.stream()
            .limit(cap)
            .filter(a -> query.status() == null || a.status() == query.status())
            .filter(a -> query.stage() == null || a.stage() == query.stage())
            .toList();
    Map<UUID, ApplicantScoresPort.Score> scoreOf =
        scores.of(
            query.jobPostingId(),
            applications.stream().map(Application::candidateProfileId).toList());
    List<Row> rows =
        applications.stream()
            .map(
                a ->
                    new Row(
                        a,
                        scoreOf.getOrDefault(
                            a.candidateProfileId(), ApplicantScoresPort.Score.notCalculatedYet())))
            .filter(r -> passes(r, query.minScore()))
            .sorted(query.sort() == ListJobApplicationsQuery.Sort.SCORE ? BY_SCORE : BY_DATE)
            .toList();
    List<Row> page =
        rows.stream().skip((long) query.page() * query.size()).limit(query.size()).toList();
    Map<AccountId, ApplicantProfilesPort.Profile> people =
        page.isEmpty()
            ? Map.of()
            : profiles.of(page.stream().map(r -> r.application().candidateAccountId()).toList());
    metrics.count(USE_CASE, truncated ? "truncated" : "listed");
    return new PipelinePage(
        page.stream().map(r -> itemOf(r, people)).toList(),
        query.page(),
        query.size(),
        rows.size(),
        truncated);
  }

  // Un puntaje mínimo deja fuera a los pendientes: no hay número con que compararlos.
  private static boolean passes(Row row, Double minScore) {
    return minScore == null || (!row.score().isPending() && row.score().overall() >= minScore);
  }

  private static PipelineItem itemOf(
      Row row, Map<AccountId, ApplicantProfilesPort.Profile> people) {
    Application a = row.application();
    return new PipelineItem(
        a.id().value(),
        people.get(a.candidateAccountId()),
        a.status().name(),
        a.stage().name(),
        a.rejectionReason() == null ? null : a.rejectionReason().name(),
        a.version(),
        a.appliedAt(),
        a.updatedAt(),
        row.score());
  }

  private <E extends RuntimeException> E failed(String outcome, E error) {
    metrics.count(USE_CASE, outcome);
    return error;
  }
}
