package com.usac.jobseeker.matching.infrastructure.adapter.in.job;

import com.usac.jobseeker.matching.application.usecase.migratealgorithmversion.MigrateAlgorithmVersionUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Mantenimiento del puntaje guardado: pone al día en segundo plano los pares calculados con otra
 * versión del algoritmo (ADR-0017, decisión 10) y purga las fotos que pasaron la retención. Nunca
 * bloquea el servicio: trabaja por lotes y cede entre pasadas. Una pasada fallida no cancela las
 * siguientes de la planificación.
 */
public class ScoreMaintenanceWorker {

  private static final Logger LOG = LoggerFactory.getLogger(ScoreMaintenanceWorker.class);
  private static final int MAX_BATCHES_PER_TICK = 20;

  private final MigrateAlgorithmVersionUseCase maintenance;

  public ScoreMaintenanceWorker(MigrateAlgorithmVersionUseCase maintenance) {
    this.maintenance = maintenance;
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  @Scheduled(fixedDelayString = "${jobseeker.matching.scoring.maintenance-interval:10m}")
  void run() {
    try {
      for (int batch = 0; batch < MAX_BATCHES_PER_TICK; batch++) {
        if (maintenance.execute() == 0) {
          break;
        }
      }
      maintenance.purgeExpiredSnapshots();
    } catch (RuntimeException failure) {
      LOG.error(
          "event=score_maintenance_failed Falló una pasada de mantenimiento del puntaje guardado",
          failure);
    }
  }
}
