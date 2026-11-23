package com.usac.jobseeker.app.infrastructure.outbox;

import com.usac.jobseeker.common.application.outbox.OutboxEvent;
import com.usac.jobseeker.common.application.outbox.OutboxEventConsumer;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Despacha la bandeja a los consumidores (ADR-0016, decisiones 2, 4 y 5). La entrega es **al menos
 * una vez**: si un consumidor falla se reintenta con retroceso exponencial y, agotados los
 * intentos, la fila queda en FAILED. Una fila cuyo tipo nadie atiende se da por procesada, porque
 * reintentarla no cambiaría nada.
 *
 * <p>Cada pasada reclama con `SKIP LOCKED` dentro de una transacción; si el proceso muere a mitad,
 * las filas vuelven a estar pendientes y los consumidores, idempotentes, las repiten sin daño.
 */
public class OutboxPoller {

  private static final Logger LOG = LoggerFactory.getLogger(OutboxPoller.class);

  private final OutboxStore store;
  private final List<OutboxEventConsumer> consumers;
  private final OutboxProperties properties;
  private final TransactionTemplate transaction;
  private final MeterRegistry meters;

  public OutboxPoller(
      OutboxStore store,
      List<OutboxEventConsumer> consumers,
      OutboxProperties properties,
      TransactionTemplate transaction,
      MeterRegistry meters) {
    this.store = store;
    this.consumers = List.copyOf(consumers);
    this.properties = properties;
    this.transaction = transaction;
    this.meters = meters;
  }

  /** Una pasada; devuelve cuántas filas reclamó. */
  public int pollOnce() {
    // `execute` solo devuelve nulo si el callback lo devuelve, y aquí nunca lo hace.
    return transaction.execute(
        status -> {
          List<OutboxStore.Claimed> batch = store.claim(properties.batchSize());
          batch.forEach(this::dispatch);
          return batch.size();
        });
  }

  // Cualquier fallo de un consumidor se reintenta con retroceso; no debe tumbar el lote entero.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private void dispatch(OutboxStore.Claimed claimed) {
    OutboxEvent event = claimed.event();
    try {
      for (OutboxEventConsumer consumer : consumers) {
        if (consumer.handles(event.eventType())) {
          consumer.consume(event);
        }
      }
      store.markProcessed(event.id());
      count("processed");
    } catch (RuntimeException e) {
      int attempt = claimed.attempts() + 1;
      if (attempt >= properties.maxAttempts()) {
        store.markFailed(event.id());
        count("failed");
        LOG.error(
            "event=outbox_failed eventType={} attempts={} Evento de la bandeja agotó sus intentos",
            event.eventType(),
            attempt,
            e);
      } else {
        store.markRetry(
            event.id(), properties.backoff(attempt, ThreadLocalRandom.current().nextDouble()));
        count("retried");
        LOG.warn(
            "event=outbox_retry eventType={} attempt={} Evento de la bandeja se reintentará",
            event.eventType(),
            attempt,
            e);
      }
    }
  }

  private void count(String outcome) {
    meters.counter("jobseeker_outbox_events_total", "outcome", outcome).increment();
  }
}
