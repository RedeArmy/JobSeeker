package com.usac.jobseeker.job.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.job.application.support.JobPostingView;
import com.usac.jobseeker.job.application.usecase.getpostingsummaries.GetPostingSummariesQuery;
import com.usac.jobseeker.job.application.usecase.getpostingsummaries.GetPostingSummariesService;
import com.usac.jobseeker.job.application.usecase.getpostingsummaries.PostingSummary;
import com.usac.jobseeker.job.domain.model.JobStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** El resumen para el tracker: título y estado, también de una vacante eliminada (BR-JOB-09). */
class GetPostingSummariesTest {

  private final JobFixture f = new JobFixture();
  private final GetPostingSummariesService summaries =
      new GetPostingSummariesService(f.store, (u, o) -> f.metrics.add(u + ":" + o));

  @Test
  void aDeletedPostingIsStillSummarizedWithItsStatus() {
    JobPostingView published = JobStates.published(f);
    JobPostingView draft = f.draft();
    JobStates.transition(f, draft, JobStatus.DELETED);

    List<PostingSummary> result =
        summaries.execute(
            new GetPostingSummariesQuery(
                List.of(
                    published.posting().id().value(),
                    draft.posting().id().value(),
                    UUID.randomUUID())));

    assertThat(result)
        .extracting(PostingSummary::status)
        .containsExactlyInAnyOrder("PUBLISHED", "DELETED");
    assertThat(result).extracting(PostingSummary::title).doesNotContainNull();
  }

  @Test
  void anEmptyRequestReadsNothing() {
    assertThat(summaries.execute(new GetPostingSummariesQuery(List.of()))).isEmpty();
    assertThat(f.metrics).isEmpty();
  }
}
