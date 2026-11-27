package com.usac.jobseeker.cv.application.support;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import java.util.List;
import java.util.function.Supplier;

/**
 * Las reglas que comparten {@link ProfileChange} y {@link CandidateChange} (TD-013): las dos las
 * repetían letra por letra, y una corrección en una no llegaba a la otra. Lo propio de cada
 * agregado (capacidad, eventos, versión de cada perfil) sigue en su coordinador.
 */
public final class ChangeRules {

  private ChangeRules() {}

  /** Una versión negativa no existe: 0 es «sin crear» y las guardadas empiezan en 1. */
  public static void requireValidVersion(long version) {
    if (version < 0) {
      throw new ValidationFailedException(List.of(new FieldViolation("version", "INVALID")));
    }
  }

  /**
   * Construye un valor validándolo. Si no cumple las reglas se cuenta como `invalid` y se propaga
   * el error, antes de tocar la base de datos.
   */
  public static <T> T validated(UseCaseMetricsPort metrics, String useCase, Supplier<T> build) {
    try {
      return build.get();
    } catch (ValidationFailedException invalid) {
      metrics.count(useCase, "invalid");
      throw invalid;
    }
  }

  /** Cuenta el resultado y devuelve el error para lanzarlo en la misma línea. */
  public static <E extends RuntimeException> E failed(
      UseCaseMetricsPort metrics, String useCase, String outcome, E error) {
    metrics.count(useCase, outcome);
    return error;
  }
}
