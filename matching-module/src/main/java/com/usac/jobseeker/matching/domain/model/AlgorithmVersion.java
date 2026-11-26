package com.usac.jobseeker.matching.domain.model;

import java.util.Objects;

/**
 * La versión completa del algoritmo (BR-MATCH-04, ADR-0017 decisión 8): el código, el modelo de
 * embeddings, el *hash* de los pesos y el de la calibración. Cambiar cualquiera produce otra
 * versión, no una mutación silenciosa del mismo número.
 */
public record AlgorithmVersion(
    String code, String model, String weightsHash, String calibrationHash) {

  public AlgorithmVersion {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(model, "model");
    Objects.requireNonNull(weightsHash, "weightsHash");
    Objects.requireNonNull(calibrationHash, "calibrationHash");
  }

  /**
   * Reconstruye la versión desde su forma corta (`código|modelo|hash8|hash8`). Los *hashes* quedan
   * abreviados a 8 caracteres, que es lo único que se guarda; sirve para mostrar y comparar, no
   * para recalcular el *hash* completo.
   */
  public static AlgorithmVersion parse(String value) {
    String[] parts = value.split("\\|", -1);
    final int expectedParts = 4;
    if (parts.length != expectedParts) {
      throw new IllegalArgumentException("una versión del algoritmo tiene cuatro partes");
    }
    return new AlgorithmVersion(parts[0], parts[1], parts[2], parts[3]);
  }

  /** La forma corta que se guarda y se muestra; los *hashes* se abrevian a 8 caracteres. */
  public String value() {
    return code
        + "|"
        + model
        + "|"
        + weightsHash.substring(0, 8)
        + "|"
        + calibrationHash.substring(0, 8);
  }
}
