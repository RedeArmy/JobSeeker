package com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob;

import com.usac.jobseeker.matching.application.usecase.embedentity.EmbeddingStorePort;
import java.util.Objects;

/**
 * De dónde salen los sugeridos: la vacante propia, su vector y los candidatos más cercanos. Se
 * agrupan porque son lo que el caso de uso lee, frente a la confianza, el registro de vistos y la
 * política, que son lo que decide.
 */
public record SuggestionsSources(
    OwnedJobPort ownedJobs, EmbeddingStorePort embeddings, CandidateRetrievalPort retrieval) {

  public SuggestionsSources {
    Objects.requireNonNull(ownedJobs, "ownedJobs");
    Objects.requireNonNull(embeddings, "embeddings");
    Objects.requireNonNull(retrieval, "retrieval");
  }
}
