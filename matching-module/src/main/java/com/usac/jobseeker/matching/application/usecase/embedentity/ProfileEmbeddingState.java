package com.usac.jobseeker.matching.application.usecase.embedentity;

import com.usac.jobseeker.matching.domain.model.EmbeddingMeta;
import java.util.Objects;
import java.util.Optional;

/**
 * Lo que `matching` sabe de un perfil: la copia de su descubribilidad y, si ya se calculó, los
 * metadatos de su vector. Una fila puede existir sin vector: la crea el evento de descubribilidad
 * si llega antes que el primer cálculo.
 */
public record ProfileEmbeddingState(boolean discoverable, Optional<EmbeddingMeta> embedding) {

  public ProfileEmbeddingState {
    Objects.requireNonNull(embedding, "embedding");
  }
}
