package com.usac.jobseeker.matching.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * La copia inmutable del perfil canónico al postular (ADR-0017, decisión 9). El texto es el que se
 * habría embebido: sin nombre, contacto, enlaces ni instituciones, pero con el texto libre ya
 * redactado, que sigue siendo un dato derivado de la persona y por eso se anonimiza con la cuenta.
 *
 * @param applicationId identificador opaco de la postulación en `ats`; nulo si aún no existe
 */
public record ApplicationProfileSnapshot(
    UUID id,
    CandidateProfileId profile,
    JobPostingId job,
    UUID applicationId,
    String canonicalText,
    String canonicalHash,
    String algorithmVersion,
    Instant createdAt) {

  public ApplicationProfileSnapshot {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(job, "job");
    Objects.requireNonNull(canonicalText, "canonicalText");
    Objects.requireNonNull(canonicalHash, "canonicalHash");
    Objects.requireNonNull(algorithmVersion, "algorithmVersion");
    Objects.requireNonNull(createdAt, "createdAt");
  }
}
