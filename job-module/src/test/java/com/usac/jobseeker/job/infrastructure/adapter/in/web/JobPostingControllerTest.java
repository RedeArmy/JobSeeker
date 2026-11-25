package com.usac.jobseeker.job.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.job.application.support.JobPostingView;
import com.usac.jobseeker.job.application.usecase.createjobposting.CreateJobPostingCommand;
import com.usac.jobseeker.job.application.usecase.getjobposting.GetJobPostingQuery;
import com.usac.jobseeker.job.application.usecase.managejobrequirements.ManageJobRequirementsCommand;
import com.usac.jobseeker.job.application.usecase.setacceptingapplications.SetAcceptingApplicationsCommand;
import com.usac.jobseeker.job.application.usecase.setrecruitingstage.SetRecruitingStageCommand;
import com.usac.jobseeker.job.application.usecase.transitionjobposting.TransitionJobPostingCommand;
import com.usac.jobseeker.job.application.usecase.updatejobposting.UpdateJobPostingCommand;
import com.usac.jobseeker.job.domain.model.EducationLevel;
import com.usac.jobseeker.job.domain.model.JobDetails;
import com.usac.jobseeker.job.domain.model.JobPosting;
import com.usac.jobseeker.job.domain.model.JobPostingId;
import com.usac.jobseeker.job.domain.model.JobRequirement;
import com.usac.jobseeker.job.domain.model.JobStatus;
import com.usac.jobseeker.job.domain.model.LanguageProficiency;
import com.usac.jobseeker.job.domain.model.RecruitingStage;
import com.usac.jobseeker.job.domain.model.RequirementType;
import com.usac.jobseeker.job.domain.model.SalaryPeriod;
import com.usac.jobseeker.job.domain.model.WorkModality;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** El adaptador solo traduce: identidad del token al caso de uso y resultado al contrato. */
class JobPostingControllerTest {

  private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");

  private final UUID caller = UUID.randomUUID();
  private final JobPostingId postingId = new JobPostingId(UUID.randomUUID());
  private final List<Object> received = new ArrayList<>();
  private boolean ownerView = true;

  private final JobPostingController controller =
      new JobPostingController(
          command -> answer(command),
          command -> answer(command),
          query -> answer(query),
          command -> answer(command),
          command -> answer(command),
          command -> answer(command),
          command -> answer(command));

  private JobPostingView answer(Object request) {
    received.add(request);
    JobDetails details =
        JobDetails.of(
            "Backend",
            "Servicios",
            WorkModality.HYBRID,
            "GT",
            new BigDecimal("3000"),
            new BigDecimal("4500"),
            "USD",
            SalaryPeriod.MONTH);
    JobPosting posting =
        JobPosting.create(postingId, new AccountId(caller), details, NOW)
            .transition(
                JobStatus.PUBLISHED,
                com.usac.jobseeker.job.domain.model.Initiator.OWNER,
                true,
                NOW.plusSeconds(5));
    return new JobPostingView(
        posting,
        List.of(
            new JobRequirement(RequirementType.SKILL, true, "Java", 3, null, null, null, null),
            new JobRequirement(
                RequirementType.EDUCATION,
                false,
                null,
                null,
                EducationLevel.BACHELOR,
                null,
                null,
                null),
            new JobRequirement(
                RequirementType.EXPERIENCE_YEARS, false, null, null, null, 5, null, null),
            new JobRequirement(
                RequirementType.LANGUAGE,
                false,
                null,
                null,
                null,
                null,
                "en",
                LanguageProficiency.B2)),
        ownerView);
  }

  private static Principal principal(UUID id) {
    return id::toString;
  }

  private static JobRequests.Posting body(Long version) {
    return new JobRequests.Posting(
        "Backend",
        "Servicios",
        WorkModality.HYBRID,
        "gt",
        new BigDecimal("3000"),
        new BigDecimal("4500"),
        "usd",
        SalaryPeriod.MONTH,
        version);
  }

  // ---- crear y leer ----

