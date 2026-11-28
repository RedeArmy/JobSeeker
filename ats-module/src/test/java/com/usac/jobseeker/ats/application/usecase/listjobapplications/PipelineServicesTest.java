package com.usac.jobseeker.ats.application.usecase.listjobapplications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.ats.application.support.ActorCapabilityPort;
import com.usac.jobseeker.ats.application.support.ApplicantProfilesPort;
import com.usac.jobseeker.ats.application.support.ApplicantScoresPort;
import com.usac.jobseeker.ats.application.support.InMemoryApplications;
import com.usac.jobseeker.ats.application.usecase.getapplication.ApplicationDetail;
import com.usac.jobseeker.ats.application.usecase.getapplication.GetApplicationQuery;
import com.usac.jobseeker.ats.application.usecase.getapplication.GetApplicationService;
import com.usac.jobseeker.ats.domain.model.Application;
import com.usac.jobseeker.ats.domain.model.ApplicationId;
import com.usac.jobseeker.ats.domain.model.ApplicationStatus;
import com.usac.jobseeker.ats.domain.model.ChangeInitiator;
import com.usac.jobseeker.ats.domain.model.PipelineStage;
import com.usac.jobseeker.ats.domain.model.RejectionReason;
import com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.model.AccountId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** El pipeline y el detalle del reclutador (tdd/ats-tracker-modules.md §4.1, §6 y §9). */
class PipelineServicesTest {

  private static final Instant T0 = Instant.parse("2026-11-28T10:00:00Z");

  private final AccountId recruiter = new AccountId(UUID.randomUUID());
  private final UUID job = UUID.randomUUID();
  private final InMemoryApplications store = new InMemoryApplications();
  private final Map<UUID, ApplicantScoresPort.Score> scores = new HashMap<>();
  private final List<Collection<AccountId>> profileReads = new ArrayList<>();
  private final List<String> metrics = new ArrayList<>();
  private boolean isRecruiter = true;
  private int cap = 1000;

  private final ActorCapabilityPort capabilities =
      new ActorCapabilityPort() {
        @Override
        public boolean isCandidate(AccountId accountId) {
          return false;
        }

        @Override
        public boolean isRecruiter(AccountId accountId) {
          return isRecruiter;
        }
      };

  private final ApplicantProfilesPort profiles =
      accounts -> {
        profileReads.add(accounts);
        Map<AccountId, ApplicantProfilesPort.Profile> found = new HashMap<>();
        for (AccountId a : accounts) {
          found.put(
              a,
              new ApplicantProfilesPort.Profile(
                  "Persona " + a.value(), "Backend", "p@example.test", null, false));
        }
        return found;
      };

  private ListJobApplicationsService list() {
    return new ListJobApplicationsService(
        capabilities,
        (owner, jobId) -> owner.equals(recruiter) && jobId.equals(job),
        store,
        (jobId, ids) -> {
          Map<UUID, ApplicantScoresPort.Score> found = new HashMap<>();
          ids.forEach(
              id -> found.put(id, scores.getOrDefault(id, ApplicantScoresPort.Score.pending())));
          return found;
        },
        profiles,
        (u, o) -> metrics.add(o),
        cap);
  }

  private final GetApplicationService get =
      new GetApplicationService(
          capabilities,
          (owner, jobId) -> owner.equals(recruiter) && jobId.equals(job),
          store,
          (jobId, ids) -> Map.of(),
          profiles,
          (u, o) -> metrics.add(o));

  private Application applied(long minutesAfter, Double score) {
    Application application =
        store.put(
            Application.submit(
                new ApplicationId(UUID.randomUUID()),
                UUID.randomUUID(),
                new AccountId(UUID.randomUUID()),
                job,
                UUID.randomUUID(),
                UUID.randomUUID(),
                T0.plusSeconds(60 * minutesAfter)));
    if (score != null) {
      scores.put(
          application.candidateProfileId(),
          new ApplicantScoresPort.Score("OK", score, false, "v1", score, null));
    }
    return application;
  }

  private PipelinePage page(
      ApplicationStatus status,
      PipelineStage stage,
      Double minScore,
      ListJobApplicationsQuery.Sort sort,
      int page,
      int size) {
    return list()
        .execute(
            new ListJobApplicationsQuery(
                recruiter, job, status, stage, minScore, sort, page, size));
  }

  private PipelinePage byScore() {
    return page(null, null, null, ListJobApplicationsQuery.Sort.SCORE, 0, 20);
  }

  private static List<UUID> ids(PipelinePage page) {
    return page.content().stream().map(PipelineItem::applicationId).toList();
  }

  // Mayor puntaje primero; a igual puntaje, quien postuló antes; los pendientes al final.
  @Test
  void byScoreHigherFirstTiesByApplicationDateAndPendingLast() {
    Application pending = applied(0, null);
    Application lateTie = applied(5, 80.0);
    Application earlyTie = applied(1, 80.0);
    Application best = applied(9, 95.0);

    PipelinePage result = byScore();

    assertThat(ids(result))
        .containsExactly(
            best.id().value(), earlyTie.id().value(), lateTie.id().value(), pending.id().value());
    assertThat(result.content().get(3).score().isPending()).isTrue();
    assertThat(result.content().get(3).score().overall()).isNull();
    assertThat(result.totalElements()).isEqualTo(4);
    assertThat(result.truncated()).isFalse();
  }

  @Test
  void byDateNewestFirst() {
    Application first = applied(0, 90.0);
    Application second = applied(1, 10.0);

    assertThat(ids(page(null, null, null, ListJobApplicationsQuery.Sort.DATE, 0, 20)))
        .containsExactly(second.id().value(), first.id().value());
  }

