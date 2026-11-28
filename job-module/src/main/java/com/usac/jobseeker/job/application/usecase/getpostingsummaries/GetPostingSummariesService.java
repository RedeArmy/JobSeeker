package com.usac.jobseeker.job.application.usecase.getpostingsummaries;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.job.application.support.JobPostingStorePort;
import com.usac.jobseeker.job.domain.model.JobPostingId;
import java.util.List;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/** Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). Una sola consulta. */
public class GetPostingSummariesService implements GetPostingSummariesUseCase {

  private static final String USE_CASE = "get_posting_summaries";

  private final JobPostingStorePort postings;
  private final UseCaseMetricsPort metrics;

  public GetPostingSummariesService(JobPostingStorePort postings, UseCaseMetricsPort metrics) {
    this.postings = Objects.requireNonNull(postings, "postings");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  @Override
  @Transactional(readOnly = true)
  public List<PostingSummary> execute(GetPostingSummariesQuery query) {
    if (query.jobPostingIds().isEmpty()) {
      return List.of();
    }
    List<PostingSummary> summaries =
        postings
            .findAllByIdsIncludingDeleted(
                query.jobPostingIds().stream().map(JobPostingId::new).toList())
            .stream()
            .map(p -> new PostingSummary(p.id().value(), p.details().title(), p.status().name()))
            .toList();
    metrics.count(USE_CASE, "read");
    return summaries;
  }
}
