package com.usac.jobseeker.ats.domain.model;

/**
 * La máquina de estados de la postulación (tdd/ats-tracker-modules.md §3.1). Esta tabla es **la
 * única fuente** de qué puede pasar: ni el caso de uso ni la base repiten la regla, y una prueba
 * exhaustiva recorre cada movimiento desde cada estado, cada etapa y cada iniciador.
 *
 * <pre>
 * CHANGE_STAGE  ACTIVE   → ACTIVE (otra etapa)    reclutador  (se avanza o se retrocede)
 * REJECT        ACTIVE   → REJECTED               reclutador  (con motivo, D5)
 * HIRE          ACTIVE   → HIRED                  reclutador  (desde cualquier etapa)
 * WITHDRAW      ACTIVE   → WITHDRAWN              candidato   (REQ-C-03)
 * REOPEN        REJECTED → ACTIVE (misma etapa)   reclutador  (D3, BR-TRACK-04)
 * </pre>
 *
 * <p>Postular no es un movimiento: crea la postulación ({@link Application#submit}). Que la vacante
 * de una reapertura no esté archivada lo comprueba el caso de uso, porque es un dato de `job`.
 */
public enum ApplicationMove {
  CHANGE_STAGE(ApplicationStatus.ACTIVE, ApplicationStatus.ACTIVE, ChangeInitiator.RECRUITER),
  REJECT(ApplicationStatus.ACTIVE, ApplicationStatus.REJECTED, ChangeInitiator.RECRUITER),
  HIRE(ApplicationStatus.ACTIVE, ApplicationStatus.HIRED, ChangeInitiator.RECRUITER),
  WITHDRAW(ApplicationStatus.ACTIVE, ApplicationStatus.WITHDRAWN, ChangeInitiator.CANDIDATE),
  REOPEN(ApplicationStatus.REJECTED, ApplicationStatus.ACTIVE, ChangeInitiator.RECRUITER);

  private final ApplicationStatus from;
  private final ApplicationStatus to;
  private final ChangeInitiator by;

  ApplicationMove(ApplicationStatus from, ApplicationStatus to, ChangeInitiator by) {
    this.from = from;
    this.to = to;
    this.by = by;
  }

  /** Si el movimiento sale de este estado y lo puede hacer este iniciador. */
  public boolean allows(ApplicationStatus current, ChangeInitiator initiator) {
    return from == current && by == initiator;
  }

  public ApplicationStatus target() {
    return to;
  }
}
