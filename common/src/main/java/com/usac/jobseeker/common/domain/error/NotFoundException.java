package com.usac.jobseeker.common.domain.error;

/**
 * El recurso pedido no existe **para quien lo pide**. Cubre dos casos que desde fuera deben verse
 * igual: no existe, o existe pero no es suyo. Distinguirlos confirmaría a un tercero que un
 * identificador ajeno es válido.
 *
 * <p>No lleva el identificador ni ningún dato del recurso: puede ser personal (BR-DATA-01).
 */
public final class NotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public NotFoundException() {
    super("El recurso no existe");
  }
}
