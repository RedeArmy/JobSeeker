package com.usac.jobseeker.matching.application.usecase.embedentity;

import com.usac.jobseeker.matching.application.usecase.recomputescoresforentity.RecomputeScoresForEntityUseCase;
import java.util.Objects;

/**
 * Lo que se hace con cada trabajo de la cola: calcular el vector de un perfil o de una vacante y,
 * después, recalcular los puntajes guardados de esa entidad. Se agrupan porque el trabajador los
 * usa siempre juntos.
 */
public record EmbeddingJobHandlers(
    EmbedProfileUseCase profile, EmbedJobUseCase job, RecomputeScoresForEntityUseCase recompute) {

  public EmbeddingJobHandlers {
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(job, "job");
    Objects.requireNonNull(recompute, "recompute");
  }
}
