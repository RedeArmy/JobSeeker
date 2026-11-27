package com.usac.jobseeker.ats.application.usecase.submitapplication;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Postular a una vacante. El candidato sale del token, nunca del cuerpo.
 *
 * @param idempotencyKey la cabecera `Idempotency-Key`, opcional: con ella, repetir el envío
 *     devuelve la misma postulación (regla 5 del TDD); sin ella, el duplicado es un `409
 *     ALREADY_APPLIED`
 */
public record SubmitApplicationCommand(
    AccountId candidate, UUID jobPostingId, String idempotencyKey) {

  /**
   * Lo que admite la cabecera: ASCII visible y un tamaño acotado, como las claves de otras APIs.
   */
  private static final Pattern KEY_SHAPE = Pattern.compile("^[\\x21-\\x7E]{1,255}$");

  public SubmitApplicationCommand {
    Objects.requireNonNull(candidate, "candidate");
    Objects.requireNonNull(jobPostingId, "jobPostingId");
    if (idempotencyKey != null && !KEY_SHAPE.matcher(idempotencyKey).matches()) {
      throw new ValidationFailedException(
          List.of(new FieldViolation("Idempotency-Key", "INVALID_FORMAT")));
    }
  }
}
