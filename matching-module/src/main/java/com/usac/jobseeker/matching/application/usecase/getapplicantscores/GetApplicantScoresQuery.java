package com.usac.jobseeker.matching.application.usecase.getapplicantscores;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Los puntajes de los postulantes de una vacante. Interna, para `ats`, que ya comprobó que quien
 * pregunta es el dueño de la vacante (BR-VIS-02): aquí no se vuelve a autorizar. Lleva
 * identificadores como `UUID`, no tipos del dominio de `matching` (ARCH-04).
 */
public record GetApplicantScoresQuery(UUID jobPostingId, List<UUID> candidateProfileIds) {

  public GetApplicantScoresQuery {
    Objects.requireNonNull(jobPostingId, "jobPostingId");
    candidateProfileIds =
        List.copyOf(Objects.requireNonNull(candidateProfileIds, "candidateProfileIds"));
  }
}
