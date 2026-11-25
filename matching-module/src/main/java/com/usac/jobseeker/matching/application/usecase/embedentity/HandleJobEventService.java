package com.usac.jobseeker.matching.application.usecase.embedentity;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). Solo encola: el cálculo y
 * la sincronización de las copias los hace el trabajador, que relee el estado actual de la vacante.
 * Así un evento tardío o repetido es inofensivo (nivel disparado, ADR-0016).
 *
 * <ul>
 *   <li>`CONTENT_UPDATED` se aplaza por la **ventana de agrupación** (ADR-0017, decisión 10).
 *   <li>`STATE_CHANGED` se pide sin espera: cerrar una vacante o pasarla a `REVIEWING` debe notarse
 *       en segundos, y publicarla debe empezar a puntuarse cuanto antes.
 * </ul>
 */
public class HandleJobEventService implements HandleJobEventUseCase {

  private static final String USE_CASE = "handle_job_event";

  private final EmbeddingWorkQueuePort queue;
  private final UseCaseMetricsPort metrics;
  private final Clock clock;
  private final Duration window;

  public HandleJobEventService(
      EmbeddingWorkQueuePort queue, UseCaseMetricsPort metrics, Clock clock, Duration window) {
    this.queue = Objects.requireNonNull(queue, "queue");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.window = Objects.requireNonNull(window, "window");
  }

  @Override
  @Transactional
  public void execute(HandleJobEventCommand command) {
    switch (command.kind()) {
      case CONTENT_UPDATED ->
          queue.request(EmbeddingEntity.JOB, command.jobId().value(), clock.instant().plus(window));
      case STATE_CHANGED ->
          queue.request(EmbeddingEntity.JOB, command.jobId().value(), clock.instant());
    }
    metrics.count(USE_CASE, command.kind().name().toLowerCase(Locale.ROOT));
  }
}
