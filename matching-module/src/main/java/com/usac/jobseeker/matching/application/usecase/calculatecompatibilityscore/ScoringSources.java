package com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore;

import com.usac.jobseeker.matching.application.usecase.embedentity.CandidateSignalsReader;
import com.usac.jobseeker.matching.application.usecase.embedentity.JobSignalsReader;
import java.util.Objects;

/**
 * Lo que necesita cualquier caso de uso que puntúa o recalcula pares: las señales de los dos lados,
 * la similitud entre sus vectores, qué cuenta es dueña de cada perfil y dónde se guarda el
 * resultado. Se agrupan porque viajan juntos y evitan constructores de diez parámetros.
 */
public record ScoringSources(
    CandidateSignalsReader candidates,
    JobSignalsReader jobs,
    PairSimilarityPort similarity,
    ProfileDirectoryPort directory,
    ScoreStorePort store) {

  public ScoringSources {
    Objects.requireNonNull(candidates, "candidates");
    Objects.requireNonNull(jobs, "jobs");
    Objects.requireNonNull(similarity, "similarity");
    Objects.requireNonNull(directory, "directory");
    Objects.requireNonNull(store, "store");
  }
}
