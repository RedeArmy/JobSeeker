package com.usac.jobseeker.common.domain.error;

/**
 * La operación se basó en una versión que ya no es la vigente: otra pestaña o dispositivo guardó
 * antes (bloqueo optimista). Quien la recibe debe volver a leer y reintentar; sobrescribir en
 * silencio perdería el cambio ajeno.
 */
public final class StaleStateException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public StaleStateException() {
    super("El recurso cambió desde que se leyó");
  }
}
