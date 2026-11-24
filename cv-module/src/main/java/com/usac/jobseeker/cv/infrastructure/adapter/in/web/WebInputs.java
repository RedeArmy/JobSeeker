package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.model.AccountId;
import java.security.Principal;
import java.util.List;
import java.util.UUID;

/**
 * Lo que toda ruta de candidato hace con su entrada antes de llamar a un caso de uso, escrito una
 * vez para que la API JSON y las pantallas no lo copien y diverjan.
 */
final class WebInputs {

  private WebInputs() {}

  /** La identidad sale de la sesión o el token, nunca de la ruta ni del cuerpo. */
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

  /** Un identificador que no es un UUID es un dato inválido, no un recurso inexistente. */
  static AccountId accountOf(String raw) {
    try {
      return new AccountId(UUID.fromString(raw == null ? "" : raw.strip()));
    } catch (IllegalArgumentException _) {
      throw new ValidationFailedException(List.of(new FieldViolation("accountId", "INVALID")));
    }
  }
}
