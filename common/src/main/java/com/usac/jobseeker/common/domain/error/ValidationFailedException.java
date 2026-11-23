package com.usac.jobseeker.common.domain.error;

import java.util.List;

/**
 * Los datos de una operación no cumplen las reglas del dominio. Reúne **todas** las violaciones, no
 * solo la primera, para que la interfaz las muestre juntas. No sabe de HTTP: `app` la traduce al
 * sobre estándar de error, así los módulos no dependen de la capa web.
 *
 * <p>El mensaje solo nombra campos, nunca valores: un valor puede ser un dato personal
 * (BR-DATA-01).
 */
public final class ValidationFailedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient List<FieldViolation> fieldViolations;

  public ValidationFailedException(List<FieldViolation> violations) {
    super(describe(violations));
    this.fieldViolations = List.copyOf(violations);
  }

  public List<FieldViolation> violations() {
    return fieldViolations;
  }

  private static String describe(List<FieldViolation> violations) {
    if (violations.isEmpty()) {
      throw new IllegalArgumentException("Debe haber al menos una violación");
    }
    return "Datos no válidos en: "
        + String.join(", ", violations.stream().map(FieldViolation::field).toList());
  }
}
