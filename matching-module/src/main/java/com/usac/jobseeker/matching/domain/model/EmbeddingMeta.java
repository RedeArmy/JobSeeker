package com.usac.jobseeker.matching.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Lo que se sabe de un embedding guardado sin leer el vector: lo que `embedentity` necesita para
 * decidir si ya está al día (mismo *hash* y mismo modelo) sin traer 1024 números por cada consulta.
 */
public record EmbeddingMeta(
    String model, String contentHash, boolean truncated, Instant computedAt) {

  public EmbeddingMeta {
    Objects.requireNonNull(model, "model");
    Objects.requireNonNull(contentHash, "contentHash");
    Objects.requireNonNull(computedAt, "computedAt");
  }
}
