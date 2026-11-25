package com.usac.jobseeker.job.application.usecase.transitionjobposting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.job.domain.event.JobPostingEvents;
import com.usac.jobseeker.job.domain.model.JobStatus;
import org.junit.jupiter.api.Test;

/** Qué evento emite cada destino, incluidos los que el dueño no alcanza por esta puerta. */
class TransitionEventTest {

  @Test
  void eachDestinationHasItsOwnEvent() {
    assertThat(TransitionJobPostingService.eventFor(JobStatus.DRAFT, JobStatus.PUBLISHED))
        .isEqualTo(JobPostingEvents.PUBLISHED);
    assertThat(TransitionJobPostingService.eventFor(JobStatus.PUBLISHED, JobStatus.CLOSED))
        .isEqualTo(JobPostingEvents.CLOSED);
    assertThat(TransitionJobPostingService.eventFor(JobStatus.CLOSED, JobStatus.ARCHIVED))
        .isEqualTo(JobPostingEvents.ARCHIVED);
    assertThat(TransitionJobPostingService.eventFor(JobStatus.ARCHIVED, JobStatus.DELETED))
        .isEqualTo(JobPostingEvents.DELETED);
  }

  // Reabrir no es publicar: `matching` distingue una vacante nueva de una que vuelve.
  @Test
  void reopeningAClosedPostingIsNotAPublication() {
    assertThat(TransitionJobPostingService.eventFor(JobStatus.CLOSED, JobStatus.PUBLISHED))
        .isEqualTo(JobPostingEvents.REOPENED);
  }

  @Test
  void nothingEverMovesBackToDraft() {
    assertThatThrownBy(
            () -> TransitionJobPostingService.eventFor(JobStatus.PUBLISHED, JobStatus.DRAFT))
        .isInstanceOf(IllegalStateException.class);
  }
}
