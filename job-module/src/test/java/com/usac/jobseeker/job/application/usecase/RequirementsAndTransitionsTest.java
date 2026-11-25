package com.usac.jobseeker.job.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException;
import com.usac.jobseeker.common.domain.error.InvalidTransitionException;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.StaleStateException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.job.application.support.JobPostingView;
import com.usac.jobseeker.job.application.usecase.managejobrequirements.ManageJobRequirementsCommand;
import com.usac.jobseeker.job.application.usecase.managejobrequirements.ManageJobRequirementsService;
import com.usac.jobseeker.job.application.usecase.transitionjobposting.TransitionJobPostingCommand;
import com.usac.jobseeker.job.application.usecase.transitionjobposting.TransitionJobPostingService;
import com.usac.jobseeker.job.domain.event.JobPostingEvents;
import com.usac.jobseeker.job.domain.model.EducationLevel;
import com.usac.jobseeker.job.domain.model.JobRequirementDraft;
import com.usac.jobseeker.job.domain.model.JobStatus;
import com.usac.jobseeker.job.domain.model.RequirementType;
import java.util.List;
import org.junit.jupiter.api.Test;

class RequirementsAndTransitionsTest {

  private final JobFixture f = new JobFixture();
  private final ManageJobRequirementsService requirements =
      new ManageJobRequirementsService(f.store, f.change, f.clock());
  private final TransitionJobPostingService transitions =
      new TransitionJobPostingService(f.change, f.clock());

  private ManageJobRequirementsCommand setOf(JobPostingView view, JobRequirementDraft... drafts) {
    return new ManageJobRequirementsCommand(
        f.owner, view.posting().id(), view.posting().version(), List.of(drafts));
  }

  private TransitionJobPostingCommand move(JobPostingView view, JobStatus to) {
    return new TransitionJobPostingCommand(
        f.owner, view.posting().id(), to, view.posting().version());
  }

  private static JobRequirementDraft education() {
    return new JobRequirementDraft(
        RequirementType.EDUCATION, false, null, null, EducationLevel.BACHELOR, null, null, null);
  }

  // ---- requisitos ----

  @Test
  void replacingTheSetOfADraftAdvancesTheVersionWithoutAnEvent() {
    JobPostingView draft = f.draft();

    JobPostingView result = requirements.execute(setOf(draft, JobStates.javaSkill(), education()));

    assertThat(result.requirements()).hasSize(2);
    assertThat(result.posting().version()).isEqualTo(draft.posting().version() + 1);
    assertThat(f.store.findByPosting(draft.posting().id())).hasSize(2);
    assertThat(f.events).isEmpty();
  }

  @Test
  void replacingTheSetOfAPublishedPostingEmitsTheUpdatedEvent() {
    JobPostingView published = JobStates.published(f);
    f.events.clear();

    requirements.execute(setOf(published, JobStates.javaSkill(), education()));

    assertThat(f.eventTypes()).containsExactly(JobPostingEvents.UPDATED);
  }

  @Test
  void sendingTheSameSetIsANoOp() {
    JobPostingView published = JobStates.published(f);
    f.events.clear();

    JobPostingView same = requirements.execute(setOf(published, JobStates.javaSkill()));

    assertThat(same.posting().version()).isEqualTo(published.posting().version());
    assertThat(f.events).isEmpty();
  }

  // BR-JOB-02 es condición para publicar y para seguir publicada.
  @Test
  void aPublishedPostingCannotLoseItsLastSkillOrEducation() {
    JobPostingView published = JobStates.published(f);

    var empty = setOf(published);

    assertThatThrownBy(() -> requirements.execute(empty))
        .isInstanceOf(ValidationFailedException.class);
    assertThat(f.store.findByPosting(published.posting().id())).hasSize(1);
  }

  @Test
  void aDraftMayHaveNoRequirementsAtAll() {
    JobPostingView draft = f.draft();
    JobPostingView withOne = requirements.execute(setOf(draft, JobStates.javaSkill()));

    assertThat(requirements.execute(setOf(withOne)).requirements()).isEmpty();
  }

  @Test
  void invalidRequirementsAreCountedAndNotSaved() {
    JobPostingView draft = f.draft();
    var bad = new JobRequirementDraft(RequirementType.SKILL, true, " ", 9, null, null, null, null);

    var command = setOf(draft, bad);

    assertThatThrownBy(() -> requirements.execute(command))
        .isInstanceOf(ValidationFailedException.class);
    assertThat(f.store.findByPosting(draft.posting().id())).isEmpty();
    assertThat(f.metrics).last().isEqualTo("manage_job_requirements:invalid");
  }

