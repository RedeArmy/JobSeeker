package com.usac.jobseeker.job.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.error.InvalidStateException;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.job.application.support.JobPostingView;
import com.usac.jobseeker.job.application.usecase.getjobposting.GetJobPostingQuery;
import com.usac.jobseeker.job.application.usecase.getjobposting.GetJobPostingService;
import com.usac.jobseeker.job.application.usecase.getjobsignals.GetJobSignalsQuery;
import com.usac.jobseeker.job.application.usecase.getjobsignals.GetJobSignalsService;
import com.usac.jobseeker.job.application.usecase.getjobsignals.JobSnapshot;
import com.usac.jobseeker.job.application.usecase.setacceptingapplications.SetAcceptingApplicationsCommand;
import com.usac.jobseeker.job.application.usecase.setacceptingapplications.SetAcceptingApplicationsService;
import com.usac.jobseeker.job.application.usecase.setrecruitingstage.SetRecruitingStageCommand;
import com.usac.jobseeker.job.application.usecase.setrecruitingstage.SetRecruitingStageService;
import com.usac.jobseeker.job.domain.event.JobPostingEvents;
import com.usac.jobseeker.job.domain.model.JobPostingId;
import com.usac.jobseeker.job.domain.model.RecruitingStage;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FlagsAndReadsTest {

  private final JobFixture f = new JobFixture();
  private final SetAcceptingApplicationsService accepting =
      new SetAcceptingApplicationsService(f.change, f.clock());
  private final SetRecruitingStageService stage =
      new SetRecruitingStageService(f.change, f.clock());
  private final GetJobPostingService get =
      new GetJobPostingService(
          f.store, f.store, f.capabilities, (u, o) -> f.metrics.add(u + ":" + o));
  private final GetJobSignalsService signals =
      new GetJobSignalsService(f.store, f.store, (u, o) -> f.metrics.add(u + ":" + o));
  private final AccountId candidate = new AccountId(UUID.randomUUID());

  private SetAcceptingApplicationsCommand pause(JobPostingView v, boolean value) {
    return new SetAcceptingApplicationsCommand(
        f.owner, v.posting().id(), value, v.posting().version());
  }

  // ---- ejes independientes del estado ----

  @Test
  void pausingAndResumingApplicationsChangesTheFlagWithoutAnEvent() {
    JobPostingView published = JobStates.published(f);
    f.events.clear();

    JobPostingView paused = accepting.execute(pause(published, false));
    JobPostingView resumed = accepting.execute(pause(paused, true));

    assertThat(paused.posting().acceptingApplications()).isFalse();
    assertThat(resumed.posting().acceptingApplications()).isTrue();
    assertThat(f.events).isEmpty();
  }

  @Test
  void pausingWhatIsAlreadyPausedIsANoOpAndNeedsAPublishedPosting() {
    JobPostingView published = JobStates.published(f);
    JobPostingView same = accepting.execute(pause(published, true));

    assertThat(same.posting().version()).isEqualTo(published.posting().version());
    assertThatThrownBy(() -> accepting.execute(pause(f.draft(), false)))
        .isInstanceOf(InvalidStateException.class);
  }

  // BR-MATCH-08: REVIEWING saca la vacante de los sugeridos, y matching debe enterarse.
  @Test
  void changingTheStageEmitsAnEventAndRepeatingItDoesNot() {
    JobPostingView published = JobStates.published(f);
    f.events.clear();
    var toReviewing =
        new SetRecruitingStageCommand(
            f.owner,
            published.posting().id(),
            RecruitingStage.REVIEWING,
            published.posting().version());

    JobPostingView reviewing = stage.execute(toReviewing);
    JobPostingView again =
        stage.execute(
            new SetRecruitingStageCommand(
                f.owner,
                reviewing.posting().id(),
                RecruitingStage.REVIEWING,
                reviewing.posting().version()));

    assertThat(f.eventTypes()).containsExactly(JobPostingEvents.RECRUITING_STAGE_CHANGED);
    assertThat(again.posting().version()).isEqualTo(reviewing.posting().version());
  }

  // ---- lectura ----

  @Test
  void theOwnerSeesItsPostingInAnyStateExceptDeleted() {
    JobPostingView draft = f.draft();

    JobPostingView view = get.execute(new GetJobPostingQuery(f.owner, draft.posting().id()));

    assertThat(view.ownedByViewer()).isTrue();
    assertThat(view.posting().status().name()).isEqualTo("DRAFT");
  }

  // BR-JOB-11 y BR-VIS-03.
  @Test
  void aCandidateSeesOnlyPublishedAndClosedPostingsAndNeverTheOwnersDrafts() {
    f.capabilities.candidates.add(candidate);
    JobPostingView draft = f.draft();
    JobPostingView closed = JobStates.closed(f);

    assertThatThrownBy(() -> get.execute(new GetJobPostingQuery(candidate, draft.posting().id())))
        .isInstanceOf(NotFoundException.class);
    JobPostingView seen = get.execute(new GetJobPostingQuery(candidate, closed.posting().id()));
    assertThat(seen.ownedByViewer()).isFalse();
    assertThat(seen.requirements()).hasSize(1);
  }

  // No se confirma que exista: sin ser candidato ni dueño, es lo mismo que un identificador falso.
  @Test
  void anAccountThatIsNeitherOwnerNorCandidateGetsTheSameAnswerAsAMissingPosting() {
    JobPostingView published = JobStates.published(f);

    assertThatThrownBy(
            () -> get.execute(new GetJobPostingQuery(f.stranger, published.posting().id())))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(
            () -> get.execute(new GetJobPostingQuery(f.owner, new JobPostingId(UUID.randomUUID()))))
        .isInstanceOf(NotFoundException.class);
  }

  // ---- lectura para matching ----

  @Test
  void signalsExposeContentAndRequirementsButNeverTheRecruiter() {
    JobPostingView published = JobStates.published(f);

    JobSnapshot snapshot =
        signals.execute(new GetJobSignalsQuery(published.posting().id())).orElseThrow();

    assertThat(snapshot.jobPostingId()).isEqualTo(published.posting().id().value());
    assertThat(snapshot.version()).isEqualTo(published.posting().version());
    assertThat(snapshot.status()).isEqualTo("PUBLISHED");
    assertThat(snapshot.recruitingStage()).isEqualTo("HIRING");
    assertThat(snapshot.title()).isEqualTo("Backend");
    assertThat(snapshot.requirements())
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.type()).isEqualTo("SKILL");
              assertThat(r.skillName()).isEqualTo("Java");
              assertThat(r.minLevel()).isEqualTo(3);
              assertThat(r.mandatory()).isTrue();
            });
    assertThat(snapshot.toString()).doesNotContain(f.owner.toString());
  }

  // TDD §5, regla 6: un borrador no es candidato a puntuarse; una eliminada ya no existe.
  @Test
  void signalsAreUnavailableForADraftAnUnknownOrDeletedPosting() {
    JobPostingView draft = f.draft();
    JobPostingView deletedDraft = f.draft();
    JobStates.transition(f, deletedDraft, com.usac.jobseeker.job.domain.model.JobStatus.DELETED);

    assertThat(signals.execute(new GetJobSignalsQuery(draft.posting().id()))).isEmpty();
    assertThat(signals.execute(new GetJobSignalsQuery(deletedDraft.posting().id()))).isEmpty();
    assertThat(signals.execute(new GetJobSignalsQuery(new JobPostingId(UUID.randomUUID()))))
        .isEqualTo(Optional.empty());
  }

  @Test
  void aClosedPostingStillHasSignalsSoExistingScoresStayExplainable() {
    JobPostingView closed = JobStates.closed(f);

    assertThat(signals.execute(new GetJobSignalsQuery(closed.posting().id())))
        .get()
        .extracting(JobSnapshot::status)
        .isEqualTo("CLOSED");
  }
}