  @Test
  void filtersByStatusStageAndMinimumScoreWhichLeavesPendingOut() {
    Application high = applied(0, 90.0);
    applied(1, 40.0);
    applied(2, null);
    Application rejected = applied(3, 95.0);
    store.put(
        rejected.reject(
            RejectionReason.NOT_A_FIT, ChangeInitiator.RECRUITER, UUID.randomUUID(), T0));

    assertThat(ids(page(null, null, 50.0, ListJobApplicationsQuery.Sort.SCORE, 0, 20)))
        .containsExactly(rejected.id().value(), high.id().value());
    PipelinePage active =
        page(
            ApplicationStatus.ACTIVE,
            PipelineStage.NEW,
            50.0,
            ListJobApplicationsQuery.Sort.SCORE,
            0,
            20);
    assertThat(ids(active)).containsExactly(high.id().value());
    PipelinePage onlyRejected =
        page(ApplicationStatus.REJECTED, null, null, ListJobApplicationsQuery.Sort.SCORE, 0, 20);
    assertThat(onlyRejected.content())
        .singleElement()
        .satisfies(item -> assertThat(item.rejectionReason()).isEqualTo("NOT_A_FIT"));
  }

  // Los datos del candidato se piden una vez y solo para la página que se devuelve.
  @Test
  void pagesAreStableAndOnlyThePageAsksCvForPeople() {
    for (int i = 0; i < 5; i++) {
      applied(i, 50.0 + i);
    }

    PipelinePage second = page(null, null, null, ListJobApplicationsQuery.Sort.SCORE, 1, 2);

    assertThat(second.content()).hasSize(2);
    assertThat(second.totalElements()).isEqualTo(5);
    assertThat(second.content()).allSatisfy(item -> assertThat(item.candidate()).isNotNull());
    assertThat(profileReads).singleElement().satisfies(read -> assertThat(read).hasSize(2));
    assertThat(page(null, null, null, ListJobApplicationsQuery.Sort.SCORE, 9, 2).content())
        .isEmpty();
  }

  // Regla 6: por encima del tope solo se ordenan las primeras, y la página lo dice.
  @Test
  void aPostingAboveTheCapIsMarkedTruncated() {
    cap = 2;
    applied(0, 10.0);
    applied(1, 20.0);
    applied(2, 99.0);

    PipelinePage result = byScore();

    assertThat(result.truncated()).isTrue();
    assertThat(result.totalElements()).isEqualTo(2);
    assertThat(metrics).containsExactly("truncated");
  }

  // Regla 1: lo ajeno no existe, y sin autorizar no se lee nada más.
  @Test
  void anotherRecruitersPostingDoesNotExistAndNothingElseIsRead() {
    applied(0, 90.0);
    ListJobApplicationsQuery foreign =
        new ListJobApplicationsQuery(
            new AccountId(UUID.randomUUID()),
            job,
            null,
            null,
            null,
            ListJobApplicationsQuery.Sort.SCORE,
            0,
            20);

    assertThatThrownBy(() -> list().execute(foreign)).isInstanceOf(NotFoundException.class);
    assertThat(profileReads).isEmpty();
  }

  @Test
  void withoutTheRecruiterCapabilityItIsForbidden() {
    isRecruiter = false;

    assertThatThrownBy(this::byScore).isInstanceOf(ForbiddenCapabilityException.class);
  }

  @Test
  void invalidPagingOrMinimumScoreIsAValidationError() {
    for (int[] bad : new int[][] {{-1, 20}, {0, 0}, {0, 101}}) {
      assertThatThrownBy(
              () ->
                  new ListJobApplicationsQuery(
                      recruiter,
                      job,
                      null,
                      null,
                      null,
                      ListJobApplicationsQuery.Sort.SCORE,
                      bad[0],
                      bad[1]))
          .isInstanceOf(ValidationFailedException.class);
    }
    assertThatThrownBy(
            () ->
                new ListJobApplicationsQuery(
                    recruiter, job, null, null, 101.0, ListJobApplicationsQuery.Sort.SCORE, 0, 20))
        .isInstanceOf(ValidationFailedException.class);
  }

  // REQ-C-07 y REQ-R-03: el dueño ve el contacto y el historial con el motivo del descarte.
  @Test
  void theOwnerSeesTheContactAPendingScoreAndTheHistoryWithReasons() {
    Application application = applied(0, null);
    store.put(
        application.reject(
            RejectionReason.CANDIDATE_UNRESPONSIVE,
            ChangeInitiator.RECRUITER,
            UUID.randomUUID(),
            T0));

    ApplicationDetail detail =
        get.execute(new GetApplicationQuery(recruiter, application.id().value()));

    assertThat(detail.candidate().contactEmail()).isEqualTo("p@example.test");
    assertThat(detail.score().isPending()).isTrue();
    assertThat(detail.rejectionReason()).isEqualTo("CANDIDATE_UNRESPONSIVE");
    assertThat(detail.history())
        .extracting(ApplicationDetail.Change::rejectionReason)
        .containsExactly(null, "CANDIDATE_UNRESPONSIVE");
  }

  @Test
  void anotherRecruiterNeverGetsTheContact() {
    Application application = applied(0, 90.0);
    GetApplicationQuery foreign =
        new GetApplicationQuery(new AccountId(UUID.randomUUID()), application.id().value());
    GetApplicationQuery unknown = new GetApplicationQuery(recruiter, UUID.randomUUID());

    assertThatThrownBy(() -> get.execute(foreign)).isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> get.execute(unknown)).isInstanceOf(NotFoundException.class);
    assertThat(profileReads).isEmpty();
  }
}