  @Test
  void requirementsOfSomeoneElsesPostingOrWithAStaleVersionAreRejected() {
    JobPostingView draft = f.draft();
    var foreign =
        new ManageJobRequirementsCommand(
            f.stranger, draft.posting().id(), 1, List.of(JobStates.javaSkill()));
    var stale =
        new ManageJobRequirementsCommand(
            f.owner, draft.posting().id(), 77, List.of(JobStates.javaSkill()));

    assertThatThrownBy(() -> requirements.execute(foreign)).isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> requirements.execute(stale)).isInstanceOf(StaleStateException.class);
  }

  // ---- transiciones ----

  @Test
  void publishingNeedsAStructuredRequirement() {
    JobPostingView draft = f.draft();

    var publish = move(draft, JobStatus.PUBLISHED);

    assertThatThrownBy(() -> transitions.execute(publish))
        .isInstanceOf(ValidationFailedException.class);
    assertThat(f.store.findById(draft.posting().id()).orElseThrow().status())
        .isEqualTo(JobStatus.DRAFT);
    assertThat(f.events).isEmpty();
  }

  @Test
  void theWholeManualLifecycleEmitsOneEventPerTransitionInOrder() {
    JobPostingView view = f.draft();
    view = requirements.execute(setOf(view, JobStates.javaSkill()));
    view = transitions.execute(move(view, JobStatus.PUBLISHED));
    view = transitions.execute(move(view, JobStatus.CLOSED));
    view = transitions.execute(move(view, JobStatus.PUBLISHED));
    view = transitions.execute(move(view, JobStatus.CLOSED));

    assertThat(f.eventTypes())
        .containsExactly(
            JobPostingEvents.PUBLISHED,
            JobPostingEvents.CLOSED,
            JobPostingEvents.REOPENED,
            JobPostingEvents.CLOSED);
    assertThat(f.events).extracting(e -> e.aggregateVersion()).isSorted();
    assertThat(view.posting().closedAt()).isNotNull();
  }

  @Test
  void discardingADraftEmitsTheDeletedEventAndHidesTheRow() {
    JobPostingView draft = f.draft();

    transitions.execute(move(draft, JobStatus.DELETED));

    assertThat(f.eventTypes()).containsExactly(JobPostingEvents.DELETED);
    assertThat(f.store.findById(draft.posting().id())).isEmpty();
  }

  // BR-JOB-14: archivar no se puede pedir por esta puerta.
  @Test
  void theOwnerCannotArchiveAndInvalidPairsAreRejected() {
    JobPostingView closed = JobStates.closed(f);
    JobPostingView draft = f.draft();

    var archive = move(closed, JobStatus.ARCHIVED);
    var closeADraft = move(draft, JobStatus.CLOSED);
    var backToDraft = move(draft, JobStatus.DRAFT);

    assertThatThrownBy(() -> transitions.execute(archive))
        .isInstanceOf(InvalidTransitionException.class);
    assertThatThrownBy(() -> transitions.execute(closeADraft))
        .isInstanceOf(InvalidTransitionException.class);
    assertThatThrownBy(() -> transitions.execute(backToDraft))
        .isInstanceOf(InvalidTransitionException.class);
    assertThat(f.metrics).contains("transition_job_posting:invalid_transition");
  }

  @Test
  void anArchivedPostingCanOnlyBeDeletedByItsOwner() {
    JobPostingView archived = JobStates.archived(f);
    f.events.clear();

    var reopen = move(archived, JobStatus.PUBLISHED);
    var delete = move(archived, JobStatus.DELETED);

    assertThatThrownBy(() -> transitions.execute(reopen))
        .isInstanceOf(InvalidTransitionException.class);
    transitions.execute(delete);

    assertThat(f.eventTypes()).containsExactly(JobPostingEvents.DELETED);
  }

  // Compare-and-set: dos transiciones desde la misma versión, solo una gana.
  @Test
  void twoTransitionsFromTheSameVersionHaveExactlyOneWinner() {
    JobPostingView published = JobStates.published(f);
    TransitionJobPostingCommand first = move(published, JobStatus.CLOSED);
    TransitionJobPostingCommand second = move(published, JobStatus.CLOSED);

    transitions.execute(first);

    assertThatThrownBy(() -> transitions.execute(second)).isInstanceOf(StaleStateException.class);
    assertThat(f.eventTypes().stream().filter(JobPostingEvents.CLOSED::equals).count()).isOne();
  }

  @Test
  void aNonRecruiterAndAForeignOwnerCannotTransition() {
    JobPostingView draft = f.draft();
    f.capabilities.recruiters.remove(f.owner);

    var discard = move(draft, JobStatus.DELETED);

    assertThatThrownBy(() -> transitions.execute(discard))
        .isInstanceOf(ForbiddenCapabilityException.class);

    var foreign =
        new TransitionJobPostingCommand(f.stranger, draft.posting().id(), JobStatus.DELETED, 1);
    assertThatThrownBy(() -> transitions.execute(foreign)).isInstanceOf(NotFoundException.class);
  }

  // BR-DATA-01: el evento lleva el identificador y la versión, nada del contenido.
  @Test
  void eventsCarryOnlyTheIdentifierAndTheVersion() {
    JobPostingView published = JobStates.published(f);

    assertThat(f.events.get(f.events.size() - 1).payload())
        .isEqualTo(
            "{\"jobPostingId\":\""
                + published.posting().id()
                + "\",\"version\":"
                + published.posting().version()
                + "}");
    assertThat(f.events)
        .allSatisfy(e -> assertThat(e.aggregateType()).isEqualTo(JobPostingEvents.AGGREGATE_TYPE));
  }
}
