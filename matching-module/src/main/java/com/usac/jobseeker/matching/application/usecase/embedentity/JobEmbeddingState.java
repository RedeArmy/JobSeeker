package com.usac.jobseeker.matching.application.usecase.embedentity;

import com.usac.jobseeker.matching.domain.model.EmbeddingMeta;
import java.util.Objects;
import java.util.Optional;

/**
 * Lo que `matching` sabe de una vacante: la copia de si está publicada y los metadatos de su
 * vector.
 */
public record JobEmbeddingState(boolean published, Optional<EmbeddingMeta> embedding) {

  public JobEmbeddingState {
    Objects.requireNonNull(embedding, "embedding");
  }
}
