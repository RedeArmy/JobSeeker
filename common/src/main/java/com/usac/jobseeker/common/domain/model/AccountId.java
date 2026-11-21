package com.usac.jobseeker.common.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Identificador de cuenta. Es el `sub` del token de Clavaris, no un identificador generado aquí
 * (data-model.md §4.1): JobSeeker nunca asigna el suyo.
 */
public record AccountId(UUID value) {

  public AccountId {
    Objects.requireNonNull(value, "value");
  }

  /** Construye el identificador desde el `sub` del token; falla si no es un UUID. */
  public static AccountId fromSubject(String subject) {
    return new AccountId(UUID.fromString(Objects.requireNonNull(subject, "subject")));
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
