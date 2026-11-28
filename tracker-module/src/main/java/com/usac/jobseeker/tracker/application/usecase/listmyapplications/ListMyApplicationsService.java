package com.usac.jobseeker.tracker.application.usecase.listmyapplications;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException;
import com.usac.jobseeker.tracker.application.support.MyApplications;
import java.util.List;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/** Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). */
public class ListMyApplicationsService implements ListMyApplicationsUseCase {

  private static final String USE_CASE = "list_my_applications";

  private final MyApplications mine;
  private final UseCaseMetricsPort metrics;

  public ListMyApplicationsService(MyApplications mine, UseCaseMetricsPort metrics) {
    this.mine = Objects.requireNonNull(mine, "mine");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  @Override
  @Transactional(readOnly = true)
  public List<MyApplication> execute(ListMyApplicationsQuery query) {
    try {
      List<MyApplication> applications = mine.of(query.candidate());
      metrics.count(USE_CASE, applications.isEmpty() ? "empty" : "found");
      return applications;
    } catch (ForbiddenCapabilityException forbidden) {
      metrics.count(USE_CASE, "forbidden");
      throw forbidden;
    }
  }
}
