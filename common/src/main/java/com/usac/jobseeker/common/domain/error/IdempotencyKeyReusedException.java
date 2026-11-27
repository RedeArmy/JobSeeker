package com.usac.jobseeker.common.domain.error;

/**
 * La misma `Idempotency-Key` llegó con otra petición (otra vacante, otra operación). No se repite
 * nada ni se devuelve la respuesta guardada: es un error del cliente, que debe usar otra clave.
 */
public final class IdempotencyKeyReusedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public IdempotencyKeyReusedException() {
    super("La clave de idempotencia ya se usó con otra petición");
  }
}
