package com.usac.jobseeker.matching.application.usecase.embedentity;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). El límite transaccional es
 * el caso de uso: la anonimización borra el vector y descarta lo pendiente de una vez.
 *
 * <ul>
 *   <li>`UPDATED` se aplaza por la **ventana de agrupación** (ADR-0017, decisión 10): diez
 *       ediciones seguidas se vuelven un solo cálculo.
 *   <li>`DISCOVERABILITY_CHANGED` se pide sin espera: ocultar un perfil debe notarse en segundos.
 *   <li>`ANONYMIZED` actúa en el acto y no depende del modelo: es lo que BR-DATA-05 exige.
 * </ul>
 */
public class HandleProfileEventService implements HandleProfileEventUseCase {

  private static final String USE_CASE = "handle_profile_event";

  private final EmbeddingWorkQueuePort queue;
  private final EmbeddingStorePort store;
  private final UseCaseMetricsPort metrics;
  private final Clock clock;
  private final Duration window;

  public HandleProfileEventService(
      EmbeddingWorkQueuePort queue,
      EmbeddingStorePort store,
      UseCaseMetricsPort metrics,
      Clock clock,
      Duration window) {
    this.queue = Objects.requireNonNull(queue, "queue");
    this.store = Objects.requireNonNull(store, "store");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.window = Objects.requireNonNull(window, "window");
  }

  @Override
  @Transactional
  public void execute(HandleProfileEventCommand command) {
    switch (command.kind()) {
      case UPDATED ->
          queue.request(
              EmbeddingEntity.PROFILE, command.accountId().value(), clock.instant().plus(window));
      case DISCOVERABILITY_CHANGED ->
          queue.request(EmbeddingEntity.PROFILE, command.accountId().value(), clock.instant());
      case ANONYMIZED -> {
        store.anonymizeProfile(command.accountId());
        queue.discard(EmbeddingEntity.PROFILE, command.accountId().value());
      }
    }
    metrics.count(USE_CASE, command.kind().name().toLowerCase(Locale.ROOT));
  }
}
