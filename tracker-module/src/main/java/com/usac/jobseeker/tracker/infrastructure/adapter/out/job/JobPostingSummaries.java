package com.usac.jobseeker.tracker.infrastructure.adapter.out.job;

import com.usac.jobseeker.job.application.usecase.getpostingsummaries.GetPostingSummariesQuery;
import com.usac.jobseeker.job.application.usecase.getpostingsummaries.GetPostingSummariesUseCase;
import com.usac.jobseeker.job.application.usecase.getpostingsummaries.PostingSummary;
import com.usac.jobseeker.tracker.application.support.PostingSummariesPort;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Lee títulos y estados con el caso de uso público de `job` (ARCH-04), en una sola llamada. */
@Component
public class JobPostingSummaries implements PostingSummariesPort {

  private final GetPostingSummariesUseCase summaries;

  public JobPostingSummaries(GetPostingSummariesUseCase summaries) {
    this.summaries = summaries;
  }

  @Override
  public Map<UUID, Summary> of(Collection<UUID> jobPostingIds) {
    return summaries.execute(new GetPostingSummariesQuery(List.copyOf(jobPostingIds))).stream()
        .collect(
            Collectors.toMap(
                PostingSummary::jobPostingId,
                p -> new Summary(p.title(), p.status()),
                (first, repeated) -> first));
  }
}
