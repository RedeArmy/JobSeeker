package com.usac.jobseeker.matching.application.usecase.embedentity;

/**
 * El modelo respondió, pero lo que devolvió o rechazó no mejorará reintentando: texto fuera del
 * contexto, modelo inexistente, dimensión distinta de la de la columna. El trabajo pasa a FAILED
 * para revisión en vez de gastar intentos.
 */
public final class EmbeddingRejectedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public EmbeddingRejectedException(String message) {
    super(message);
  }
}
