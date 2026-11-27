package com.usac.jobseeker.ats.application.usecase.submitapplication;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Si el candidato cumple BR-CAND-01, según `cv`, y con qué perfil y CV postularía. */
public interface ApplicantReadinessPort {

  /**
   * @param missing los requisitos que faltan, como códigos estables de `cv`; vacío si está listo
   * @param candidateProfileId presente si está listo
   * @param cvFileId presente si está listo
   */
  record Readiness(
      List<String> missing, Optional<UUID> candidateProfileId, Optional<UUID> cvFileId) {

    public Readiness {
      missing = List.copyOf(missing);
      Objects.requireNonNull(candidateProfileId, "candidateProfileId");
      Objects.requireNonNull(cvFileId, "cvFileId");
    }

    public boolean ready() {
      return missing.isEmpty() && candidateProfileId.isPresent() && cvFileId.isPresent();
    }
  }

  Readiness check(AccountId candidate);
}
