package com.usac.jobseeker.cv.infrastructure.adapter.in.scheduled;

import com.usac.jobseeker.common.domain.error.UpstreamUnavailableException;
import com.usac.jobseeker.cv.application.usecase.reconcileorphancvfiles.ReconcileOrphanCvFilesUseCase;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Dispara la reconciliación de CV huérfanos (TD-041). El `@Scheduled` vive aquí y no en el caso de
 * uso (ARCH-09): el caso de uso no sabe que lo llama un reloj. La espera inicial evita que compita
 * con un arranque recién hecho o con las pruebas, y un almacén caído solo pospone la pasada.
 */
public class OrphanCvReconciliationJob {

  private static final Logger LOG = LoggerFactory.getLogger(OrphanCvReconciliationJob.class);

  private final ReconcileOrphanCvFilesUseCase reconcile;

  public OrphanCvReconciliationJob(ReconcileOrphanCvFilesUseCase reconcile) {
    this.reconcile = Objects.requireNonNull(reconcile, "reconcile");
  }

  @Scheduled(
      initialDelayString = "${jobseeker.cv.orphan-reconciliation-initial-delay:15m}",
      fixedDelayString = "${jobseeker.cv.orphan-reconciliation-interval:6h}")
  public void run() {
    try {
      reconcile.execute();
    } catch (UpstreamUnavailableException e) {
      LOG.warn(
          "event=orphan_cv_reconciliation_skipped Almacén no disponible; se reintenta en la"
              + " próxima pasada");
    }
  }
}
