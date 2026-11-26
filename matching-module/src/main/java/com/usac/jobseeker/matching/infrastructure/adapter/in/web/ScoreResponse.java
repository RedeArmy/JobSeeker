package com.usac.jobseeker.matching.infrastructure.adapter.in.web;

import com.usac.jobseeker.matching.application.usecase.explainscore.ExplainScoreResult;
import com.usac.jobseeker.matching.application.usecase.explainscore.WhatIfScoreResult;
import com.usac.jobseeker.matching.domain.model.CompatibilityScore;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * La compatibilidad del candidato con una vacante y su desglose (BR-MATCH-02, BR-VIS-01). **El
 * coseno bruto no aparece** (ADR-0017): el porcentaje sale de calibrarlo y combinarlo.
 */
@Schema(
    description = "La compatibilidad del candidato con una vacante, con su desglose por dimensión.")
public record ScoreResponse(
    @Schema(
            description =
                "`OK`, o `PENDING_EMBEDDING` si falta el vector del perfil o de la vacante.")
        String status,
    @Schema(
            description =
                "Versión del algoritmo con que se puntuó (BR-MATCH-04); nulo si está pendiente.")
        String algorithmVersion,
    @Schema(
            description =
                "Falso hasta que exista la calibración del protocolo de 300 pares: el porcentaje es"
                    + " preliminar y no una probabilidad (ADR-0017, ADR-0030).")
        boolean calibrated,
    @Schema(
            description =
                "Hay un cambio del perfil o de la vacante en cola: lo que ves es el último puntaje y se"
                    + " va a actualizar (BR-MATCH-03).")
        boolean updating,
    SuggestionsResponse.Score score) {

  static ScoreResponse from(ExplainScoreResult result) {
    if (result.score() == null) {
      return new ScoreResponse(result.status().name(), null, false, result.updating(), null);
    }
    return of(result.status().name(), result.score(), result.updating());
  }

  private static ScoreResponse of(String status, CompatibilityScore score, boolean updating) {
    return new ScoreResponse(
        status,
        score.version().value(),
        score.calibrated(),
        updating,
        SuggestionsResponse.scoreOf(score));
  }

  /** La respuesta de un «qué pasaría si»: el puntaje de hoy y el hipotético, sin guardar nada. */
  @Schema(description = "El puntaje de hoy y el que tendrías si declararas lo que pruebas.")
  public record WhatIf(
      String status,
      @Schema(description = "Falso hasta calibrar; ver `calibrated` del puntaje.")
          boolean calibrated,
      SuggestionsResponse.Score current,
      SuggestionsResponse.Score hypothetical,
      @Schema(description = "Diferencia de porcentaje mostrado entre el hipotético y el actual.")
          Integer delta) {

    static WhatIf from(WhatIfScoreResult result) {
      if (result.current() == null) {
        return new WhatIf(result.status().name(), false, null, null, null);
      }
      SuggestionsResponse.Score current = SuggestionsResponse.scoreOf(result.current());
      SuggestionsResponse.Score hypothetical = SuggestionsResponse.scoreOf(result.hypothetical());
      return new WhatIf(
          result.status().name(),
          result.current().calibrated(),
          current,
          hypothetical,
          hypothetical.overall() - current.overall());
    }
  }
}
