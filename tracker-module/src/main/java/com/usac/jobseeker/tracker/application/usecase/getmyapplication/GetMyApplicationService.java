package com.usac.jobseeker.tracker.application.usecase.getmyapplication;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.tracker.application.support.MyApplications;
import com.usac.jobseeker.tracker.application.usecase.listmyapplications.MyApplication;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). Busca entre las propias:
 * así la de otro candidato no se lee nunca, y no hace falta una consulta que autorice aparte.
 */
public class GetMyApplicationService implements GetMyApplicationUseCase {

  private static final String USE_CASE = "get_my_application";

  private final MyApplications mine;
  private final UseCaseMetricsPort metrics;

  public GetMyApplicationService(MyApplications mine, UseCaseMetricsPort metrics) {
    this.mine = Objects.requireNonNull(mine, "mine");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  @Override
  @Transactional(readOnly = true)
  public MyApplication execute(GetMyApplicationQuery query) {
    try {
      MyApplication found =
          mine.of(query.candidate()).stream()
              .filter(a -> a.applicationId().equals(query.applicationId()))
              .findFirst()
              .orElseThrow(NotFoundException::new);
      metrics.count(USE_CASE, "found");
      return found;
    } catch (NotFoundException missing) {
      metrics.count(USE_CASE, "not_found");
      throw missing;
    } catch (ForbiddenCapabilityException forbidden) {
      metrics.count(USE_CASE, "forbidden");
      throw forbidden;
    }
  }
}
