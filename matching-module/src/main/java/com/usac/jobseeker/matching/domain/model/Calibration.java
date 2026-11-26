package com.usac.jobseeker.matching.domain.model;

import java.util.Objects;

/**
 * Lleva el coseno crudo a [0, 1] con una función monótona (ADR-0017, decisión 5). El coseno de un
 * modelo de embeddings no es un porcentaje: sus valores reales se concentran en una banda estrecha
 * y mostrarlos tal cual engañaría. Los parámetros son **por modelo** y salen de pares etiquetados
 * (sigmoide de Platt); hasta que el protocolo de 300 pares los produzca no hay calibración, el
 * puntaje se marca **no calibrado** y no sirve para medir la precisión.
 *
 * @param model el modelo de embeddings al que pertenecen los parámetros (ADR-0018)
 * @param kind sin calibrar, o sigmoide de Platt
 * @param a pendiente de Platt; debe ser positiva para que la función sea monótona
 * @param b desplazamiento de Platt
 */
public record Calibration(String model, Kind kind, double a, double b) {

  public enum Kind {
    UNCALIBRATED,
    PLATT
  }

  public Calibration {
    Objects.requireNonNull(model, "model");
    Objects.requireNonNull(kind, "kind");
    if (kind == Kind.PLATT && !(a > 0 && Double.isFinite(a) && Double.isFinite(b))) {
      throw new IllegalArgumentException("Platt necesita una pendiente positiva y finita");
    }
  }

  /**
   * Sin parámetros ajustados: se usa el coseno acotado a [0, 1] y el puntaje se marca no calibrado.
   */
  public static Calibration uncalibrated(String model) {
    return new Calibration(model, Kind.UNCALIBRATED, 0, 0);
  }

  public boolean calibrated() {
    return kind == Kind.PLATT;
  }

  public double apply(double cosine) {
    if (Double.isNaN(cosine)) {
      throw new IllegalArgumentException("el coseno no es un número");
    }
    if (kind == Kind.UNCALIBRATED) {
      return Math.clamp(cosine, 0.0, 1.0);
    }
    return 1.0 / (1.0 + Math.exp(-(a * cosine + b)));
  }

  public String hash() {
    return Weights.sha256(model + "|" + kind + "|" + a + "|" + b);
  }
}
