package com.usac.jobseeker.cv.infrastructure.adapter.in.scheduled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.error.UpstreamUnavailableException;
import com.usac.jobseeker.cv.application.usecase.reconcileorphancvfiles.ReconcileOrphanCvFilesUseCase;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OrphanCvReconciliationJobTest {

  // Un almacén caído solo pospone la pasada: no debe romper el hilo del planificador.
  @Test
  void anUnavailableStorageIsSwallowedAndRetriedOnTheNextRun() {
    var runs = new AtomicInteger();
    var job =
        new OrphanCvReconciliationJob(
            () -> {
              runs.incrementAndGet();
              throw new UpstreamUnavailableException("storage", new IOException("caído"));
            });

    job.run();
    job.run();

    assertThat(runs).hasValue(2);
  }

  // Lo inesperado sí sale: el planificador lo registra como error en vez de ocultarlo.
  @Test
  void anUnexpectedFailureIsNotHidden() {
    var job =
        new OrphanCvReconciliationJob(
            () -> {
              throw new IllegalStateException("fallo inesperado");
            });

    assertThatThrownBy(job::run).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aSuccessfulPassRunsTheUseCaseOnce() {
    var runs = new AtomicInteger();
    var job =
        new OrphanCvReconciliationJob(
            () -> {
              runs.incrementAndGet();
              return new ReconcileOrphanCvFilesUseCase.Result(0, 0, 0, false);
            });

    job.run();

    assertThat(runs).hasValue(1);
  }
}
