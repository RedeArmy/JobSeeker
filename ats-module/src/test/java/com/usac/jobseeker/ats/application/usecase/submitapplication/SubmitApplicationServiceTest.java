package com.usac.jobseeker.ats.application.usecase.submitapplication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.ats.application.support.ActorCapabilityPort;
import com.usac.jobseeker.ats.application.support.ApplicationSnapshotPort;
import com.usac.jobseeker.ats.application.support.ApplicationStorePort;
import com.usac.jobseeker.ats.domain.event.ApplicationEvents;
import com.usac.jobseeker.ats.domain.model.Application;
import com.usac.jobseeker.ats.domain.model.ApplicationId;
import com.usac.jobseeker.ats.domain.model.ApplicationStatus;
import com.usac.jobseeker.ats.domain.model.ChangeInitiator;
import com.usac.jobseeker.ats.domain.model.PipelineStage;
import com.usac.jobseeker.ats.domain.model.StatusChange;
import com.usac.jobseeker.common.application.idempotency.IdempotencyKeysPort;
import com.usac.jobseeker.common.application.outbox.OutboxEvent;
import com.usac.jobseeker.common.domain.error.ApplicationDeniedException;
import com.usac.jobseeker.common.domain.error.ApplicationRequirementsMissingException;
import com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException;
import com.usac.jobseeker.common.domain.error.IdempotencyKeyReusedException;
import com.usac.jobseeker.common.domain.error.InvalidStateException;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.model.AccountId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Postular (tdd/ats-tracker-modules.md §9): el orden, cada regla y la idempotencia. */
class SubmitApplicationServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");

  private final AccountId candidate = new AccountId(UUID.randomUUID());
  private final UUID job = UUID.randomUUID();
  private final UUID profile = UUID.randomUUID();
  private final UUID cv = UUID.randomUUID();

  private boolean isCandidate = true;
  private JobApplicationGatePort.Gate gate = JobApplicationGatePort.Gate.OPEN;
  private ApplicantReadinessPort.Readiness readiness =
      new ApplicantReadinessPort.Readiness(List.of(), Optional.of(profile), Optional.of(cv));
  private final Store store = new Store();
  private final Keys keys = new Keys();
  private final List<UUID> snapshots = new ArrayList<>();
  private RuntimeException snapshotFailure;
  private final List<OutboxEvent> events = new ArrayList<>();
  private final List<String> metrics = new ArrayList<>();

  private final ApplicationSnapshotPort snapshotPort =
      (account, jobId, application) -> {
        if (snapshotFailure != null) {
          throw snapshotFailure;
        }
        snapshots.add(application.value());
      };

  private final SubmitApplicationService service =
      new SubmitApplicationService(
          new SubmitApplicationService.Ports(
              new ActorCapabilityPort() {
                @Override
                public boolean isCandidate(AccountId accountId) {
                  return isCandidate;
                }

                @Override
                public boolean isRecruiter(AccountId accountId) {
                  return false;
                }
              },
              keys,
              (account, jobId) -> gate,
              account -> readiness,
              store,
              snapshotPort,
              events::add),
          (useCase, outcome) -> metrics.add(outcome),
          UUID::randomUUID,
          Clock.fixed(NOW, ZoneOffset.UTC));

  private SubmittedApplication submit(String key) {
    return service.execute(new SubmitApplicationCommand(candidate, job, key));
  }

  @Test
  void submittingCreatesTheApplicationItsHistoryTheSnapshotAndTheEvent() {
    SubmittedApplication submitted = submit(null);

    assertThat(submitted.status()).isEqualTo("ACTIVE");
    assertThat(submitted.stage()).isEqualTo("NEW");
    assertThat(submitted.version()).isEqualTo(1);
    assertThat(submitted.appliedAt()).isEqualTo(NOW);
    assertThat(submitted.jobPostingId()).isEqualTo(job);
    Application saved = store.rows.get(submitted.applicationId());
    assertThat(saved.candidateProfileId()).isEqualTo(profile);
    assertThat(saved.candidateAccountId()).isEqualTo(candidate);
    assertThat(saved.cvFileId()).as("el CV vigente al postular").isEqualTo(cv);
    assertThat(store.history)
        .singleElement()
        .satisfies(
            change -> {
              assertThat(change.fromStatus()).isNull();
              assertThat(change.toStatus()).isEqualTo(ApplicationStatus.ACTIVE);
              assertThat(change.toStage()).isEqualTo(PipelineStage.NEW);
              assertThat(change.initiator()).isEqualTo(ChangeInitiator.CANDIDATE);
            });
    assertThat(snapshots).containsExactly(submitted.applicationId());
    assertThat(events)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.eventType()).isEqualTo(ApplicationEvents.SUBMITTED);
              assertThat(event.aggregateType()).isEqualTo("APPLICATION");
              assertThat(event.aggregateId()).isEqualTo(submitted.applicationId());
            });
    assertThat(metrics).containsExactly("submitted");
  }

  @Test
  void withoutTheCandidateCapabilityNothingIsTouched() {
    isCandidate = false;

    assertThatThrownBy(() -> submit("clave-1")).isInstanceOf(ForbiddenCapabilityException.class);
    assertThat(keys.claimed).as("ni siquiera se reserva la clave").isEmpty();
    assertThat(metrics).containsExactly("forbidden");
  }

  @Test
  void aPostingTheCandidateCannotSeeDoesNotExist() {
    gate = JobApplicationGatePort.Gate.NOT_FOUND;

    assertThatThrownBy(() -> submit(null)).isInstanceOf(NotFoundException.class);
    assertNothingWritten("not_found");
  }

  @Test
  void aRecruiterCannotApplyToTheirOwnPosting() {
    gate = JobApplicationGatePort.Gate.OWN_JOB;

    assertThatThrownBy(() -> submit(null))
        .isInstanceOfSatisfying(
            ApplicationDeniedException.class,
            e ->
                assertThat(e.reason())
                    .isEqualTo(ApplicationDeniedException.Reason.SELF_APPLICATION));
    assertNothingWritten("self_application");
  }

  @Test
  void aClosedOrPausedPostingDoesNotAcceptApplications() {
    gate = JobApplicationGatePort.Gate.NOT_ACCEPTING;

    assertThatThrownBy(() -> submit(null)).isInstanceOf(InvalidStateException.class);
    assertNothingWritten("not_accepting");
  }

  @Test
  void whatIsMissingForBrCand01IsReturned() {
    readiness =
        new ApplicantReadinessPort.Readiness(
            List.of("CONTACT", "CV"), Optional.empty(), Optional.empty());

    assertThatThrownBy(() -> submit(null))
        .isInstanceOfSatisfying(
            ApplicationRequirementsMissingException.class,
            e -> assertThat(e.missing()).containsExactly("CONTACT", "CV"));
    assertNothingWritten("requirements_missing");
  }

  // La vacante se comprueba primero: si no existe, no importa qué le falte al perfil.
  @Test
  void theGateIsCheckedBeforeTheReadiness() {
    gate = JobApplicationGatePort.Gate.NOT_FOUND;
    readiness =
        new ApplicantReadinessPort.Readiness(List.of("CV"), Optional.empty(), Optional.empty());

    assertThatThrownBy(() -> submit(null)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void anOpenApplicationToTheSamePostingIsAlreadyApplied() {
    store.open = true;

    assertThatThrownBy(() -> submit(null))
        .isInstanceOfSatisfying(
            ApplicationDeniedException.class,
            e ->
                assertThat(e.reason())
                    .isEqualTo(ApplicationDeniedException.Reason.ALREADY_APPLIED));
    assertNothingWritten("already_applied");
  }

  // Regla 4: si otro envío gana la carrera después de la lectura, el índice lo detiene igual.
  @Test
  void losingTheRaceAtTheIndexIsAlsoAlreadyApplied() {
    store.loseTheRace = true;

    assertThatThrownBy(() -> submit(null)).isInstanceOf(ApplicationDeniedException.class);
    assertThat(snapshots).isEmpty();
    assertThat(events).isEmpty();
    assertThat(metrics).containsExactly("already_applied");
  }

  // Regla 3: si la copia falla, el caso de uso no sigue; la transacción deshace lo escrito.
  @Test
  void aFailedSnapshotStopsBeforeTheEventAndTheStoredResponse() {
    snapshotFailure = new InvalidStateException();

    assertThatThrownBy(() -> submit("clave-1")).isSameAs(snapshotFailure);
    assertThat(events).isEmpty();
    assertThat(keys.responses).isEmpty();
  }

  @Test
  void repeatingTheSameKeyReturnsTheSameApplicationWithoutDoingItAgain() {
    SubmittedApplication first = submit("clave-1");

    SubmittedApplication again = submit("clave-1");

    assertThat(again).isEqualTo(first);
    assertThat(store.rows).hasSize(1);
    assertThat(snapshots).hasSize(1);
    assertThat(events).hasSize(1);
    assertThat(metrics).containsExactly("submitted", "replayed");
  }

  @Test
  void theSameKeyForAnotherPostingIsRejected() {
    submit("clave-1");

    SubmitApplicationCommand other =
        new SubmitApplicationCommand(candidate, UUID.randomUUID(), "clave-1");
    assertThatThrownBy(() -> service.execute(other))
        .isInstanceOf(IdempotencyKeyReusedException.class);
  }

  @Test
  void aMalformedKeyIsAValidationError() {
    assertThatThrownBy(() -> new SubmitApplicationCommand(candidate, job, "con espacio"))
        .isInstanceOf(ValidationFailedException.class);
    assertThatThrownBy(() -> new SubmitApplicationCommand(candidate, job, ""))
        .isInstanceOf(ValidationFailedException.class);
    String tooLong = "k".repeat(256);
    assertThatThrownBy(() -> new SubmitApplicationCommand(candidate, job, tooLong))
        .isInstanceOf(ValidationFailedException.class);
    assertThat(new SubmitApplicationCommand(candidate, job, "k".repeat(255)).idempotencyKey())
        .hasSize(255);
  }

  private void assertNothingWritten(String outcome) {
    assertThat(store.rows).isEmpty();
    assertThat(store.history).isEmpty();
    assertThat(snapshots).isEmpty();
    assertThat(events).isEmpty();
    assertThat(metrics).containsExactly(outcome);
  }

  private static final class Store implements ApplicationStorePort {
    final Map<UUID, Application> rows = new HashMap<>();
    final List<StatusChange> history = new ArrayList<>();
    boolean open;
    boolean loseTheRace;

    @Override
    public boolean insertIfNoneOpen(Application application, StatusChange change) {
      if (loseTheRace) {
        return false;
      }
      rows.put(application.id().value(), application);
      history.add(change);
      return true;
    }

    @Override
    public boolean hasOpenApplication(UUID candidateProfileId, UUID jobPostingId) {
      return open;
    }

    @Override
    public Optional<Application> findById(ApplicationId id) {
      return Optional.ofNullable(rows.get(id.value()));
    }

    @Override
    public boolean updateIfVersion(
        Application application, StatusChange change, long expectedVersion) {
      throw new UnsupportedOperationException("postular no modifica postulaciones");
    }

    @Override
    public List<Application> findByCandidate(AccountId candidate) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<Application> findByJob(UUID jobPostingId, int limit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Map<ApplicationId, List<StatusChange>> historyOf(
        java.util.Collection<ApplicationId> ids) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class Keys implements IdempotencyKeysPort {
    final Map<String, String> claimed = new HashMap<>();
    final Map<String, String> responses = new HashMap<>();

    @Override
    public Optional<String> claim(AccountId account, String key, String requestHash) {
      String earlier = claimed.putIfAbsent(account + key, requestHash);
      if (earlier == null) {
        return Optional.empty();
      }
      if (!earlier.equals(requestHash)) {
        throw new IdempotencyKeyReusedException();
      }
      return Optional.of(responses.get(account + key));
    }

    @Override
    public void complete(AccountId account, String key, String response) {
      responses.put(account + key, response);
    }
  }
}
