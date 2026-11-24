package com.usac.jobseeker.common.domain.error;

/**
 * Lo que se quiso crear ya existe (clave natural repetida). No es un error de validación del dato
 * ni de versión: el cliente ya tiene lo que pedía y no debe reintentar con otro cuerpo.
 */
public final class AlreadyExistsException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public AlreadyExistsException() {
    super("El recurso ya existe");
  }
}
