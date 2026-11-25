package com.usac.jobseeker.job.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * La regla más fácil de romper sin darse cuenta (TDD de job §9): se recorren **todos** los pares
 * `(origen, destino, quién)` y solo los de la tabla de §3.1 pasan.
 */
class JobStatusTest {

  private record Move(JobStatus from, JobStatus to, Initiator by) {}

  private static final List<Move> ALLOWED =
      List.of(
          new Move(JobStatus.DRAFT, JobStatus.PUBLISHED, Initiator.OWNER),
          new Move(JobStatus.DRAFT, JobStatus.DELETED, Initiator.OWNER),
          new Move(JobStatus.PUBLISHED, JobStatus.CLOSED, Initiator.OWNER),
          new Move(JobStatus.CLOSED, JobStatus.PUBLISHED, Initiator.OWNER),
          new Move(JobStatus.CLOSED, JobStatus.ARCHIVED, Initiator.SYSTEM),
          new Move(JobStatus.ARCHIVED, JobStatus.DELETED, Initiator.OWNER));

  @Test
  void onlyTheDocumentedMovesAreAllowedAcrossEveryPairAndInitiator() {
    List<Move> allowed = new ArrayList<>();
    for (JobStatus from : JobStatus.values()) {
      for (JobStatus to : JobStatus.values()) {
        for (Initiator by : Initiator.values()) {
          if (from.canMoveTo(to, by)) {
            allowed.add(new Move(from, to, by));
          }
        }
      }
    }

    assertThat(allowed).containsExactlyInAnyOrderElementsOf(ALLOWED);
  }

  // BR-JOB-14: el archivado es exclusivamente automático; el dueño no puede hacerlo a mano.
  @Test
  void theOwnerCannotArchiveByHandAndTheSystemCannotPublish() {
    assertThat(JobStatus.CLOSED.canMoveTo(JobStatus.ARCHIVED, Initiator.OWNER)).isFalse();
    assertThat(JobStatus.DRAFT.canMoveTo(JobStatus.PUBLISHED, Initiator.SYSTEM)).isFalse();
  }

  @Test
  void deletedIsTerminal() {
    for (JobStatus to : JobStatus.values()) {
      assertThat(JobStatus.DELETED.isKnownTransition(to)).isFalse();
    }
  }

  @Test
  void aSelfTransitionNeverExists() {
    for (JobStatus status : JobStatus.values()) {
      assertThat(status.isKnownTransition(status)).isFalse();
    }
  }

  // BR-JOB-08: archivada ya no se reabre; la única salida es eliminarla.
  @Test
  void anArchivedPostingCanOnlyBeDeleted() {
    for (JobStatus to : JobStatus.values()) {
      assertThat(JobStatus.ARCHIVED.isKnownTransition(to)).isEqualTo(to == JobStatus.DELETED);
    }
  }

  @Test
  void knownTransitionIgnoresWhoTriggersIt() {
    assertThat(JobStatus.CLOSED.isKnownTransition(JobStatus.ARCHIVED)).isTrue();
  }

  // BR-JOB-11: solo publicadas y cerradas aparecen en el listado de candidatos.
  @Test
  void onlyPublishedAndClosedAreVisibleToCandidates() {
    Map<JobStatus, Boolean> expected =
        Map.of(
            JobStatus.DRAFT, false,
            JobStatus.PUBLISHED, true,
            JobStatus.CLOSED, true,
            JobStatus.ARCHIVED, false,
            JobStatus.DELETED, false);

    expected.forEach(
        (status, visible) -> assertThat(status.isVisibleToCandidates()).isEqualTo(visible));
  }

  @Test
  void onlyDraftPublishedAndClosedCanBeEdited() {
    Map<JobStatus, Boolean> expected =
        Map.of(
            JobStatus.DRAFT, true,
            JobStatus.PUBLISHED, true,
            JobStatus.CLOSED, true,
            JobStatus.ARCHIVED, false,
            JobStatus.DELETED, false);

    expected.forEach((status, editable) -> assertThat(status.isEditable()).isEqualTo(editable));
  }
}
