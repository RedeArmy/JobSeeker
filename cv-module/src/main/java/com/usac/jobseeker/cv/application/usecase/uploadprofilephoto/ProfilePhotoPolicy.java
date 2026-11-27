package com.usac.jobseeker.cv.application.usecase.uploadprofilephoto;

/**
 * Límites de la foto (ADR-0021 §4, decisión R2): salen de configuración, no del código.
 *
 * @param maxBytes tamaño máximo de la foto subida, antes de re-codificarla
 */
public record ProfilePhotoPolicy(long maxBytes) {
  public ProfilePhotoPolicy {
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("El tamaño máximo de la foto debe ser positivo");
    }
  }
}
