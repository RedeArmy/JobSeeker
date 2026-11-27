package com.usac.jobseeker.job.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.error.InvalidStateException;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.job.application.support.JobDetailsInput;
import com.usac.jobseeker.job.application.support.JobPostingView;
import com.usac.jobseeker.job.application.usecase.createjobposting.CreateJobPostingCommand;
import com.usac.jobseeker.job.application.usecase.createjobposting.CreateJobPostingService;
import com.usac.jobseeker.job.application.usecase.getjobposting.GetJobPostingQuery;
import com.usac.jobseeker.job.application.usecase.getjobposting.GetJobPostingService;
import com.usac.jobseeker.job.application.usecase.getjobsignals.GetJobSignalsQuery;
import com.usac.jobseeker.job.application.usecase.getjobsignals.GetJobSignalsService;
import com.usac.jobseeker.job.application.usecase.getjobsignals.JobSnapshot;
import com.usac.jobseeker.job.application.usecase.managejobrequirements.ManageJobRequirementsCommand;
import com.usac.jobseeker.job.application.usecase.managejobrequirements.ManageJobRequirementsService;
import com.usac.jobseeker.job.application.usecase.setacceptingapplications.SetAcceptingApplicationsCommand;
import com.usac.jobseeker.job.application.usecase.setacceptingapplications.SetAcceptingApplicationsService;
import com.usac.jobseeker.job.application.usecase.setrecruitingstage.SetRecruitingStageCommand;
import com.usac.jobseeker.job.application.usecase.setrecruitingstage.SetRecruitingStageService;
import com.usac.jobseeker.job.domain.event.JobPostingEvents;
import com.usac.jobseeker.job.domain.model.EducationLevel;
import com.usac.jobseeker.job.domain.model.JobPostingId;
import com.usac.jobseeker.job.domain.model.JobRequirementDraft;
import com.usac.jobseeker.job.domain.model.JobStatus;
import com.usac.jobseeker.job.domain.model.LanguageProficiency;
import com.usac.jobseeker.job.domain.model.RecruitingStage;
import com.usac.jobseeker.job.domain.model.RequirementType;
import com.usac.jobseeker.job.domain.model.SalaryPeriod;
import com.usac.jobseeker.job.domain.model.WorkModality;
import java.math.BigDecimal;
import java.util.List;
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
    var onADraft = pause(f.draft(), false);

    assertThatThrownBy(() -> accepting.execute(onADraft)).isInstanceOf(InvalidStateException.class);
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

    var draftQuery = new GetJobPostingQuery(candidate, draft.posting().id());
    var closedQuery = new GetJobPostingQuery(candidate, closed.posting().id());

    assertThatThrownBy(() -> get.execute(draftQuery)).isInstanceOf(NotFoundException.class);
    JobPostingView seen = get.execute(closedQuery);
    assertThat(seen.ownedByViewer()).isFalse();
    assertThat(seen.requirements()).hasSize(1);
  }

  // No se confirma que exista: sin ser candidato ni dueño, es lo mismo que un identificador falso.
  @Test
  void anAccountThatIsNeitherOwnerNorCandidateGetsTheSameAnswerAsAMissingPosting() {
    JobPostingView published = JobStates.published(f);

    var byStranger = new GetJobPostingQuery(f.stranger, published.posting().id());
    var unknown = new GetJobPostingQuery(f.owner, new JobPostingId(UUID.randomUUID()));

    assertThatThrownBy(() -> get.execute(byStranger)).isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> get.execute(unknown)).isInstanceOf(NotFoundException.class);
  }

  // ---- lectura para matching ----

  @Test
  void signalsExposeContentAndRequirementsButNeverTheRecruiter() {
    JobPostingView published = JobStates.published(f);

    JobSnapshot snapshot =
        signals.execute(new GetJobSignalsQuery(published.posting().id().value())).orElseThrow();

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

    assertThat(signals.execute(new GetJobSignalsQuery(draft.posting().id().value()))).isEmpty();
    assertThat(signals.execute(new GetJobSignalsQuery(deletedDraft.posting().id().value())))
        .isEmpty();
    assertThat(signals.execute(new GetJobSignalsQuery(UUID.randomUUID()))).isEmpty();
  }

  // La lectura por lotes aplica las mismas reglas que la individual y da lo mismo, vacante por
  // vacante (coding-standards §8.1).
  @Test
  void theBatchReadGivesTheSameSnapshotsAsTheIndividualOne() {
    JobPostingView published = JobStates.published(f);
    JobPostingView closed = JobStates.closed(f);
    JobPostingView draft = f.draft();
    UUID missing = UUID.randomUUID();
    var ids =
        List.of(
            published.posting().id().value(),
            closed.posting().id().value(),
            draft.posting().id().value(),
            missing);

    var batch =
        signals.executeAll(
            com.usac.jobseeker.job.application.usecase.getjobsignals.GetJobSignalsBatchQuery.of(
                ids));

    assertThat(batch).containsOnlyKeys(ids.get(0), ids.get(1));
    for (UUID id : batch.keySet()) {
      assertThat(batch)
          .containsEntry(id, signals.execute(new GetJobSignalsQuery(id)).orElseThrow());
    }
    assertThat(
            signals.executeAll(
                com.usac.jobseeker.job.application.usecase.getjobsignals.GetJobSignalsBatchQuery.of(
                    List.of())))
        .isEmpty();
  }

  @Test
  void aClosedPostingStillHasSignalsSoExistingScoresStayExplainable() {
    JobPostingView closed = JobStates.closed(f);

    assertThat(signals.execute(new GetJobSignalsQuery(closed.posting().id().value())))
        .get()
        .extracting(JobSnapshot::status)
        .isEqualTo("CLOSED");
  }

  // Todas las formas de un requisito y un rango salarial completo salen en la proyección.
  @Test
  void signalsCarryEveryRequirementTypeAndTheSalaryRange() {
    var input =
        new JobDetailsInput(
            "Backend",
            "Servicios",
            WorkModality.HYBRID,
            "GT",
            new BigDecimal("3000"),
            new BigDecimal("4500"),
            "USD",
            SalaryPeriod.MONTH);
    JobPostingView created =
        new CreateJobPostingService(f.store, f.change, f.clock(), UUID::randomUUID)
            .execute(new CreateJobPostingCommand(f.owner, input));
    JobPostingView withRequirements =
        new ManageJobRequirementsService(f.store, f.change, f.clock())
            .execute(
                new ManageJobRequirementsCommand(
                    f.owner,
                    created.posting().id(),
                    created.posting().version(),
                    List.of(
                        JobStates.javaSkill(),
                        new JobRequirementDraft(
                            RequirementType.EDUCATION,
                            false,
                            null,
                            null,
                            EducationLevel.BACHELOR,
                            null,
                            null,
                            null),
                        new JobRequirementDraft(
                            RequirementType.EXPERIENCE_YEARS,
                            false,
                            null,
                            null,
                            null,
                            5,
                            null,
                            null),
                        new JobRequirementDraft(
                            RequirementType.LANGUAGE,
                            false,
                            null,
                            null,
                            null,
                            null,
                            "en",
                            LanguageProficiency.B2))));
    JobPostingView published = JobStates.transition(f, withRequirements, JobStatus.PUBLISHED);

    JobSnapshot snapshot =
        signals.execute(new GetJobSignalsQuery(published.posting().id().value())).orElseThrow();

    assertThat(snapshot.modality()).isEqualTo("HYBRID");
    assertThat(snapshot.country()).isEqualTo("GT");
    assertThat(snapshot.salary())
        .isEqualTo(
            new JobSnapshot.Salary(
                new BigDecimal("3000.00"), new BigDecimal("4500.00"), "USD", "MONTH"));
    assertThat(snapshot.requirements())
        .extracting(JobSnapshot.Requirement::type)
        .containsExactly("SKILL", "EDUCATION", "EXPERIENCE_YEARS", "LANGUAGE");
    assertThat(snapshot.requirements().get(1).educationLevel()).isEqualTo("BACHELOR");
    assertThat(snapshot.requirements().get(2).minYears()).isEqualTo(5);
    assertThat(snapshot.requirements().get(3).language()).isEqualTo("en");
    assertThat(snapshot.requirements().get(3).minProficiency()).isEqualTo("B2");
  }

  // Sin modalidad ni salario, la proyección lo dice con nulos y no inventa un rango.
  @Test
  void aPostingWithoutModalityOrSalaryHasNullsInItsSignals() {
    var bare = new JobDetailsInput("Solo título", "d", null, null, null, null, null, null);
    JobPostingView created =
        new CreateJobPostingService(f.store, f.change, f.clock(), UUID::randomUUID)
            .execute(new CreateJobPostingCommand(f.owner, bare));
    JobPostingView withSkill =
        new ManageJobRequirementsService(f.store, f.change, f.clock())
            .execute(
                new ManageJobRequirementsCommand(
                    f.owner,
                    created.posting().id(),
                    created.posting().version(),
                    List.of(JobStates.javaSkill())));
    JobPostingView published = JobStates.transition(f, withSkill, JobStatus.PUBLISHED);

    JobSnapshot snapshot =
        signals.execute(new GetJobSignalsQuery(published.posting().id().value())).orElseThrow();

    assertThat(snapshot.modality()).isNull();
    assertThat(snapshot.salary()).isNull();
    assertThat(snapshot.country()).isNull();
  }
}
