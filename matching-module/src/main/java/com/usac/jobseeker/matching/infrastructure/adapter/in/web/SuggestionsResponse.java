package com.usac.jobseeker.matching.infrastructure.adapter.in.web;

import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.SuggestionsResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Los candidatos sugeridos de una vacante. **No lleva similitud ni puntaje**: el coseno bruto no es
 * un porcentaje de compatibilidad y nunca se entrega (ADR-0017); el orden de la lista es lo único
 * que la recuperación aporta. Tampoco lleva datos del candidato: son identificadores opacos.
 */
@Schema(description = "Candidatos sugeridos para una vacante, del más al menos cercano.")
public record SuggestionsResponse(
    @Schema(
            description =
                "`OK`, `PAUSED` (la vacante está en `REVIEWING`, BR-MATCH-08) o `PENDING_EMBEDDING`"
                    + " (la vacante aún no tiene vector).")
        String status,
    @Schema(description = "Los perfiles descubribles más cercanos, en orden.")
        List<Candidate> candidates,
    @Schema(
            description =
                "Cuántos perfiles **nuevos** más puedes ver en la ventana de 24 h. Volver a ver uno que"
                    + " ya viste no gasta cuota.",
            example = "180")
        int remainingQuota) {

  /** Un candidato sugerido; solo su posición y su identificador opaco. */
  @Schema(description = "Un candidato sugerido, sin datos personales.")
  public record Candidate(
      @Schema(description = "Posición en el ranking, desde 1.", example = "1") int rank,
      @Schema(description = "Identificador opaco del perfil de candidato.")
          String candidateProfileId) {}

  static SuggestionsResponse from(SuggestionsResult result) {
    List<Candidate> candidates =
        IntStream.range(0, result.candidates().size())
            .mapToObj(i -> new Candidate(i + 1, result.candidates().get(i).toString()))
            .toList();
    return new SuggestionsResponse(result.status().name(), candidates, result.remainingQuota());
  }
}
