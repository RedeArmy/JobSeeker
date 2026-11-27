package com.usac.jobseeker.cv.application.usecase.checkapplicationreadiness;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Si la cuenta puede postular (BR-CAND-01) y con qué. Solo lleva identificadores, nunca el nombre
 * ni el contacto: `ats` no los necesita para crear la postulación (minimización, BR-DATA-01).
 *
 * @param missing lo que falta; vacío si puede postular
 * @param candidateProfileId el perfil de candidato al que se ata la postulación, si existe
 * @param currentCvFileId la versión del CV que se enviaría ahora; la postulación la conserva aunque
 *     después se suba otra (ADR-0021)
 */
public record ApplicationReadiness(
    Set<MissingRequirement> missing,
    Optional<UUID> candidateProfileId,
    Optional<UUID> currentCvFileId) {

  public ApplicationReadiness {
    Objects.requireNonNull(candidateProfileId, "candidateProfileId");
    Objects.requireNonNull(currentCvFileId, "currentCvFileId");
    missing = missing.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(missing));
    // Sin estos dos no hay con qué crear la postulación: «lista» no puede llegar sin ellos.
    if (missing.isEmpty() && (candidateProfileId.isEmpty() || currentCvFileId.isEmpty())) {
      throw new IllegalArgumentException("una cuenta lista para postular tiene perfil y CV");
    }
  }

  public boolean ready() {
    return missing.isEmpty();
  }
}
