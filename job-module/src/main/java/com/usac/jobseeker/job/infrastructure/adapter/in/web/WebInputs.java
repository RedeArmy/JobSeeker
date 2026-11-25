package com.usac.jobseeker.job.infrastructure.adapter.in.web;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.job.domain.model.JobPostingId;
import java.security.Principal;
import java.util.List;
import java.util.UUID;

/** Lo que toda ruta de la vacante hace con su entrada antes de llamar a un caso de uso. */
final class WebInputs {

  private WebInputs() {}

  /** La identidad sale del token, nunca de la ruta ni del cuerpo. */
  static AccountId caller(Principal principal) {
    // `sub` ya fue validado como UUID por el decodificador de la cadena (`SubjectValidator`).
    return new AccountId(UUID.fromString(principal.getName()));
  }

  /** La versión es obligatoria en toda escritura: sin ella no hay forma de saber qué se leyó. */
  static long version(Long value) {
    if (value == null) {
      throw new ValidationFailedException(List.of(new FieldViolation("version", "REQUIRED")));
    }
    return value;
  }

  /** Un identificador de vacante que no es un UUID no puede existir: se trata como inexistente. */
  static JobPostingId postingId(String raw) {
    try {
      return new JobPostingId(UUID.fromString(raw));
    } catch (IllegalArgumentException _) {
      throw new NotFoundException();
    }
  }

  /** Un campo obligatorio del cuerpo que no vino. */
  static <T> T required(String field, T value) {
    if (value == null) {
      throw new ValidationFailedException(List.of(new FieldViolation(field, "REQUIRED")));
    }
    return value;
  }
}
