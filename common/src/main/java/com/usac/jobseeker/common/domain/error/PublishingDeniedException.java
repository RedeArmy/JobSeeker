package com.usac.jobseeker.common.domain.error;

/**
 * Publicar una vacante no está permitido a esta cuenta reclutadora por la confianza que se le tiene
 * (REQ-R-10), no por el estado de la vacante ni por una versión vieja: reintentar tras volver a
 * leer no lo arregla, hace falta que cambie la cuenta (verificarse) o que libere una vacante. No
 * lleva datos de la cuenta ni de la vacante.
 */
public final class PublishingDeniedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Por qué se niega; decide el código HTTP y lo que el cliente puede hacer. */
  public enum Reason {
    /** Sin verificar y ya en su tope: verificarse lo sube. */
    NOT_VERIFIED,
    /** Se le retiró la confianza: no publica ni reabre. */
    SUSPENDED,
    /** Verificada y ya en su tope: tiene que cerrar alguna publicada. */
    QUOTA_EXCEEDED
  }

  private final transient Reason deniedReason;

  public PublishingDeniedException(Reason reason) {
    super("La cuenta no puede publicar más vacantes");
    this.deniedReason = reason;
  }

  public Reason reason() {
    return deniedReason;
  }
}
