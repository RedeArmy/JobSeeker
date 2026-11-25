package com.usac.jobseeker.job.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.error.PublishingDeniedException;
import com.usac.jobseeker.common.domain.error.PublishingDeniedException.Reason;
import com.usac.jobseeker.common.domain.model.RecruiterVerification;
import com.usac.jobseeker.job.application.support.JobPostingView;
import com.usac.jobseeker.job.application.support.PublishingGate;
import com.usac.jobseeker.job.application.support.PublishingPolicy;
import com.usac.jobseeker.job.application.usecase.transitionjobposting.TransitionJobPostingCommand;
import com.usac.jobseeker.job.application.usecase.transitionjobposting.TransitionJobPostingService;
import com.usac.jobseeker.job.domain.event.JobPostingEvents;
import com.usac.jobseeker.job.domain.model.Initiator;
import com.usac.jobseeker.job.domain.model.JobStatus;
import org.junit.jupiter.api.Test;

/**
 * La puerta de publicar (REQ-R-10, amenaza T-N1): sin verificar se publica poco, verificada más, y
 * suspendida nada. Crear y editar borradores sigue libre (decisión D1 del TDD de `job`).
 */
class PublishingGateTest {

  private final JobFixture f = new JobFixture();
  private final TransitionJobPostingService transitions =
      new TransitionJobPostingService(f.change, f.gate, f.clock());

  private JobPostingView draftWithASkill() {
    return JobStates.withSkill(f, f.draft());
  }

  private void publish(JobPostingView draft) {
    transitions.execute(
        new TransitionJobPostingCommand(
            f.owner, draft.posting().id(), JobStatus.PUBLISHED, draft.posting().version()));
  }

  // ---- política ----

  @Test
  void thePolicyGivesEachStandingItsOwnLimitAndNothingToTheSuspended() {
    PublishingPolicy policy = new PublishingPolicy(2, 100);

    assertThat(policy.limitFor(RecruiterVerification.UNVERIFIED)).isEqualTo(2);
    assertThat(policy.limitFor(RecruiterVerification.VERIFIED)).isEqualTo(100);
    assertThat(policy.limitFor(RecruiterVerification.SUSPENDED)).isZero();
  }

