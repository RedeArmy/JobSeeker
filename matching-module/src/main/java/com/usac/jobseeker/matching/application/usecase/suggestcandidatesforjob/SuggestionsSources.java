package com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob;

import com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore.ScoreStorePort;
import com.usac.jobseeker.matching.application.usecase.embedentity.CandidateSignalsReader;
import com.usac.jobseeker.matching.application.usecase.embedentity.EmbeddingStorePort;
import com.usac.jobseeker.matching.application.usecase.embedentity.JobSignalsReader;
import java.util.Objects;

/**
 * De dónde salen los sugeridos: la vacante propia y su vector, los candidatos más cercanos, sus
 * señales y la disponibilidad que ve el reclutador. Se agrupan porque son lo que el caso de uso
 * lee, frente a la confianza, el registro de vistos, la política y el modelo de puntaje, que son lo
 * que decide.
 */
public record SuggestionsSources(
    OwnedJobPort ownedJobs,
    EmbeddingStorePort embeddings,
    CandidateRetrievalPort retrieval,
    CandidateSignalsReader candidates,
    JobSignalsReader jobs,
    AvailabilityPort availability,
    ScoreStorePort scores) {

  public SuggestionsSources {
    Objects.requireNonNull(ownedJobs, "ownedJobs");
    Objects.requireNonNull(embeddings, "embeddings");
    Objects.requireNonNull(retrieval, "retrieval");
    Objects.requireNonNull(candidates, "candidates");
    Objects.requireNonNull(jobs, "jobs");
    Objects.requireNonNull(availability, "availability");
    Objects.requireNonNull(scores, "scores");
  }
}
