package com.usac.jobseeker.matching.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * El embedding de una entidad con todo lo necesario para saber de dónde salió: el modelo que lo
 * produjo (parte de `algorithm_version`, BR-MATCH-04), el *hash* de la representación canónica que
 * se embebió (si no cambia, no se vuelve a llamar a Ollama, BR-MATCH-03) y si el texto se redujo.
 */
public record EntityEmbedding(
    EmbeddingVector vector,
    String model,
    String contentHash,
    boolean truncated,
    Instant computedAt) {

  private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

  public EntityEmbedding {
    Objects.requireNonNull(vector, "vector");
    Objects.requireNonNull(model, "model");
    Objects.requireNonNull(contentHash, "contentHash");
    Objects.requireNonNull(computedAt, "computedAt");
    if (model.isBlank()) {
      throw new IllegalArgumentException("el modelo no puede estar vacío");
    }
    if (!SHA256_HEX.matcher(contentHash).matches()) {
      throw new IllegalArgumentException("el hash debe ser un SHA-256 en hexadecimal minúscula");
    }
  }
}
