package com.usac.jobseeker.cv.domain.model;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.time.Instant;
import java.util.Objects;

/**
 * El perfil profesional compartido de una cuenta (ADR-0008): uno por cuenta, creado la primera vez
 * que se usa y no al registrarse (BR-ACCT-09). Es inmutable: editar produce una versión nueva.
 *
 * <p>`version` es la del bloqueo optimista. La primera versión de un perfil guardado es 1; el 0 se
 * reserva para «aún no existe», que es lo que el cliente envía al crearlo.
 */
public record ProfessionalProfile(
    ProfessionalProfileId id,
    AccountId accountId,
    ProfessionalIdentity identity,
    long version,
    Instant updatedAt) {

  public static final long FIRST_VERSION = 1;

  public ProfessionalProfile {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (version < FIRST_VERSION) {
      throw new IllegalArgumentException("la versión de un perfil guardado empieza en 1");
    }
  }

  public static ProfessionalProfile create(
      ProfessionalProfileId id, AccountId accountId, ProfessionalIdentity identity, Instant now) {
    return new ProfessionalProfile(id, accountId, identity, FIRST_VERSION, now);
  }

  /** La siguiente versión del perfil con la identidad nueva; el original no cambia. */
  public ProfessionalProfile edit(ProfessionalIdentity newIdentity, Instant now) {
    return new ProfessionalProfile(id, accountId, newIdentity, version + 1, now);
  }
}