  @Test
  void aIncoherentPolicyIsRefusedAtStartup() {
    assertThatThrownBy(() -> new PublishingPolicy(-1, 10))
        .isInstanceOf(IllegalArgumentException.class);
    // Verificarse no puede empeorar la cuenta.
    assertThatThrownBy(() -> new PublishingPolicy(5, 4))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new PublishingPolicy(0, 0).limitFor(RecruiterVerification.UNVERIFIED)).isZero();
  }

  // ---- sin verificar ----

  @Test
  void anUnverifiedAccountPublishesUpToItsLimitAndThenIsToldToVerify() {
    f.standings.put(f.owner, RecruiterVerification.UNVERIFIED);
    JobPostingView first = draftWithASkill();
    JobPostingView second = draftWithASkill();

    publish(first);
    assertThatThrownBy(() -> publish(second))
        .isInstanceOfSatisfying(
            PublishingDeniedException.class,
            denied -> assertThat(denied.reason()).isEqualTo(Reason.NOT_VERIFIED));
    assertThat(f.store.findById(second.posting().id()).orElseThrow().status())
        .isEqualTo(JobStatus.DRAFT);
    assertThat(f.metrics).last().isEqualTo("transition_job_posting:denied_not_verified");
  }

  // Crear y editar borradores sigue libre: solo publicar cruza la puerta.
  @Test
  void anUnverifiedAccountStillCreatesAndEditsDraftsFreely() {
    f.standings.put(f.owner, RecruiterVerification.UNVERIFIED);

    for (int i = 0; i < 10; i++) {
      f.draft();
    }

    assertThat(f.store.postings).hasSize(10);
    assertThat(f.store.lockedFor).isEmpty();
  }

  // ---- verificada ----

  @Test
  void aVerifiedAccountGetsTheHigherLimitAndThenAQuotaExceeded() {
    f.standings.put(f.owner, RecruiterVerification.VERIFIED);
    for (int i = 0; i < 50; i++) {
      publish(draftWithASkill());
    }
    JobPostingView extra = draftWithASkill();

    assertThatThrownBy(() -> publish(extra))
        .isInstanceOfSatisfying(
            PublishingDeniedException.class,
            denied -> assertThat(denied.reason()).isEqualTo(Reason.QUOTA_EXCEEDED));
    assertThat(f.metrics).last().isEqualTo("transition_job_posting:denied_quota_exceeded");
  }

  // ---- suspendida ----

  @Test
  void aSuspendedAccountCannotPublishEvenWithRoomLeft() {
    f.standings.put(f.owner, RecruiterVerification.SUSPENDED);
    JobPostingView draft = draftWithASkill();

    assertThatThrownBy(() -> publish(draft))
        .isInstanceOfSatisfying(
            PublishingDeniedException.class,
            denied -> assertThat(denied.reason()).isEqualTo(Reason.SUSPENDED));
    assertThat(f.events).isEmpty();
    // Ni siquiera cuenta: no hay nada que contar si no se publica.
    assertThat(f.store.lockedFor).isEmpty();
  }

  // ---- el cupo ----

  // Cerrar libera el cupo: lo que cuenta es lo publicado ahora, no lo publicado alguna vez.
  @Test
  void closingAPostingFreesItsSlot() {
    f.standings.put(f.owner, RecruiterVerification.UNVERIFIED);
    JobPostingView published = JobStates.published(f);
    JobPostingView closed = JobStates.transition(f, published, JobStatus.CLOSED);
    JobPostingView next = draftWithASkill();

    publish(next);

    assertThat(f.store.findById(closed.posting().id()).orElseThrow().status())
        .isEqualTo(JobStatus.CLOSED);
    assertThat(f.eventTypes()).contains(JobPostingEvents.PUBLISHED);
  }

  // Reabrir una cerrada ocupa cupo otra vez: si no, cerrar y reabrir saltaría el tope.
  @Test
  void reopeningAClosedPostingGoesThroughTheSameLimit() {
    f.standings.put(f.owner, RecruiterVerification.VERIFIED);
    JobPostingView closed = JobStates.closed(f);
    for (int i = 0; i < 50; i++) {
      publish(draftWithASkill());
    }

    assertThatThrownBy(() -> JobStates.transition(f, closed, JobStatus.PUBLISHED))
        .isInstanceOf(PublishingDeniedException.class);
  }

  @Test
  void theCountIsPerAccountSoAnotherRecruitersPostingsDoNotCount() {
    f.standings.put(f.owner, RecruiterVerification.UNVERIFIED);
    publish(draftWithASkill());
    // Una vacante publicada de otro reclutador no ocupa el cupo de esta cuenta.
    var foreign =
        f.draft()
            .posting()
            .transition(JobStatus.PUBLISHED, Initiator.OWNER, true, f.clock().instant());
    f.store.postings.put(
        foreign.id(),
        new com.usac.jobseeker.job.domain.model.JobPosting(
            foreign.id(),
            f.stranger,
            foreign.details(),
            foreign.status(),
            foreign.acceptingApplications(),
            foreign.recruitingStage(),
            foreign.version(),
            foreign.createdAt(),
            foreign.updatedAt(),
            foreign.publishedAt(),
            foreign.closedAt(),
            foreign.archivedAt(),
            foreign.deletedAt()));

    assertThat(f.store.lockAndCountPublished(f.owner)).isOne();
    assertThat(f.store.lockAndCountPublished(f.stranger)).isOne();
  }

  // Una transición inválida o un dato inválido se dicen antes que el tope: no se revela el cupo.
  @Test
  void anInvalidPublishIsReportedBeforeTheLimit() {
    f.standings.put(f.owner, RecruiterVerification.SUSPENDED);
    JobPostingView withoutRequirements = f.draft();

    assertThatThrownBy(
            () ->
                transitions.execute(
                    new TransitionJobPostingCommand(
                        f.owner,
                        withoutRequirements.posting().id(),
                        JobStatus.PUBLISHED,
                        withoutRequirements.posting().version())))
        .isNotInstanceOf(PublishingDeniedException.class);
  }

  @Test
  void theGateTakesTheCountLockOnlyWhenPublishing() {
    f.standings.put(f.owner, RecruiterVerification.VERIFIED);
    JobPostingView published = JobStates.published(f);
    int before = f.store.lockedFor.size();

    JobStates.transition(f, published, JobStatus.CLOSED);

    assertThat(f.store.lockedFor).hasSize(before);
  }

  @Test
  void aGateNeedsItsCollaborators() {
    assertThatThrownBy(() -> new PublishingGate(null, f.store, f.policy))
        .isInstanceOf(NullPointerException.class);
  }
}
