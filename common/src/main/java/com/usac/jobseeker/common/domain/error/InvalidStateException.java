package com.usac.jobseeker.common.domain.error;

/**
 * La operación no aplica al estado actual del recurso (editar una vacante archivada, pausar las
 * postulaciones de una cerrada). El recurso existe y la versión es la vigente, así que no es un
 * {@link StaleStateException}; cambiar de estado primero, si procede, es lo que la habilita.
 */
public final class InvalidStateException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidStateException() {
    super("La operación no aplica al estado actual");
  }
}
