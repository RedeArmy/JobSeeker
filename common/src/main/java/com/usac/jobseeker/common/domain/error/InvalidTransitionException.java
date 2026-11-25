package com.usac.jobseeker.common.domain.error;

/**
 * La transición pedida no existe en la máquina de estados, o quien la pide no puede hacerla (por
 * ejemplo, archivar a mano). Es distinta de {@link StaleStateException}: reintentar tras volver a
 * leer no la arregla, porque el par `(origen, destino)` no es válido. No lleva datos del recurso.
 */
public final class InvalidTransitionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidTransitionException() {
    super("La transición no es válida desde el estado actual");
  }
}
