package com.usac.jobseeker.ats.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.ats.domain.event.ApplicationEvents;
import com.usac.jobseeker.common.domain.error.InvalidTransitionException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.model.AccountId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Postular y las reglas de cada movimiento que la tabla sola no expresa. */
class ApplicationTest {

  private static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");
  private static final Instant LATER = NOW.plusSeconds(60);

  private final ApplicationId id = new ApplicationId(UUID.randomUUID());
  private final UUID profile = UUID.randomUUID();
  private final AccountId account = new AccountId(UUID.randomUUID());
  private final UUID job = UUID.randomUUID();
  private final UUID cv = UUID.randomUUID();

  private Application.Transition submitted() {
    return Application.submit(id, profile, account, job, cv, UUID.randomUUID(), NOW);
  }

  @Test
  void submittingStartsActiveInTheFirstColumnWithAnOriginlessHistory() {
    Application.Transition transition = submitted();

    assertThat(transition.application())
        .isEqualTo(
            new Application(
                id,
                profile,
                account,
                job,
                cv,
                ApplicationStatus.ACTIVE,
                PipelineStage.NEW,
                null,
                1,
                NOW,
                NOW));
    StatusChange change = transition.change();
    assertThat(change.fromStatus()).isNull();
    assertThat(change.fromStage()).isNull();
    assertThat(change.toStatus()).isEqualTo(ApplicationStatus.ACTIVE);
    assertThat(change.toStage()).isEqualTo(PipelineStage.NEW);
    assertThat(change.initiator()).isEqualTo(ChangeInitiator.CANDIDATE);
    assertThat(change.applicationId()).isEqualTo(id);
  }

  @Test
  void rejectingWithoutAReasonIsAValidationError() {
    Application active = submitted().application();
    UUID changeId = UUID.randomUUID();

    assertThatThrownBy(() -> active.reject(null, ChangeInitiator.RECRUITER, changeId, LATER))
        .isInstanceOfSatisfying(
            ValidationFailedException.class,
            e -> assertThat(e.violations()).extracting("field").containsExactly("reason"));
  }

  // D3: el que ya había pasado las pruebas vuelve a la misma columna, sin el motivo, que queda en
  // el historial del descarte.
  @Test
  void reopeningReturnsToTheColumnItHadAndClearsTheReason() {
    Application atOffer =
        submitted()
            .application()
            .changeStage(PipelineStage.OFFER, ChangeInitiator.RECRUITER, UUID.randomUUID(), NOW)
            .application();
    Application.Transition rejected =
        atOffer.reject(
            RejectionReason.CANDIDATE_UNRESPONSIVE,
            ChangeInitiator.RECRUITER,
            UUID.randomUUID(),
            NOW);

    Application.Transition reopened =
        rejected.application().reopen(ChangeInitiator.RECRUITER, UUID.randomUUID(), LATER);

    assertThat(rejected.change().rejectionReason())
        .isEqualTo(RejectionReason.CANDIDATE_UNRESPONSIVE);
    assertThat(reopened.application().status()).isEqualTo(ApplicationStatus.ACTIVE);
    assertThat(reopened.application().stage()).isEqualTo(PipelineStage.OFFER);
    assertThat(reopened.application().rejectionReason()).isNull();
    assertThat(reopened.application().version()).isEqualTo(4);
    assertThat(reopened.change().fromStatus()).isEqualTo(ApplicationStatus.REJECTED);
    assertThat(reopened.change().rejectionReason()).isNull();
  }

  @Test
  void stayingInTheSameColumnIsNotAChange() {
    Application active = submitted().application();
    UUID changeId = UUID.randomUUID();

    assertThatThrownBy(
            () -> active.changeStage(PipelineStage.NEW, ChangeInitiator.RECRUITER, changeId, LATER))
        .isInstanceOf(InvalidTransitionException.class);
  }

  @Test
  void aHireIsNeverReopened() {
    Application hired =
        submitted()
            .application()
            .hire(ChangeInitiator.RECRUITER, UUID.randomUUID(), LATER)
            .application();
    UUID changeId = UUID.randomUUID();

    assertThatThrownBy(() -> hired.reopen(ChangeInitiator.RECRUITER, changeId, LATER))
        .isInstanceOf(InvalidTransitionException.class);
  }

  @Test
  void onlyARejectedApplicationCarriesAReason() {
    UUID changeId = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                new Application(
                    id,
                    profile,
                    account,
                    job,
                    cv,
                    ApplicationStatus.ACTIVE,
                    PipelineStage.NEW,
                    RejectionReason.OTHER,
                    1,
                    NOW,
                    NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new Application(
                    id,
                    profile,
                    account,
                    job,
                    cv,
                    ApplicationStatus.REJECTED,
                    PipelineStage.NEW,
                    null,
                    1,
                    NOW,
                    NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new StatusChange(
                    changeId,
                    id,
                    null,
                    PipelineStage.NEW,
                    ApplicationStatus.ACTIVE,
                    PipelineStage.NEW,
                    ChangeInitiator.CANDIDATE,
                    null,
                    NOW))
        .as("el origen viene completo o no viene")
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theVersionStartsAtOne() {
    assertThatThrownBy(
            () ->
                new Application(
                    id,
                    profile,
                    account,
                    job,
                    cv,
                    ApplicationStatus.ACTIVE,
                    PipelineStage.NEW,
                    null,
                    0,
                    NOW,
                    NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // BR-DATA-01 y BR-TRACK-03: el evento lleva identificadores y lo público, nunca el motivo
  // interno.
  @Test
  void theEventCarriesOnlyIdentifiersAndThePublicState() {
    Application rejected =
        submitted()
            .application()
            .reject(RejectionReason.NOT_A_FIT, ChangeInitiator.RECRUITER, UUID.randomUUID(), LATER)
            .application();

    String payload = ApplicationEvents.payload(rejected);

    assertThat(payload)
        .isEqualTo(
            "{\"applicationId\":\""
                + id
                + "\",\"jobPostingId\":\""
                + job
                + "\",\"version\":2,\"status\":\"REJECTED\",\"stage\":\"NEW\"}")
        .doesNotContain("NOT_A_FIT")
        .doesNotContain(account.value().toString())
        .doesNotContain(cv.toString());
  }
}
