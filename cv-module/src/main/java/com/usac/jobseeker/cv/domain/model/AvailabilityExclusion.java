package com.usac.jobseeker.cv.domain.model;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.model.AccountId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Una cuenta a la que el candidato oculta su estado `OPEN_TO_WORK` (BR-CAND-05): se le muestra como
 * `UNSET` (BR-VIS-06). Es por identidad de cuenta y de cualquier rol, no por empresa. Nunca limita
 * que esa cuenta vea el resto del perfil ni que el candidato postule (BR-CAND-06).
 */
public record AvailabilityExclusion(AccountId excludedAccount, Instant createdAt) {

  /** Acota el abuso y el tamaño de la lista; el diseño no fijaba un tope. */
  public static final int MAX_PER_CANDIDATE = 500;

  public AvailabilityExclusion {
    Objects.requireNonNull(excludedAccount, "excludedAccount");
    Objects.requireNonNull(createdAt, "createdAt");
  }

  /** Nadie se excluye a sí mismo: ocultarse el estado a uno mismo no tendría efecto alguno. */
  public static AvailabilityExclusion of(AccountId owner, AccountId excluded, Instant now) {
    if (owner.equals(excluded)) {
      throw new ValidationFailedException(
          List.of(new FieldViolation("accountId", "SELF_EXCLUSION")));
    }
    return new AvailabilityExclusion(excluded, now);
  }
}
