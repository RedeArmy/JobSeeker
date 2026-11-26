package com.usac.jobseeker.matching.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * Los parámetros del puntaje que se calibran con datos y por tanto nunca se codifican
 * (BR-MATCH-05): el peso de cada dimensión estructurada, `α` (cuánto pesa la afinidad semántica),
 * la completitud mínima para entrar a las listas de sugeridos y cuánto más pesa un requisito
 * obligatorio. Su *hash* forma parte de `algorithm_version` (ADR-0017, decisión 8): cambiarlos
 * cambia la versión.
 *
 * @param structured peso de cada dimensión; se renormalizan entre las disponibles, así que solo
 *     importa su proporción
 * @param alpha peso de la afinidad semántica calibrada frente a la capa estructurada, en [0, 1]
 * @param minCompleteness completitud mínima para sugerir a un candidato, en [0, 1] (ADR-0017, R1)
 * @param mandatoryFactor veces que pesa un requisito obligatorio frente a uno deseable, ≥ 1
 */
public record Weights(
    Map<Dimension, Double> structured,
    double alpha,
    double minCompleteness,
    double mandatoryFactor) {

  public Weights {
    EnumMap<Dimension, Double> copy = new EnumMap<>(Dimension.class);
    double sum = 0;
    for (Dimension dimension : Dimension.values()) {
      Double weight = structured.get(dimension);
      if (weight == null || Double.isNaN(weight) || Double.isInfinite(weight) || weight < 0) {
        throw new IllegalArgumentException("falta el peso de " + dimension + " o no es válido");
      }
      copy.put(dimension, weight);
      sum += weight;
    }
    if (sum <= 0) {
      throw new IllegalArgumentException("los pesos no pueden sumar cero");
    }
    // Un NaN no es menor ni mayor que nada: se rechaza aparte o pasaría la comprobación de rango.
    if (Double.isNaN(alpha) || alpha < 0 || alpha > 1) {
      throw new IllegalArgumentException("alpha debe estar entre 0 y 1");
    }
    if (Double.isNaN(minCompleteness) || minCompleteness < 0 || minCompleteness > 1) {
      throw new IllegalArgumentException("la completitud mínima debe estar entre 0 y 1");
    }
    if (Double.isNaN(mandatoryFactor) || mandatoryFactor < 1) {
      throw new IllegalArgumentException(
          "un requisito obligatorio no puede pesar menos que uno deseable");
    }
    structured = Map.copyOf(copy);
  }

  /** Los valores iniciales del ADR-0017 y de `03-lld.md` §2.3, a calibrar durante la validación. */
  public static Weights defaults() {
    EnumMap<Dimension, Double> weights = new EnumMap<>(Dimension.class);
    weights.put(Dimension.SKILLS, 0.35);
    weights.put(Dimension.EXPERIENCE, 0.25);
    weights.put(Dimension.EDUCATION, 0.15);
    weights.put(Dimension.LOCATION_MODALITY, 0.10);
    weights.put(Dimension.SALARY, 0.10);
    weights.put(Dimension.LANGUAGES, 0.05);
    return new Weights(weights, 0.30, 0.5, 2.0);
  }

  public double weight(Dimension dimension) {
    return structured.get(dimension);
  }

  /** *Hash* estable de la configuración, para `algorithm_version` y la clave de caché. */
  public String hash() {
    StringBuilder canonical = new StringBuilder();
    for (Dimension dimension : Dimension.values()) {
      canonical.append(dimension).append('=').append(structured.get(dimension)).append(';');
    }
    canonical.append("alpha=").append(alpha).append(";min=").append(minCompleteness);
    canonical.append(";mandatory=").append(mandatoryFactor);
    return sha256(canonical.toString());
  }

  static String sha256(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
