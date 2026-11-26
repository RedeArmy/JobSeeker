package com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.domain.model.CandidateProfileId;
import java.util.Objects;

/**
 * Un candidato recuperado en la etapa 1 del pipeline, con su similitud coseno **cruda** y la cuenta
 * dueña del perfil (para leer sus señales en la etapa 2). Es solo un criterio de recuperación: el
 * puntaje que se muestra sale de calibrarla y combinarla (ADR-0017), y ni el coseno bruto ni la
 * cuenta llegan nunca a una respuesta.
 */
public record NearestProfile(
    CandidateProfileId candidate, AccountId account, double cosineSimilarity) {

  public NearestProfile {
    Objects.requireNonNull(candidate, "candidate");
    Objects.requireNonNull(account, "account");
  }
}
