package com.usac.jobseeker.cv.domain.model;

import java.util.List;

/**
 * Las cuatro colecciones del perfil profesional, tal como se leen. Es un valor de lectura: cada
 * colección se edita por su propio caso de uso, elemento a elemento.
 */
public record ProfileCollections(
    List<Experience> experiences,
    List<Education> education,
    List<Certification> certifications,
    List<Project> projects) {

  /** Máximo de elementos por colección: acota el abuso y el tamaño de la respuesta. */
  public static final int MAX_PER_COLLECTION = 50;

  public ProfileCollections {
    experiences = List.copyOf(experiences);
    education = List.copyOf(education);
    certifications = List.copyOf(certifications);
    projects = List.copyOf(projects);
  }

  public static ProfileCollections empty() {
    return new ProfileCollections(List.of(), List.of(), List.of(), List.of());
  }
}
