package com.usac.jobseeker.job.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException;
import com.usac.jobseeker.common.domain.error.InvalidStateException;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.StaleStateException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.job.application.support.JobDetailsInput;
import com.usac.jobseeker.job.application.support.JobPostingView;
import com.usac.jobseeker.job.application.usecase.createjobposting.CreateJobPostingCommand;
import com.usac.jobseeker.job.application.usecase.createjobposting.CreateJobPostingService;
import com.usac.jobseeker.job.application.usecase.updatejobposting.UpdateJobPostingCommand;
import com.usac.jobseeker.job.application.usecase.updatejobposting.UpdateJobPostingService;
import com.usac.jobseeker.job.domain.event.JobPostingEvents;
import com.usac.jobseeker.job.domain.model.JobPostingId;
import com.usac.jobseeker.job.domain.model.JobStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CreateAndUpdateJobPostingTest {

  private final JobFixture f = new JobFixture();
  private final CreateJobPostingService create =
      new CreateJobPostingService(f.store, f.change, f.clock(), UUID::randomUUID);
  private final UpdateJobPostingService update = new UpdateJobPostingService(f.change, f.clock());

  private UpdateJobPostingCommand edit(JobPostingView view, JobDetailsInput input) {
    return new UpdateJobPostingCommand(
        f.owner, view.posting().id(), view.posting().version(), input);
  }

  @Test
  void creatingProducesADraftOwnedByTheCallerWithoutAnyEvent() {
    JobPostingView view =
        create.execute(new CreateJobPostingCommand(f.owner, JobFixture.input("Backend")));

    assertThat(view.posting().status()).isEqualTo(JobStatus.DRAFT);
    assertThat(view.posting().isOwnedBy(f.owner)).isTrue();
    assertThat(view.requirements()).isEmpty();
    assertThat(f.store.findById(view.posting().id())).isPresent();
    assertThat(f.events).isEmpty();
    assertThat(f.metrics).containsExactly("create_job_posting:created");
  }

  // BR-JOB-01 y BR-ACCT-04: sin la capacidad de reclutador no se crea nada, ni siquiera se valida.
  @Test
  void anAccountWithoutTheRecruiterCapabilityIsForbiddenBeforeAnythingIsValidated() {
    f.capabilities.recruiters.remove(f.owner);
    var invalid = new JobDetailsInput("", "", null, null, null, null, null, null);

    assertThatThrownBy(() -> create.execute(new CreateJobPostingCommand(f.owner, invalid)))
        .isInstanceOf(ForbiddenCapabilityException.class);
    assertThat(f.store.postings).isEmpty();
    assertThat(f.metrics).containsExactly("create_job_posting:forbidden");
  }

  @Test
  void invalidDataIsCountedAndNothingIsSaved() {
    var invalid = new JobDetailsInput(" ", "d", null, null, null, null, null, null);

    assertThatThrownBy(() -> create.execute(new CreateJobPostingCommand(f.owner, invalid)))
        .isInstanceOf(ValidationFailedException.class);
    assertThat(f.store.postings).isEmpty();
    assertThat(f.metrics).containsExactly("create_job_posting:invalid");
  }

  @Test
  void aDraftEditAdvancesTheVersionButEmitsNoEvent() {
    JobPostingView draft = f.draft();
    f.advance(5);

    JobPostingView edited = update.execute(edit(draft, JobFixture.input("Otro título")));

    assertThat(edited.posting().details().title()).isEqualTo("Otro título");
    assertThat(edited.posting().version()).isEqualTo(draft.posting().version() + 1);
    assertThat(f.events).isEmpty();
  }

  // BR-MATCH-03: una vacante visible que cambia hace que su vector se recalcule.
  @Test
  void editingAPublishedPostingEmitsTheUpdatedEventWithItsNewVersion() {
    JobPostingView published = JobStates.published(f);
    f.events.clear();

    JobPostingView edited = update.execute(edit(published, JobFixture.input("Nuevo")));

    assertThat(f.eventTypes()).containsExactly(JobPostingEvents.UPDATED);
    assertThat(f.events.get(0).aggregateVersion()).isEqualTo(edited.posting().version());
    assertThat(f.events.get(0).payload()).doesNotContain("Nuevo");
  }

  // Reenviar lo mismo no es un cambio: no sube la versión ni provoca recálculos.
  @Test
  void savingTheSameDataIsANoOpWithoutVersionOrEvent() {
    JobPostingView published = JobStates.published(f);
    f.events.clear();

    JobPostingView same = update.execute(edit(published, JobFixture.input("Backend")));

    assertThat(same.posting().version()).isEqualTo(published.posting().version());
    assertThat(f.events).isEmpty();
    assertThat(f.metrics).last().isEqualTo("update_job_posting:unchanged");
  }

  @Test
  void aStaleVersionIsRejectedAndNothingChanges() {
    JobPostingView draft = f.draft();
    var stale =
        new UpdateJobPostingCommand(f.owner, draft.posting().id(), 99, JobFixture.input("X"));

    assertThatThrownBy(() -> update.execute(stale)).isInstanceOf(StaleStateException.class);
    assertThat(f.store.findById(draft.posting().id()).orElseThrow().details().title())
        .isEqualTo("Backend");
  }

  // TDD §5, regla 2: la vacante de otro no existe para quien no es su dueño.
  @Test
  void anotherRecruitersPostingDoesNotExistForYou() {
    JobPostingView draft = f.draft();
    var foreign =
        new UpdateJobPostingCommand(f.stranger, draft.posting().id(), 1, JobFixture.input("X"));

    assertThatThrownBy(() -> update.execute(foreign)).isInstanceOf(NotFoundException.class);
    assertThatThrownBy(
            () ->
                update.execute(
                    new UpdateJobPostingCommand(
                        f.owner, new JobPostingId(UUID.randomUUID()), 1, JobFixture.input("X"))))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void anArchivedPostingCannotBeEdited() {
    JobPostingView archived = JobStates.archived(f);

    assertThatThrownBy(() -> update.execute(edit(archived, JobFixture.input("Tarde"))))
        .isInstanceOf(InvalidStateException.class);
    assertThat(f.metrics).last().isEqualTo("update_job_posting:invalid_state");
  }

  @Test
  void aNegativeVersionIsAValidationErrorNotAStaleOne() {
    assertThatThrownBy(
            () ->
                new UpdateJobPostingCommand(
                    f.owner, new JobPostingId(UUID.randomUUID()), -1, JobFixture.input("X")))
        .isInstanceOf(ValidationFailedException.class);
  }
}
