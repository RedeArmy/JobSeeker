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

  /** Construye el identificador desde el `sub` del token; falla si no es un UUID canónico. */
  public static AccountId fromSubject(String subject) {
    UUID parsed = UUID.fromString(Objects.requireNonNull(subject, "subject"));
    // `UUID.fromString` es permisivo y acepta `1-1-1-1-1`: una identidad no puede depender de ese
    // relleno, así que solo vale la forma canónica de 36 caracteres que emite Clavaris.
    if (!parsed.toString().equalsIgnoreCase(subject)) {
      throw new IllegalArgumentException("el sub no es un UUID canónico");
    }
    return new AccountId(parsed);
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
