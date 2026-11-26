package com.usac.jobseeker.matching.infrastructure.adapter.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.ScoringModel;
import com.usac.jobseeker.matching.domain.model.Calibration;
import com.usac.jobseeker.matching.domain.model.Weights;
import com.usac.jobseeker.matching.domain.service.CompatibilityAggregator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** Nada debe fallar en silencio: el indicador dice si producción publica un puntaje calibrado. */
class ScoringCalibrationMetricsTest {

  private static double calibratedGauge(SimpleMeterRegistry registry) {
    return registry.get(ScoringCalibrationMetrics.CALIBRATED).gauge().value();
  }

  @Test
  void withoutFittedParametersTheGaugeIsZeroAndCarriesTheAlgorithmVersion() {
    var registry = new SimpleMeterRegistry();

    new ScoringCalibrationMetrics(
            new ScoringModel(Weights.defaults(), Calibration.uncalibrated("modelo")))
        .bindTo(registry);

    assertThat(calibratedGauge(registry)).isZero();
    assertThat(
            registry
                .get(ScoringCalibrationMetrics.CALIBRATED)
                .gauge()
                .getId()
                .getTag("algorithm_version"))
        .startsWith(CompatibilityAggregator.CODE_VERSION + "|modelo|");
  }

  @Test
  void withFittedParametersTheGaugeIsOne() {
    var registry = new SimpleMeterRegistry();
    var calibration = new Calibration("modelo", Calibration.Kind.PLATT, 5, -2);

    new ScoringCalibrationMetrics(new ScoringModel(Weights.defaults(), calibration))
        .bindTo(registry);

    assertThat(calibratedGauge(registry)).isEqualTo(1.0);
  }

  // La etiqueta es la versión, que cambia con la configuración: una nueva calibración es otra
  // serie.
  @Test
  void aDifferentCalibrationIsADifferentVersionTag() {
    var uncalibrated = new SimpleMeterRegistry();
    var fitted = new SimpleMeterRegistry();

    new ScoringCalibrationMetrics(
            new ScoringModel(Weights.defaults(), Calibration.uncalibrated("m")))
        .bindTo(uncalibrated);
    new ScoringCalibrationMetrics(
            new ScoringModel(
                Weights.defaults(), new Calibration("m", Calibration.Kind.PLATT, 5, -2)))
        .bindTo(fitted);

    assertThat(
            uncalibrated
                .get(ScoringCalibrationMetrics.CALIBRATED)
                .gauge()
                .getId()
                .getTag("algorithm_version"))
        .isNotEqualTo(
            fitted
                .get(ScoringCalibrationMetrics.CALIBRATED)
                .gauge()
                .getId()
                .getTag("algorithm_version"));
  }
}
