package com.usac.jobseeker.matching.application.usecase.embedentity;

/**
 * El modelo de embeddings no pudo atender ahora (apagado, saturado, tiempo agotado): reintentar más
 * tarde puede funcionar. El trabajo vuelve a la cola con retroceso y se sirve el último score con
 * aviso, sin calcular con datos a medias (NFR-AVAIL-02).
 */
public final class EmbeddingUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public EmbeddingUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
