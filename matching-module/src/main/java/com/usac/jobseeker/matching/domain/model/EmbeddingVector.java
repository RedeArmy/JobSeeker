package com.usac.jobseeker.matching.domain.model;

import java.util.Arrays;

/**
 * Un vector de embedding. Inmutable: copia el arreglo al entrar y al salir, porque quien lo guardó
 * no debe poder alterarlo después. Rechaza valores no finitos, que PostgreSQL no admite en `vector`
 * y que llegarían como un error opaco de la base en vez de uno claro aquí.
 *
 * <p>`toString` no incluye los valores: 1024 números en un log no ayudan y un vector derivado de un
 * perfil es un dato derivado de datos personales (BR-DATA-01).
 */
public final class EmbeddingVector {

  private final float[] components;

  private EmbeddingVector(float[] components) {
    this.components = components;
  }

  public static EmbeddingVector of(float... values) {
    if (values == null || values.length == 0) {
      throw new IllegalArgumentException("un vector necesita al menos una dimensión");
    }
    for (float value : values) {
      if (!Float.isFinite(value)) {
        throw new IllegalArgumentException("un vector solo admite valores finitos");
      }
    }
    return new EmbeddingVector(values.clone());
  }

  public int dimensions() {
    return components.length;
  }

  /** Una copia de los valores. */
  public float[] values() {
    return components.clone();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof EmbeddingVector vector && Arrays.equals(components, vector.components);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(components);
  }

  @Override
  public String toString() {
    return "EmbeddingVector[" + components.length + " dimensiones]";
  }
}
