package com.usac.jobseeker.ats.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.ats.application.support.ApplicantProfilesPort;
import com.usac.jobseeker.ats.application.support.ApplicantScoresPort;
import com.usac.jobseeker.ats.application.usecase.getapplication.ApplicationDetail;
import com.usac.jobseeker.ats.application.usecase.getapplication.GetApplicationQuery;
import com.usac.jobseeker.ats.application.usecase.listjobapplications.ListJobApplicationsQuery;
import com.usac.jobseeker.ats.application.usecase.listjobapplications.PipelineItem;
import com.usac.jobseeker.ats.application.usecase.listjobapplications.PipelinePage;
import com.usac.jobseeker.ats.domain.model.ApplicationStatus;
import com.usac.jobseeker.ats.domain.model.PipelineStage;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** El adaptador solo traduce: el token y la consulta al caso de uso, y el resultado al contrato. */
class PipelineControllerTest {

  private static final Instant AT = Instant.parse("2026-11-28T10:00:00Z");

  private final UUID caller = UUID.randomUUID();
  private final Principal principal = caller::toString;
  private final UUID job = UUID.randomUUID();
  private final UUID application = UUID.randomUUID();
  private final List<Object> received = new ArrayList<>();

  private final ApplicantScoresPort.Score scored =
      new ApplicantScoresPort.Score(
          "OK",
          72.6,
          false,
          "1|m|a|b",
          60.4,
          new ApplicantScoresPort.Breakdown(
              0.8,
              0.3,
              0.7,
              0.75,
              List.of(
                  new ApplicantScoresPort.Dimension(
                      "SKILLS", "AVAILABLE", 0.9, 0.35, "Java cubierto"))));
  private final ApplicantProfilesPort.Profile ana =
      new ApplicantProfilesPort.Profile("Ana", "Backend", "ana@example.test", "+502 1", false);

  private final PipelineController controller =
      new PipelineController(
          query -> {
            received.add(query);
            return new PipelinePage(
                List.of(
                    new PipelineItem(
                        application, ana, "REJECTED", "OFFER", "NOT_A_FIT", 3, AT, AT, scored),
                    new PipelineItem(
                        UUID.randomUUID(),
                        null,
                        "ACTIVE",
                        "NEW",
                        null,
                        1,
                        AT,
                        AT,
                        ApplicantScoresPort.Score.notCalculatedYet())),
                0,
                20,
                2,
                false);
          },
          query -> {
            received.add(query);
            return new ApplicationDetail(
                application,
                job,
                ana,
                "REJECTED",
                "OFFER",
                "NOT_A_FIT",
                3,
                AT,
                AT,
                scored,
                List.of(
                    new ApplicationDetail.Change(
                        null, null, "ACTIVE", "NEW", "CANDIDATE", null, AT),
                    new ApplicationDetail.Change(
                        "ACTIVE", "OFFER", "REJECTED", "OFFER", "RECRUITER", "NOT_A_FIT", AT)));
          });

  @Test
  void thePipelineTranslatesFiltersAndRoundsTheScoreWithoutContact() {
    PipelineResponses.Page page =
        controller.listJobApplications(
            principal, job.toString(), " rejected ", "offer", 50.0, "date", 1, 10);

    ListJobApplicationsQuery query = (ListJobApplicationsQuery) received.get(0);
    assertThat(query.recruiter().value()).isEqualTo(caller);
    assertThat(query.jobPostingId()).isEqualTo(job);
    assertThat(query.status()).isEqualTo(ApplicationStatus.REJECTED);
    assertThat(query.stage()).isEqualTo(PipelineStage.OFFER);
    assertThat(query.minScore()).isEqualTo(50.0);
    assertThat(query.sort()).isEqualTo(ListJobApplicationsQuery.Sort.DATE);
    assertThat(query.page()).isEqualTo(1);
    assertThat(query.size()).isEqualTo(10);

    PipelineResponses.Item first = page.content().get(0);
    assertThat(first.candidate()).isEqualTo(new PipelineResponses.Person("Ana", "Backend", false));
    assertThat(first.rejectionReason()).isEqualTo("NOT_A_FIT");
    assertThat(first.score()).isEqualTo(new PipelineResponses.Summary("OK", 73, false, 60));
    PipelineResponses.Item second = page.content().get(1);
    assertThat(second.candidate()).isNull();
    assertThat(second.score())
        .isEqualTo(new PipelineResponses.Summary("PENDING", null, false, null));
    assertThat(page.totalElements()).isEqualTo(2);
    assertThat(page.truncated()).isFalse();
  }

