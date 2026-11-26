package com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob;

import com.usac.jobseeker.matching.domain.model.Availability;
import com.usac.jobseeker.matching.domain.model.CandidateCard;
import com.usac.jobseeker.matching.domain.model.CandidateProfileId;
import com.usac.jobseeker.matching.domain.model.CompatibilityScore;
import java.util.Objects;

/**
 * Un candidato sugerido: su identificador opaco, el puntaje con su desglose, la disponibilidad que
 * *este* reclutador puede ver y la tarjeta enmascarada (REQ-C-07). No lleva la cuenta, el nombre ni
 * ningún dato de contacto.
 */
public record SuggestedCandidate(
    CandidateProfileId profile,
    CompatibilityScore score,
    Availability availability,
    CandidateCard card) {

  public SuggestedCandidate {
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(score, "score");
    Objects.requireNonNull(availability, "availability");
    Objects.requireNonNull(card, "card");
  }
}
