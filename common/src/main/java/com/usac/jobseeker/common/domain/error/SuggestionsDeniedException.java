package com.usac.jobseeker.common.domain.error;

/**
 * Pedir candidatos sugeridos no está permitido a esta cuenta reclutadora por la confianza que se le
 * tiene o por lo que ya vio (REQ-R-10, REQ-R-11), no por el estado de la vacante ni por una versión
 * vieja: reintentar no lo arregla, hace falta que cambie la cuenta (verificarse) o que pase la
 * ventana de la cuota. Es la defensa central contra el raspado de datos de candidatos (amenaza
 * T-N1). No lleva datos de la cuenta, de la vacante ni de ningún candidato.
 */
public final class SuggestionsDeniedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Por qué se niega; decide el código HTTP y lo que el cliente puede hacer. */
  public enum Reason {
    /** Sin verificar: solo un operador puede cambiarlo (BR-ACCT-11). */
    NOT_VERIFIED,
    /** Se le retiró la confianza. */
    SUSPENDED,
    /** Verificada, pero ya vio los perfiles únicos que permite la ventana. */
    QUOTA_EXCEEDED
  }

  private final transient Reason deniedReason;

  public SuggestionsDeniedException(Reason reason) {
    super("La cuenta no puede ver candidatos sugeridos");
    this.deniedReason = reason;
  }

  public Reason reason() {
    return deniedReason;
  }
}
