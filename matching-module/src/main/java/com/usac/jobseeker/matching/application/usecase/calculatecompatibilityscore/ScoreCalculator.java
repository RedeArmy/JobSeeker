package com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore;

import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.ScoringModel;
import com.usac.jobseeker.matching.domain.model.AlgorithmVersion;
import com.usac.jobseeker.matching.domain.model.CandidateSignals;
import com.usac.jobseeker.matching.domain.model.CompatibilityScore;
import com.usac.jobseeker.matching.domain.model.JobSignals;
import com.usac.jobseeker.matching.domain.service.CompatibilityAggregator;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Lo que comparten los casos de uso que puntúan un par: el modelo de puntaje (pesos y calibración,
 * configuración) y la fecha de hoy. Es aritmética pura sobre señales y un coseno ya calculados: no
 * llama a ningún modelo (ADR-0017, decisión 1).
 */
public final class ScoreCalculator {

  private final ScoringModel scoring;
  private final Clock clock;

  public ScoreCalculator(ScoringModel scoring, Clock clock) {
    this.scoring = Objects.requireNonNull(scoring, "scoring");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public CompatibilityScore score(CandidateSignals candidate, JobSignals job, double cosine) {
    return CompatibilityAggregator.score(
        candidate, job, cosine, scoring.weights(), scoring.calibration(), today());
  }

  /** La versión con que se puntúa hoy: lo que distingue un puntaje vigente de uno viejo. */
  public AlgorithmVersion version() {
    return new AlgorithmVersion(
        CompatibilityAggregator.CODE_VERSION,
        scoring.calibration().model(),
        scoring.weights().hash(),
        scoring.calibration().hash());
  }

  public LocalDate today() {
    return clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
  }
}