  @Test
  void creatingTakesTheOwnerFromTheTokenAndAnswers201WithTheWholePosting() {
    var response = controller.createPosting(principal(caller), body(null));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody().id()).isEqualTo(postingId.toString());
    assertThat(received)
        .singleElement()
        .isInstanceOfSatisfying(
            CreateJobPostingCommand.class,
            command -> {
              assertThat(command.owner().value()).isEqualTo(caller);
              assertThat(command.details().title()).isEqualTo("Backend");
              assertThat(command.details().salaryCurrency()).isEqualTo("usd");
              assertThat(command.details().modality()).isEqualTo(WorkModality.HYBRID);
            });
  }

  @Test
  void theOwnerSeesTheManagementFieldsAndTheWholeSalary() {
    var response = controller.getPosting(principal(caller), postingId.toString());

    assertThat(response.status()).isEqualTo(JobStatus.PUBLISHED);
    assertThat(response.version()).isEqualTo(2L);
    assertThat(response.recruitingStage()).isEqualTo(RecruitingStage.HIRING);
    assertThat(response.createdAt()).isEqualTo(NOW.toString());
    assertThat(response.updatedAt()).isNotNull();
    assertThat(response.closedAt()).isNull();
    assertThat(response.publishedAt()).isEqualTo(NOW.plusSeconds(5).toString());
    assertThat(response.salary().currency()).isEqualTo("USD");
    assertThat(response.salary().period()).isEqualTo("MONTH");
    assertThat(response.acceptingApplications()).isTrue();
  }

  // BR-VIS-03: a un candidato le llega solo lo que le sirve para decidir si postula.
  @Test
  void aCandidateGetsNoManagementFields() {
    ownerView = false;

    var response = controller.getPosting(principal(caller), postingId.toString());

    assertThat(response.version()).isNull();
    assertThat(response.recruitingStage()).isNull();
    assertThat(response.createdAt()).isNull();
    assertThat(response.updatedAt()).isNull();
    assertThat(response.closedAt()).isNull();
    assertThat(response.title()).isEqualTo("Backend");
    assertThat(response.publishedAt()).isNotNull();
    assertThat(received)
        .singleElement()
        .isInstanceOfSatisfying(
            GetJobPostingQuery.class,
            query -> {
              assertThat(query.viewer().value()).isEqualTo(caller);
              assertThat(query.id()).isEqualTo(postingId);
            });
  }

  @Test
  void everyRequirementTypeIsMappedWithItsOwnFieldsAndNullsForTheRest() {
    var items = controller.getPosting(principal(caller), postingId.toString()).requirements();

    assertThat(items).hasSize(4);
    assertThat(items.get(0).type()).isEqualTo("SKILL");
    assertThat(items.get(0).skillName()).isEqualTo("Java");
    assertThat(items.get(0).minLevel()).isEqualTo(3);
    assertThat(items.get(0).mandatory()).isTrue();
    assertThat(items.get(0).educationLevel()).isNull();
    assertThat(items.get(1).educationLevel()).isEqualTo("BACHELOR");
    assertThat(items.get(2).minYears()).isEqualTo(5);
    assertThat(items.get(3).language()).isEqualTo("en");
    assertThat(items.get(3).minProficiency()).isEqualTo("B2");
  }

  @Test
  void aPostingWithoutSalaryHasNoSalaryInTheResponse() {
    assertThat(JobPostingResponse.Salary.from(null)).isNull();
  }

  @Test
  void aPostingIdThatIsNotAUuidIsNotFound() {
    Principal who = principal(caller);

    assertThatThrownBy(() -> controller.getPosting(who, "xyz"))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> controller.updatePosting(who, "xyz", body(1L)))
        .isInstanceOf(NotFoundException.class);
    assertThat(received).isEmpty();
  }

  // ---- editar ----

  @Test
  void updatingMapsEveryFieldAndTheVersionTheClientRead() {
    controller.updatePosting(principal(caller), postingId.toString(), body(7L));

    assertThat(received)
        .singleElement()
        .isInstanceOfSatisfying(
            UpdateJobPostingCommand.class,
            command -> {
              assertThat(command.owner().value()).isEqualTo(caller);
              assertThat(command.id()).isEqualTo(postingId);
              assertThat(command.expectedVersion()).isEqualTo(7);
              assertThat(command.details().salaryMin()).isEqualByComparingTo("3000");
              assertThat(command.details().salaryPeriod()).isEqualTo(SalaryPeriod.MONTH);
              assertThat(command.details().country()).isEqualTo("gt");
            });
  }

  @Test
  void updatingWithoutAVersionIsRejectedBeforeAnyUseCaseRuns() {
    Principal who = principal(caller);
    String id = postingId.toString();
    var withoutVersion = body(null);

    assertThatThrownBy(() -> controller.updatePosting(who, id, withoutVersion))
        .isInstanceOf(ValidationFailedException.class);
    assertThat(received).isEmpty();
  }

  // ---- requisitos ----

  @Test
  void requirementsAreMappedOneByOneInTheOrderTheyWereSent() {
    var skill =
        new JobRequests.Requirement(RequirementType.SKILL, true, "Java", 3, null, null, null, null);
    var language =
        new JobRequests.Requirement(
            RequirementType.LANGUAGE, null, null, null, null, null, "en", LanguageProficiency.C1);

    controller.replaceRequirements(
        principal(caller),
        postingId.toString(),
        new JobRequests.Requirements(List.of(skill, language), 4L));

    assertThat(received)
        .singleElement()
        .isInstanceOfSatisfying(
            ManageJobRequirementsCommand.class,
            command -> {
              assertThat(command.expectedVersion()).isEqualTo(4);
              assertThat(command.requirements()).hasSize(2);
              assertThat(command.requirements().get(0).skillName()).isEqualTo("Java");
              assertThat(command.requirements().get(0).mandatory()).isTrue();
              assertThat(command.requirements().get(1).minProficiency())
                  .isEqualTo(LanguageProficiency.C1);
            });
  }

  @Test
  void aMissingListOfRequirementsMeansAnEmptySetNotAnError() {
    controller.replaceRequirements(
        principal(caller), postingId.toString(), new JobRequests.Requirements(null, 4L));

    assertThat(received)
        .singleElement()
        .isInstanceOfSatisfying(
            ManageJobRequirementsCommand.class,
            command -> assertThat(command.requirements()).isEmpty());
  }

  @Test
  void aNullElementInTheRequirementsReachesTheUseCaseAsAnInvalidDraft() {
    var withNull = new ArrayList<JobRequests.Requirement>();
    withNull.add(null);

    controller.replaceRequirements(
        principal(caller), postingId.toString(), new JobRequests.Requirements(withNull, 1L));

    assertThat(received)
        .singleElement()
        .isInstanceOfSatisfying(
            ManageJobRequirementsCommand.class,
            command -> assertThat(command.requirements()).hasSize(1));
  }

  // ---- estado y ejes ----

  @Test
  void aTransitionNeedsItsDestinationAndItsVersion() {
    Principal who = principal(caller);
    String id = postingId.toString();
    var noDestination = new JobRequests.Transition(null, 3L);
    var noVersion = new JobRequests.Transition(JobStatus.CLOSED, null);

    assertThatThrownBy(() -> controller.transition(who, id, noDestination))
        .isInstanceOf(ValidationFailedException.class);
    assertThatThrownBy(() -> controller.transition(who, id, noVersion))
        .isInstanceOf(ValidationFailedException.class);
    assertThat(received).isEmpty();

    controller.transition(who, id, new JobRequests.Transition(JobStatus.CLOSED, 3L));

    assertThat(received)
        .singleElement()
        .isInstanceOfSatisfying(
            TransitionJobPostingCommand.class,
            command -> {
              assertThat(command.to()).isEqualTo(JobStatus.CLOSED);
              assertThat(command.expectedVersion()).isEqualTo(3);
            });
  }

  @Test
  void pausingApplicationsNeedsTheFlagAndTheVersion() {
    Principal who = principal(caller);
    String id = postingId.toString();
    var noFlag = new JobRequests.AcceptingApplications(null, 3L);

    assertThatThrownBy(() -> controller.setAcceptingApplications(who, id, noFlag))
        .isInstanceOf(ValidationFailedException.class);

    controller.setAcceptingApplications(who, id, new JobRequests.AcceptingApplications(false, 3L));

    assertThat(received)
        .singleElement()
        .isInstanceOfSatisfying(
            SetAcceptingApplicationsCommand.class,
            command -> {
              assertThat(command.accepting()).isFalse();
              assertThat(command.expectedVersion()).isEqualTo(3);
            });
  }

  @Test
  void theRecruitingStageNeedsItsValueAndItsVersion() {
    Principal who = principal(caller);
    String id = postingId.toString();
    var noStage = new JobRequests.Stage(null, 3L);

    assertThatThrownBy(() -> controller.setRecruitingStage(who, id, noStage))
        .isInstanceOf(ValidationFailedException.class);

    controller.setRecruitingStage(who, id, new JobRequests.Stage(RecruitingStage.REVIEWING, 3L));

    assertThat(received)
        .singleElement()
        .isInstanceOfSatisfying(
            SetRecruitingStageCommand.class,
            command -> assertThat(command.stage()).isEqualTo(RecruitingStage.REVIEWING));
  }
}
