package com.usac.jobseeker.cv.application.usecase.uploadcv;

/**
 * Límites del CV (ADR-0021 §4 y §5, decisiones R2 y R3): salen de configuración, no del código.
 *
 * @param maxBytes tamaño máximo de un CV
 * @param maxVersions versiones que una cuenta puede guardar
 */
public record CvFilePolicy(long maxBytes, int maxVersions) {
  public CvFilePolicy {
    if (maxBytes <= 0 || maxVersions <= 0) {
      throw new IllegalArgumentException("Los límites del CV deben ser positivos");
    }
  }
}
