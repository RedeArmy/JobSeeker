package com.usac.jobseeker.matching.infrastructure.adapter.out.metrics;

import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.ScoringModel;
import com.usac.jobseeker.matching.domain.model.AlgorithmVersion;
import com.usac.jobseeker.matching.domain.service.CompatibilityAggregator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hace visible si el puntaje que se publica está calibrado (ADR-0017, decisión 5; ADR-0030). Sin
 * los parámetros del protocolo de 300 pares el puntaje es **preliminar**: sirve para ordenar y
 * explicar, no como probabilidad ni para medir la precisión. Nada debe fallar en silencio: este
 * indicador permite alertar si producción corre sin calibrar y el aviso de arranque lo deja en el
 * registro.
 *
 * <p>`jobseeker_matching_scoring_calibrated{algorithm_version}` vale 1 si hay calibración ajustada
 * y 0 si no. La etiqueta es la versión del algoritmo, que cambia solo al cambiar la configuración.
 * Sin datos personales.
 */
public class ScoringCalibrationMetrics implements MeterBinder {

  static final String CALIBRATED = "jobseeker_matching_scoring_calibrated";
  private static final Logger LOG = LoggerFactory.getLogger(ScoringCalibrationMetrics.class);

  private final ScoringModel scoring;

  public ScoringCalibrationMetrics(ScoringModel scoring) {
    this.scoring = scoring;
  }

  @Override
  public void bindTo(MeterRegistry meters) {
    AlgorithmVersion version =
        new AlgorithmVersion(
            CompatibilityAggregator.CODE_VERSION,
            scoring.calibration().model(),
            scoring.weights().hash(),
            scoring.calibration().hash());
    boolean calibrated = scoring.calibration().calibrated();
    Gauge.builder(CALIBRATED, () -> calibrated ? 1 : 0)
        .description(
            "1 si el puntaje de compatibilidad usa una calibración ajustada; 0 si es preliminar")
        .tag("algorithm_version", version.value())
        .register(meters);
    if (!calibrated && LOG.isWarnEnabled()) {
      LOG.warn(
          "event=scoring_uncalibrated algorithm_version={} El puntaje de compatibilidad se publica"
              + " sin calibrar: es preliminar, sirve para ordenar y explicar, y no debe usarse para"
              + " medir la precisión hasta tener los parámetros del protocolo de 300 pares",
          version.value());
    }
  }
}
