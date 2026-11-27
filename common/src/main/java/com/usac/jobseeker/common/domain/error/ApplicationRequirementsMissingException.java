package com.usac.jobseeker.common.domain.error;

import java.util.List;

/**
 * Al candidato le falta algo de lo que BR-CAND-01 exige para postular (nombre, contacto o CV).
 * Lleva qué falta, como códigos estables, para que la interfaz lo lleve a completarlo; nunca un
 * valor.
 */
public final class ApplicationRequirementsMissingException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient List<String> missingRequirements;

  public ApplicationRequirementsMissingException(List<String> missing) {
    super(describe(missing));
    this.missingRequirements = List.copyOf(missing);
  }

  // Se valida al construir el mensaje, antes de super(): una excepción sin requisitos no existe.
  private static String describe(List<String> missing) {
    if (missing.isEmpty()) {
      throw new IllegalArgumentException("Debe faltar al menos un requisito");
    }
    return "Faltan datos para postular: " + String.join(", ", missing);
  }

  public List<String> missing() {
    return missingRequirements;
  }
}
