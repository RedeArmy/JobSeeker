package com.usac.jobseeker.job.domain.model;

import java.util.Map;
import java.util.Set;

/**
 * La máquina de estados de la vacante (BR-JOB-03, BR-JOB-06 a 09 y 11). La tabla de transiciones es
 * **la única fuente** de qué puede pasar: ni el caso de uso ni la base repiten la regla, y una
 * prueba exhaustiva recorre todos los pares posibles.
 *
 * <pre>
 * DRAFT → PUBLISHED, DELETED          (dueño)
 * PUBLISHED → CLOSED                  (dueño)
 * CLOSED → PUBLISHED                  (dueño; reabrir cancela el conteo, BR-JOB-07)
 * CLOSED → ARCHIVED                   (solo el sistema, BR-JOB-14)
 * ARCHIVED → DELETED                  (dueño; borrado lógico, BR-JOB-09)
 * DELETED                             (terminal)
 * </pre>
 */
public enum JobStatus {
  DRAFT,
  PUBLISHED,
  CLOSED,
  ARCHIVED,
  DELETED;

  private static final Map<JobStatus, Map<JobStatus, Initiator>> TRANSITIONS =
      Map.of(
          DRAFT, Map.of(PUBLISHED, Initiator.OWNER, DELETED, Initiator.OWNER),
          PUBLISHED, Map.of(CLOSED, Initiator.OWNER),
          CLOSED, Map.of(PUBLISHED, Initiator.OWNER, ARCHIVED, Initiator.SYSTEM),
          ARCHIVED, Map.of(DELETED, Initiator.OWNER),
          DELETED, Map.of());

  /** Los estados que ven los candidatos en el listado general (BR-JOB-11). */
  private static final Set<JobStatus> VISIBLE_TO_CANDIDATES = Set.of(PUBLISHED, CLOSED);

  /** Si el par `(this, to)` existe y lo puede disparar `by`. */
  public boolean canMoveTo(JobStatus to, Initiator by) {
    return TRANSITIONS.get(this).get(to) == by;
  }

  /**
   * Si el par existe, con independencia de quién lo dispare: distingue «no existe» de «no puedes».
   */
  public boolean isKnownTransition(JobStatus to) {
    return TRANSITIONS.get(this).containsKey(to);
  }

  /** Si un candidato puede ver la vacante en este estado (BR-JOB-11). */
  public boolean isVisibleToCandidates() {
    return VISIBLE_TO_CANDIDATES.contains(this);
  }

  /** Si el dueño puede editar los datos y los requisitos: no así archivada ni eliminada. */
  public boolean isEditable() {
    return this == DRAFT || this == PUBLISHED || this == CLOSED;
  }
}
