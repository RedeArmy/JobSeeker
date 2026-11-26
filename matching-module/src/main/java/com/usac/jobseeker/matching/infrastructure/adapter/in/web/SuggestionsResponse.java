package com.usac.jobseeker.matching.infrastructure.adapter.in.web;

import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.SuggestedCandidate;
import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.SuggestionsResult;
import com.usac.jobseeker.matching.domain.model.CandidateCard;
import com.usac.jobseeker.matching.domain.model.CompatibilityScore;
import com.usac.jobseeker.matching.domain.model.DimensionScore;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Los candidatos sugeridos de una vacante con su puntaje explicable (BR-MATCH-02, BR-VIS-04) y una
 * tarjeta enmascarada (REQ-C-07). **El coseno bruto no aparece** (ADR-0017), y tampoco nombre,
 * foto, contacto, enlaces ni la cuenta del candidato: son identificadores opacos.
 */
@Schema(description = "Candidatos sugeridos para una vacante, del mejor al peor.")
public record SuggestionsResponse(
    @Schema(
            description =
                "`OK`, `PAUSED` (la vacante está en `REVIEWING`, BR-MATCH-08) o `PENDING_EMBEDDING`"
                    + " (la vacante aún no tiene vector).")
        String status,
    @Schema(
            description =
                "Versión del algoritmo con que se puntuó (BR-MATCH-04); nulo si no hubo candidatos.")
        String algorithmVersion,
    @Schema(
            description =
                "Falso hasta que exista la calibración del protocolo de 300 pares: el puntaje se muestra,"
                    + " marcado como no calibrado, y no sirve para medir la precisión (ADR-0017).")
        boolean calibrated,
    @Schema(
            description =
                "Los candidatos, en el orden final: porcentaje mostrado y, a igualdad, `OPEN_TO_WORK` primero (BR-MATCH-07).")
        List<Candidate> candidates,
    @Schema(
            description =
                "Cuántos perfiles **nuevos** más puedes ver en la ventana de 24 h. Volver a ver uno que"
                    + " ya viste no gasta cuota.",
            example = "180")
        int remainingQuota) {

  /** Un candidato sugerido. */
  @Schema(description = "Un candidato sugerido, sin datos de identidad ni de contacto.")
  public record Candidate(
      @Schema(description = "Posición en el ranking, desde 1.", example = "1") int rank,
      @Schema(description = "Identificador opaco del perfil de candidato.")
          String candidateProfileId,
      Score score,
      @Schema(
              description =
                  "`OPEN_TO_WORK`, `NOT_LOOKING` o `UNSET`, tal como lo ve esta cuenta: `UNSET` si el candidato"
                      + " la excluyó de ver su estado (BR-VIS-06). Solo ordena; nunca cambia el puntaje.")
          String availability,
      Profile profile) {}

  /** El puntaje de compatibilidad con su desglose. */
  @Schema(
      description =
          "Puntaje de 0 a 100 con el desglose por dimensión y la afinidad semántica visible.")
  public record Score(
      @Schema(description = "Porcentaje de compatibilidad mostrado, entero.", example = "78")
          int overall,
      @Schema(
              description =
                  "Fracción de lo que la vacante pide que el candidato declaró (0 a 1). Las listas solo incluyen"
                      + " perfiles con una completitud mínima.",
              example = "0.8")
          double completeness,
      @Schema(
              description =
                  "`α`: peso de la afinidad semántica; 1 si la vacante no tiene requisitos comparables.",
              example = "0.3")
          double alpha,
      @Schema(
              description =
                  "Puntaje de la capa estructurada (0 a 1); 0 si no hay dimensiones disponibles.")
          double structured,
      @Schema(description = "Afinidad semántica calibrada (0 a 1), nunca el coseno bruto.")
          double semantic,
      List<Dimension> dimensions) {}

  /** Una fila del desglose. */
  @Schema(description = "Una dimensión del puntaje con su explicación.")
  public record Dimension(
      @Schema(
              description =
                  "`SKILLS`, `EXPERIENCE`, `EDUCATION`, `LOCATION_MODALITY`, `SALARY` o `LANGUAGES`.")
          String dimension,
      @Schema(
              description =
                  "`AVAILABLE`, `NOT_DECLARED` (el candidato no lo declaró), `NOT_APPLICABLE` (la vacante no lo"
                      + " pide) o `NOT_COMPARABLE` (p. ej. salarios en monedas distintas, BR-MATCH-10).")
          String status,
      @Schema(
              description =
                  "Puntaje de la dimensión (0 a 1); sin significado si no está `AVAILABLE`.")
          double score,
      @Schema(
              description =
                  "Peso con que contribuyó, renormalizado entre las dimensiones disponibles; 0 si quedó excluida.")
          double weight,
      @Schema(description = "Explicación en español con la evidencia citada.")
          String explanation) {}

  /** La tarjeta enmascarada: solo contexto profesional. */
  @Schema(
      description =
          "Contexto profesional del candidato, sin nombre, foto, contacto, enlaces ni instituciones.")
  public record Profile(
      String headline,
      String country,
      @Schema(description = "Años de experiencia acreditados.", example = "4.5")
          double yearsOfExperience,
      @Schema(description = "Nivel de formación terminado más alto.") String highestEducation,
      @Schema(description = "Hasta cinco habilidades.") List<String> topSkills) {}

  static SuggestionsResponse from(SuggestionsResult result) {
    List<Candidate> candidates =
        IntStream.range(0, result.candidates().size())
            .mapToObj(i -> candidate(i + 1, result.candidates().get(i)))
            .toList();
    return new SuggestionsResponse(
        result.status().name(),
        result.algorithmVersion() == null ? null : result.algorithmVersion().value(),
        result.calibrated(),
        candidates,
        result.remainingQuota());
  }

  private static Candidate candidate(int rank, SuggestedCandidate suggested) {
    CompatibilityScore score = suggested.score();
    List<Dimension> dimensions =
        score.breakdown().dimensions().stream().map(SuggestionsResponse::dimension).toList();
    CandidateCard card = suggested.card();
    return new Candidate(
        rank,
        suggested.profile().toString(),
        new Score(
            (int) Math.round(score.overall()),
            score.breakdown().completeness(),
            score.breakdown().alpha(),
            score.breakdown().structured(),
            score.breakdown().semantic(),
            dimensions),
        suggested.availability().name(),
        new Profile(
            card.headline(),
            card.country(),
            card.yearsOfExperience(),
            card.highestEducation(),
            card.topSkills()));
  }

  private static Dimension dimension(DimensionScore d) {
    return new Dimension(
        d.dimension().name(), d.status().name(), d.score(), d.weight(), d.explanation());
  }
}