  @Test
  void emptyFiltersMeanNoFilter() {
    controller.listJobApplications(principal, job.toString(), null, " ", null, "SCORE", 0, 20);

    ListJobApplicationsQuery query = (ListJobApplicationsQuery) received.get(0);
    assertThat(query.status()).isNull();
    assertThat(query.stage()).isNull();
    assertThat(query.sort()).isEqualTo(ListJobApplicationsQuery.Sort.SCORE);
  }

  @Test
  void aValueOutsideTheCatalogIsAValidationErrorOnItsField() {
    String id = job.toString();

    assertThatThrownBy(
            () -> controller.listJobApplications(principal, id, "LOST", null, null, "SCORE", 0, 20))
        .isInstanceOfSatisfying(
            ValidationFailedException.class,
            e -> assertThat(e.violations()).extracting("field").containsExactly("status"));
    assertThatThrownBy(
            () -> controller.listJobApplications(principal, id, null, null, null, "RANDOM", 0, 20))
        .isInstanceOfSatisfying(
            ValidationFailedException.class,
            e -> assertThat(e.violations()).extracting("field").containsExactly("sort"));
    assertThat(received).isEmpty();
  }

  @Test
  void anIdThatIsNotAUuidDoesNotExist() {
    assertThatThrownBy(
            () ->
                controller.listJobApplications(
                    principal, "no-es-un-uuid", null, null, null, "SCORE", 0, 20))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> controller.getApplication(principal, "no-es-un-uuid"))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void theDetailCarriesContactBreakdownAndHistoryWithReasons() {
    PipelineResponses.Detail detail = controller.getApplication(principal, application.toString());

    GetApplicationQuery query = (GetApplicationQuery) received.get(0);
    assertThat(query.recruiter().value()).isEqualTo(caller);
    assertThat(query.applicationId()).isEqualTo(application);
    assertThat(detail.candidate())
        .isEqualTo(
            new PipelineResponses.Contact("Ana", "Backend", "ana@example.test", "+502 1", false));
    assertThat(detail.score().overall()).isEqualTo(73);
    assertThat(detail.score().appliedOverall()).isEqualTo(60);
    assertThat(detail.score().algorithmVersion()).isEqualTo("1|m|a|b");
    assertThat(detail.score().breakdown().dimensions())
        .containsExactly(
            new PipelineResponses.Dimension("SKILLS", "AVAILABLE", 0.9, 0.35, "Java cubierto"));
    assertThat(detail.history())
        .extracting(PipelineResponses.Change::rejectionReason)
        .containsExactly(null, "NOT_A_FIT");
    assertThat(detail.jobPostingId()).isEqualTo(job);
  }

  @Test
  void aPendingScoreHasNoBreakdownAndAMissingPersonIsNull() {
    var pendingDetail =
        new PipelineController(
                query -> null,
                query ->
                    new ApplicationDetail(
                        application,
                        job,
                        null,
                        "ACTIVE",
                        "NEW",
                        null,
                        1,
                        AT,
                        AT,
                        ApplicantScoresPort.Score.notCalculatedYet(),
                        List.of()))
            .getApplication(principal, application.toString());

    assertThat(pendingDetail.candidate()).isNull();
    assertThat(pendingDetail.score().breakdown()).isNull();
    assertThat(pendingDetail.score().overall()).isNull();
  }
}
