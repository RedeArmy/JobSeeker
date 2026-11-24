package com.usac.jobseeker.cv.domain.model;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.time.Instant;
import java.util.Objects;

/**
 * El perfil de candidato de una cuenta (ADR-0008): lo propio de la capacidad de candidato sobre el
 * perfil profesional compartido. Uno por cuenta, creado la primera vez que se usa. Es inmutable:
 * editar produce una versión nueva.
 *
 * <p>La descubribilidad nace en `true` y la disponibilidad en `UNSET` (BR-DATA-03, BR-CAND-04);
 * editarlas es de sus propios casos de uso. `version` es la del bloqueo optimista: la primera de un
 * perfil guardado es 1 y el 0 se reserva para «aún no existe».
 */
public record CandidateProfile(
    CandidateProfileId id,
    AccountId accountId,
    CandidateDetails details,
    boolean discoverable,
    AvailabilityStatus availability,
    long version,
    Instant updatedAt) {

  public static final long FIRST_VERSION = 1;

  public CandidateProfile {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(details, "details");
    Objects.requireNonNull(availability, "availability");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (version < FIRST_VERSION) {
      throw new IllegalArgumentException("la versión de un perfil guardado empieza en 1");
    }
  }

  public static CandidateProfile create(
      CandidateProfileId id, AccountId accountId, CandidateDetails details, Instant now) {
    return new CandidateProfile(
        id, accountId, details, true, AvailabilityStatus.UNSET, FIRST_VERSION, now);
  }

  /** La siguiente versión con los datos nuevos; descubribilidad y disponibilidad no cambian. */
  public CandidateProfile edit(CandidateDetails newDetails, Instant now) {
    return new CandidateProfile(
        id, accountId, newDetails, discoverable, availability, version + 1, now);
  }

  /**
   * La siguiente versión sin cambiar los datos: la usa quien reemplaza un conjunto (BR-ACCT-06).
   */
  public CandidateProfile touched(Instant now) {
    return new CandidateProfile(
        id, accountId, details, discoverable, availability, version + 1, now);
  }
}
