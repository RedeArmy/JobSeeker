package com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile;

import com.usac.jobseeker.cv.domain.model.ProfessionalProfile;
import com.usac.jobseeker.cv.domain.model.ProfileCollections;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Lo que el titular ve de su perfil profesional. La versión viaja con él: es la que debe devolver
 * al guardar para que dos ediciones simultáneas no se pisen.
 */
public record ProfessionalProfileView(
    UUID id,
    String fullName,
    String headline,
    String bio,
    long version,
    Instant updatedAt,
    ProfileCollections collections) {

  public ProfessionalProfileView {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(fullName, "fullName");
    Objects.requireNonNull(updatedAt, "updatedAt");
    Objects.requireNonNull(collections, "collections");
  }

  public static ProfessionalProfileView of(
      ProfessionalProfile profile, ProfileCollections collections) {
    return new ProfessionalProfileView(
        profile.id().value(),
        profile.identity().fullName(),
        profile.identity().headline(),
        profile.identity().bio(),
        profile.version(),
        profile.updatedAt(),
        collections);
  }
}
