package com.usac.jobseeker.common.domain.error;

import java.util.Objects;

/**
 * Un campo que no cumple una regla. Lleva el nombre del campo y un código estable en mayúsculas
 * (`REQUIRED`, `TOO_LONG`…), nunca el valor recibido: puede ser un dato personal (BR-DATA-01).
 */
public record FieldViolation(String field, String code) {

  public FieldViolation {
    Objects.requireNonNull(field, "field");
    Objects.requireNonNull(code, "code");
    if (field.isBlank() || code.isBlank()) {
      throw new IllegalArgumentException("field y code no pueden estar vacíos");
    }
  }
}
