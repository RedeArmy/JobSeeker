package com.usac.jobseeker.job.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.error.InvalidStateException;
import com.usac.jobseeker.common.domain.error.InvalidTransitionException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.model.AccountId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JobPostingTest {

  private static final Instant T0 = Instant.parse("2026-11-24T10:00:00Z");
  private static final Instant T1 = T0.plusSeconds(60);
  private static final Instant T2 = T0.plusSeconds(120);
  private static final AccountId OWNER = new AccountId(UUID.randomUUID());

  private static JobDetails details(String title) {
    return JobDetails.of(title, "Descripción", WorkModality.REMOTE, "GT", null, null, null, null);
  }

  private static JobPosting draft() {
    return JobPosting.create(new JobPostingId(UUID.randomUUID()), OWNER, details("Backend"), T0);
  }

  private static JobPosting published() {
    return draft().transition(JobStatus.PUBLISHED, Initiator.OWNER, true, T1);
  }

  @Test
  void aNewPostingIsADraftThatAcceptsApplicationsAndIsHiring() {
    JobPosting posting = draft();

    assertThat(posting.status()).isEqualTo(JobStatus.DRAFT);
    assertThat(posting.acceptingApplications()).isTrue();
    assertThat(posting.recruitingStage()).isEqualTo(RecruitingStage.HIRING);
    assertThat(posting.version()).isEqualTo(JobPosting.FIRST_VERSION);
    assertThat(posting.publishedAt()).isNull();
  }

  @Test
  void everyChangeAdvancesTheVersionByOne() {
    JobPosting posting = draft();

    assertThat(posting.edit(details("Otro"), T1).version()).isEqualTo(2);
    assertThat(posting.touched(T1).version()).isEqualTo(2);
    assertThat(published().version()).isEqualTo(2);
  }

  @Test
  void publishingStampsTheFirstPublicationAndNeverMovesIt() {
    JobPosting reopened =
        published()
            .transition(JobStatus.CLOSED, Initiator.OWNER, true, T2)
            .transition(JobStatus.PUBLISHED, Initiator.OWNER, true, T2.plusSeconds(1));

    assertThat(reopened.publishedAt()).isEqualTo(T1);
  }

  // BR-JOB-02: sin una habilidad o formación el score no puede calcularse.
  @Test
  void publishingWithoutAStructuredRequirementIsRejectedAsValidation() {
    JobPosting posting = draft();

    assertThatThrownBy(() -> posting.transition(JobStatus.PUBLISHED, Initiator.OWNER, false, T1))
        .isInstanceOf(ValidationFailedException.class);
  }

  // BR-JOB-06: cerrar inicia el conteo del archivado; BR-JOB-07: reabrir lo cancela.
  @Test
  void closingStartsTheArchivalClockAndReopeningClearsIt() {
    JobPosting closed = published().transition(JobStatus.CLOSED, Initiator.OWNER, true, T2);
    JobPosting reopened = closed.transition(JobStatus.PUBLISHED, Initiator.OWNER, true, T2);

    assertThat(closed.closedAt()).isEqualTo(T2);
    assertThat(reopened.closedAt()).isNull();
  }

  @Test
  void archivingAndDeletingStampTheirMoments() {
    JobPosting archived =
        published()
            .transition(JobStatus.CLOSED, Initiator.OWNER, true, T2)
            .transition(JobStatus.ARCHIVED, Initiator.SYSTEM, true, T2);
    JobPosting deleted = archived.transition(JobStatus.DELETED, Initiator.OWNER, true, T2);

    assertThat(archived.archivedAt()).isEqualTo(T2);
    assertThat(deleted.deletedAt()).isEqualTo(T2);
    assertThat(deleted.status()).isEqualTo(JobStatus.DELETED);
  }

  @Test
  void anInvalidTransitionIsRejectedWithoutTouchingThePosting() {
    JobPosting posting = draft();

    assertThatThrownBy(() -> posting.transition(JobStatus.CLOSED, Initiator.OWNER, true, T1))
        .isInstanceOf(InvalidTransitionException.class);
    assertThatThrownBy(() -> posting.transition(JobStatus.ARCHIVED, Initiator.OWNER, true, T1))
        .isInstanceOf(InvalidTransitionException.class);
  }

  @Test
  void aDraftCanBeDiscardedStraightToDeleted() {
    assertThat(draft().transition(JobStatus.DELETED, Initiator.OWNER, false, T1).deletedAt())
        .isEqualTo(T1);
  }

  @Test
  void anArchivedOrDeletedPostingCannotBeEdited() {
    JobPosting archived =
        published()
            .transition(JobStatus.CLOSED, Initiator.OWNER, true, T2)
            .transition(JobStatus.ARCHIVED, Initiator.SYSTEM, true, T2);
    JobDetails other = details("Otro");

    assertThatThrownBy(() -> archived.edit(other, T2)).isInstanceOf(InvalidStateException.class);
    assertThatThrownBy(() -> archived.touched(T2)).isInstanceOf(InvalidStateException.class);
  }

  @Test
  void aClosedPostingCanStillBeEdited() {
    JobPosting closed = published().transition(JobStatus.CLOSED, Initiator.OWNER, true, T2);

    assertThat(closed.edit(details("Nuevo título"), T2).details().title())
        .isEqualTo("Nuevo título");
  }

  // BR-JOB-10 y BR-JOB-04: ejes independientes, con efecto solo con la vacante publicada.
  @Test
  void acceptingApplicationsAndStageOnlyApplyToAPublishedPosting() {
    JobPosting posting = published();

    assertThat(posting.withAcceptingApplications(false, T2).acceptingApplications()).isFalse();
    assertThat(posting.withRecruitingStage(RecruitingStage.REVIEWING, T2).recruitingStage())
        .isEqualTo(RecruitingStage.REVIEWING);
    assertThat(posting.withAcceptingApplications(false, T2).status())
        .isEqualTo(JobStatus.PUBLISHED);
    JobPosting aDraft = draft();

    assertThatThrownBy(() -> aDraft.withAcceptingApplications(false, T1))
        .isInstanceOf(InvalidStateException.class);
    assertThatThrownBy(() -> aDraft.withRecruitingStage(RecruitingStage.REVIEWING, T1))
        .isInstanceOf(InvalidStateException.class);
  }

  @Test
  void neitherAxisStartsTheArchivalClock() {
    JobPosting paused = published().withAcceptingApplications(false, T2);

    assertThat(paused.closedAt()).isNull();
  }

  @Test
  void onlyTheOwnerOwnsIt() {
    assertThat(draft().isOwnedBy(OWNER)).isTrue();
    assertThat(draft().isOwnedBy(new AccountId(UUID.randomUUID()))).isFalse();
  }

  @Test
  void aSavedPostingNeverHasAVersionBelowOne() {
    JobPosting posting = draft();
    JobPostingId id = posting.id();
    JobDetails details = posting.details();

    assertThatThrownBy(
            () ->
                new JobPosting(
                    id,
                    OWNER,
                    details,
                    JobStatus.DRAFT,
                    true,
                    RecruitingStage.HIRING,
                    0,
                    T0,
                    T0,
                    null,
                    null,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
