package com.usac.jobseeker.common.domain.error;

/**
 * No se puede postular a esta vacante por una regla de la postulación, no por un dato inválido ni
 * por el estado de la vacante: reintentar no lo arregla. No lleva datos del candidato ni de la
 * vacante (BR-DATA-01).
 */
public final class ApplicationDeniedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Por qué se niega; decide el código HTTP. */
  public enum Reason {
    /** El reclutador quiere postular a su propia vacante (BR-ACCT-05). */
    SELF_APPLICATION,
    /**
     * Ya tiene una postulación a esta vacante que no retiró: en proceso, descartada o contratada
     * (BR-TRACK-01, BR-TRACK-05).
     */
    ALREADY_APPLIED
  }

  private final transient Reason deniedReason;

  public ApplicationDeniedException(Reason reason) {
    super("No se puede postular a esta vacante");
    this.deniedReason = reason;
  }

  public Reason reason() {
    return deniedReason;
  }
}
