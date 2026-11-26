package com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob;

import com.usac.jobseeker.matching.domain.model.Calibration;
import com.usac.jobseeker.matching.domain.model.Weights;
import java.util.Objects;

/** Los parámetros con que se puntúa: pesos y calibración, ambos configuración (BR-MATCH-05). */
public record ScoringModel(Weights weights, Calibration calibration) {

  public ScoringModel {
    Objects.requireNonNull(weights, "weights");
    Objects.requireNonNull(calibration, "calibration");
  }
}
