package com.usac.jobseeker.ats.application.usecase.withdrawapplication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.ats.application.support.ActorCapabilityPort;
import com.usac.jobseeker.ats.application.support.InMemoryApplications;
import com.usac.jobseeker.ats.application.usecase.listcandidateapplications.CandidateApplication;
import com.usac.jobseeker.ats.application.usecase.listcandidateapplications.ListCandidateApplicationsQuery;
import com.usac.jobseeker.ats.application.usecase.listcandidateapplications.ListCandidateApplicationsService;
import com.usac.jobseeker.ats.domain.event.ApplicationEvents;
import com.usac.jobseeker.ats.domain.model.Application;
import com.usac.jobseeker.ats.domain.model.ApplicationId;
import com.usac.jobseeker.ats.domain.model.ChangeInitiator;
import com.usac.jobseeker.ats.domain.model.RejectionReason;
import com.usac.jobseeker.common.application.outbox.OutboxEvent;
import com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException;
import com.usac.jobseeker.common.domain.error.InvalidTransitionException;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.StaleStateException;
import com.usac.jobseeker.common.domain.model.AccountId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Retirar una postulación (REQ-C-03) y la lista del candidato que consume el tracker. */
class WithdrawAndListApplicationsTest {

  private static final Instant APPLIED = Instant.parse("2026-11-27T10:00:00Z");
  private static final Instant NOW = Instant.parse("2026-11-28T10:00:00Z");

  private final AccountId candidate = new AccountId(UUID.randomUUID());
  private final InMemoryApplications store = new InMemoryApplications();
  private final List<OutboxEvent> events = new ArrayList<>();
  private final List<String> metrics = new ArrayList<>();
  private boolean isCandidate = true;

  private final ActorCapabilityPort capabilities =
      new ActorCapabilityPort() {
        @Override
        public boolean isCandidate(AccountId accountId) {
          return isCandidate;
        }

        @Override
        public boolean isRecruiter(AccountId accountId) {
          return false;
        }
      };

  private final WithdrawApplicationService withdraw =
      new WithdrawApplicationService(
          capabilities,
          store,
          events::add,
          (u, o) -> metrics.add(o),
          UUID::randomUUID,
          Clock.fixed(NOW, ZoneOffset.UTC));
  private final ListCandidateApplicationsService list =
      new ListCandidateApplicationsService(store, (u, o) -> metrics.add(o));

  private Application applied(AccountId owner, Instant at) {
    return store.put(
        Application.submit(
            new ApplicationId(UUID.randomUUID()),
            UUID.randomUUID(),
            owner,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            at));
  }

  private CandidateApplication withdrawing(Application application) {
    return withdraw.execute(new WithdrawApplicationCommand(candidate, application.id().value()));
  }

  @Test
  void withdrawingKeepsTheApplicationRecordsTheChangeAndPublishesIt() {
    Application application = applied(candidate, APPLIED);

    CandidateApplication withdrawn = withdrawing(application);

    assertThat(withdrawn.status()).isEqualTo("WITHDRAWN");
    assertThat(withdrawn.stage()).isEqualTo("NEW");
    assertThat(withdrawn.version()).isEqualTo(2);
    assertThat(withdrawn.updatedAt()).isEqualTo(NOW);
    assertThat(withdrawn.history())
        .extracting(CandidateApplication.Change::toStatus)
        .containsExactly("ACTIVE", "WITHDRAWN");
    assertThat(withdrawn.history().get(1).by()).isEqualTo("CANDIDATE");
    assertThat(events)
        .singleElement()
        .satisfies(e -> assertThat(e.eventType()).isEqualTo(ApplicationEvents.STATUS_CHANGED));
    assertThat(metrics).containsExactly("withdrawn");
  }

  // Regla 2: la de otro candidato no existe; tampoco una que no existe.
  @Test
  void anotherCandidatesApplicationDoesNotExist() {
    Application theirs = applied(new AccountId(UUID.randomUUID()), APPLIED);

    assertThatThrownBy(() -> withdrawing(theirs)).isInstanceOf(NotFoundException.class);
    WithdrawApplicationCommand unknown =
        new WithdrawApplicationCommand(candidate, UUID.randomUUID());
    assertThatThrownBy(() -> withdraw.execute(unknown)).isInstanceOf(NotFoundException.class);
    assertThat(events).isEmpty();
  }

  @Test
  void aFinishedApplicationCannotBeWithdrawn() {
    Application rejected =
        store.put(
            applied(candidate, APPLIED)
                .reject(
                    RejectionReason.NOT_A_FIT, ChangeInitiator.RECRUITER, UUID.randomUUID(), NOW));

    assertThatThrownBy(() -> withdrawing(rejected)).isInstanceOf(InvalidTransitionException.class);
    assertThat(metrics).containsExactly("invalid_transition");
  }

  @Test
  void aChangeThatArrivedFirstMakesItStale() {
    Application application = applied(candidate, APPLIED);
    store.loseTheRace = true;

    assertThatThrownBy(() -> withdrawing(application)).isInstanceOf(StaleStateException.class);
    assertThat(events).isEmpty();
  }

  @Test
  void withoutTheCandidateCapabilityNothingIsRead() {
    Application application = applied(candidate, APPLIED);
    isCandidate = false;

    assertThatThrownBy(() -> withdrawing(application))
        .isInstanceOf(ForbiddenCapabilityException.class);
  }

  // BR-TRACK-03: el candidato ve el estado y la columna, nunca el motivo interno del descarte.
  @Test
  void theListShowsOwnApplicationsNewestFirstWithoutTheRejectionReason() {
    Application older = applied(candidate, APPLIED);
    Application newer = applied(candidate, APPLIED.plusSeconds(3600));
    store.put(
        older.reject(
            RejectionReason.CANDIDATE_UNRESPONSIVE,
            ChangeInitiator.RECRUITER,
            UUID.randomUUID(),
            NOW));
    applied(new AccountId(UUID.randomUUID()), APPLIED);

    List<CandidateApplication> mine = list.execute(new ListCandidateApplicationsQuery(candidate));

    assertThat(mine)
        .extracting(CandidateApplication::applicationId)
        .containsExactly(newer.id().value(), older.id().value());
    assertThat(mine.get(1).status()).isEqualTo("REJECTED");
    assertThat(mine.get(1).history()).hasSize(2);
    assertThat(mine.toString()).doesNotContain("CANDIDATE_UNRESPONSIVE");
  }

  @Test
  void aCandidateWithoutApplicationsGetsAnEmptyList() {
    assertThat(list.execute(new ListCandidateApplicationsQuery(candidate))).isEmpty();
    assertThat(metrics).containsExactly("empty");
  }
}
