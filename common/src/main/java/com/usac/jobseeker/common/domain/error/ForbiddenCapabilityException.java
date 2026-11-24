package com.usac.jobseeker.common.domain.error;

/**
 * La cuenta está autenticada pero no tiene habilitada la capacidad que la operación exige (por
 * ejemplo, una cuenta solo de reclutador pidiendo su perfil de candidato). Cada capacidad se
 * comporta como si fuera de una cuenta exclusiva de ese rol, sin atajos cruzados (BR-ACCT-04).
 *
 * <p>No lleva ningún dato: el mensaje no dice qué capacidad falta ni a qué recurso se quería
 * llegar.
 */
public final class ForbiddenCapabilityException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ForbiddenCapabilityException() {
    super("La cuenta no tiene la capacidad necesaria");
  }
}
