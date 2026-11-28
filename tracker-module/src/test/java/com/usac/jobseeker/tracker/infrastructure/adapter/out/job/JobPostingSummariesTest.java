package com.usac.jobseeker.tracker.infrastructure.adapter.out.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.job.application.usecase.getpostingsummaries.PostingSummary;
import com.usac.jobseeker.tracker.application.support.PostingSummariesPort;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Los resúmenes de `job` quedan por vacante, también el de una eliminada (BR-JOB-09). */
class JobPostingSummariesTest {

  @Test
  void eachSummaryIsKeyedByItsPostingAndADuplicateIsHarmless() {
    UUID open = UUID.randomUUID();
    UUID deleted = UUID.randomUUID();
    var adapter =
        new JobPostingSummaries(
            query -> {
              assertThat(query.jobPostingIds()).containsExactly(open, deleted);
              return List.of(
                  new PostingSummary(open, "Backend", "PUBLISHED"),
                  new PostingSummary(deleted, "Frontend", "DELETED"),
                  new PostingSummary(open, "Backend", "PUBLISHED"));
            });

    var result = adapter.of(List.of(open, deleted));

    assertThat(result)
        .containsEntry(open, new PostingSummariesPort.Summary("Backend", "PUBLISHED"))
        .containsEntry(deleted, new PostingSummariesPort.Summary("Frontend", "DELETED"))
        .hasSize(2);
  }
}
