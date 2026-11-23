package com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob;

import com.usac.jobseeker.matching.domain.model.CandidateProfileId;
import java.util.Objects;

/**
 * Un candidato recuperado en la etapa 1 del pipeline, con su similitud coseno **cruda**. Es solo un
 * criterio de recuperación: el puntaje que se muestra sale de calibrarla y combinarla (ADR-0017), y
 * el coseno bruto no llega nunca a una respuesta.
 */
public record NearestProfile(CandidateProfileId candidate, double cosineSimilarity) {

  public NearestProfile {
    Objects.requireNonNull(candidate, "candidate");
  }
}
