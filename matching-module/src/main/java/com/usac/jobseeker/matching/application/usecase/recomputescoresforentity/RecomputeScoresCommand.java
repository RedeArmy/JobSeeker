package com.usac.jobseeker.matching.application.usecase.recomputescoresforentity;

import com.usac.jobseeker.matching.application.usecase.embedentity.EmbeddingEntity;
import java.util.Objects;
import java.util.UUID;

/**
 * Recalcular los puntajes guardados de una entidad que acaba de procesarse: la cuenta de un perfil
 * o el identificador de una vacante, igual que la clave del trabajo de la cola.
 */
public record RecomputeScoresCommand(EmbeddingEntity entity, UUID key) {

  public RecomputeScoresCommand {
    Objects.requireNonNull(entity, "entity");
    Objects.requireNonNull(key, "key");
  }
}
