package com.usac.jobseeker.cv.application.usecase.getmycandidateprofile;

import com.usac.jobseeker.cv.domain.model.AvailabilityStatus;
import com.usac.jobseeker.cv.domain.model.CandidateCollections;
import com.usac.jobseeker.cv.domain.model.CandidateDetails;
import com.usac.jobseeker.cv.domain.model.CandidateProfile;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Lo que el candidato ve de su propio perfil, con sus tres conjuntos. La versión viaja con él: es
 * la que debe devolver al guardar, sea el contacto o un conjunto, para que dos ediciones
 * simultáneas no se pisen. Contiene datos personales (el contacto): solo se entrega a su titular
 * (BR-DATA-01).
 */
public record CandidateProfileView(
    UUID id,
    CandidateDetails details,
    boolean discoverable,
    AvailabilityStatus availability,
    long version,
    Instant updatedAt,
    CandidateCollections collections) {

  public CandidateProfileView {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(details, "details");
    Objects.requireNonNull(availability, "availability");
    Objects.requireNonNull(updatedAt, "updatedAt");
    Objects.requireNonNull(collections, "collections");
  }

  public static CandidateProfileView of(
      CandidateProfile profile, CandidateCollections collections) {
    return new CandidateProfileView(
        profile.id().value(),
        profile.details(),
        profile.discoverable(),
        profile.availability(),
        profile.version(),
        profile.updatedAt(),
        collections);
  }
}
