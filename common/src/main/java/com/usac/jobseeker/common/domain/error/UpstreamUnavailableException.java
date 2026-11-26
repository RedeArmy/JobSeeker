package com.usac.jobseeker.common.domain.error;

import java.util.Objects;

/**
 * Un servicio del que depende la operación no responde (almacén de archivos, antivirus). `app` lo
 * traduce a `503 UPSTREAM_UNAVAILABLE`: el resto del sistema sigue funcionando y quien llama puede
 * reintentar más tarde (`02-hld-system-design.md` §9). La operación nunca se completa a medias por
 * esto: subir un archivo sin escanear, por ejemplo, está prohibido (ADR-0021 §7).
 */
public final class UpstreamUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String unavailableDependency;

  /**
   * @param dependency nombre estable del servicio, en minúsculas (`storage`, `malware_scan`); va a
   *     los logs, nunca un dato de la petición
   */
  public UpstreamUnavailableException(String dependency, Throwable cause) {
    super("Servicio no disponible: " + Objects.requireNonNull(dependency, "dependency"), cause);
    this.unavailableDependency = dependency;
  }

  public String dependency() {
    return unavailableDependency;
  }
}
