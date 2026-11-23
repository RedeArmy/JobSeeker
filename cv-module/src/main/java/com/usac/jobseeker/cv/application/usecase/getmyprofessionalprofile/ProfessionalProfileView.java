package com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile;

import com.usac.jobseeker.cv.domain.model.ProfessionalProfile;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Lo que el titular ve de su perfil profesional. La versión viaja con él: es la que debe devolver
 * al guardar para que dos ediciones simultáneas no se pisen.
 */
public record ProfessionalProfileView(
    UUID id, String fullName, String headline, String bio, long version, Instant updatedAt) {

  public ProfessionalProfileView {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(fullName, "fullName");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }

  public static ProfessionalProfileView of(ProfessionalProfile profile) {
    return new ProfessionalProfileView(
        profile.id().value(),
        profile.identity().fullName(),
        profile.identity().headline(),
        profile.identity().bio(),
        profile.version(),
        profile.updatedAt());
  }
}
